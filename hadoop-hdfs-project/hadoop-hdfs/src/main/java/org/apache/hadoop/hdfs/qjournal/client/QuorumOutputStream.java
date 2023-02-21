/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.qjournal.client;

import java.io.IOException;

import org.apache.hadoop.hdfs.server.namenode.EditLogOutputStream;
import org.apache.hadoop.hdfs.server.namenode.EditsDoubleBuffer;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp;
import org.apache.hadoop.io.DataOutputBuffer;

/**
 * EditLogOutputStream implementation that writes to a quorum of
 * remote journals.
 */
class QuorumOutputStream extends EditLogOutputStream {
  private final AsyncLoggerSet loggers;
  private EditsDoubleBuffer buf;
  private final long segmentTxId;
  private final int writeTimeoutMs;

  public QuorumOutputStream(AsyncLoggerSet loggers,
      long txId, int outputBufferCapacity,
      int writeTimeoutMs, int logVersion) throws IOException {
    super();
    //不同的流，对应的双缓冲都是自己独立的，每个流在实例化的时候，都会实例化一个buffer
    this.buf = new EditsDoubleBuffer(outputBufferCapacity);
    this.loggers = loggers;
    this.segmentTxId = txId;
    this.writeTimeoutMs = writeTimeoutMs;
    setCurrentLogVersion(logVersion);
  }

  @Override
  public void write(FSEditLogOp op) throws IOException {
    buf.writeOp(op, getCurrentLogVersion());
  }

  @Override
  public void writeRaw(byte[] bytes, int offset, int length) throws IOException {
    //人家走的也是双缓冲机制
    //先写入内存，然后再是flush
    buf.writeRaw(bytes, offset, length);
  }

  @Override
  public void create(int layoutVersion) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() throws IOException {
    if (buf != null) {
      buf.close();
      buf = null;
    }
  }

  @Override
  public void abort() throws IOException {
    QuorumJournalManager.LOG.warn("Aborting " + this);
    buf = null;
    close();
  }

  @Override
  public void setReadyToFlush() throws IOException {
    buf.setReadyToFlush();
  }

  @Override
  public boolean shouldForceSync() {
    return buf.shouldForceSync();
  }

  @Override
  protected void flushAndSync(boolean durable) throws IOException {
    int numReadyBytes = buf.countReadyBytes();
    if (numReadyBytes > 0) {
      int numReadyTxns = buf.countReadyTxns();
      long firstTxToFlush = buf.getFirstReadyTxId();

      assert numReadyTxns > 0;

      // Copy from our double-buffer into a new byte array. This is for
      // two reasons:
      // 1) The IPC code has no way of specifying to send only a slice of
      //    a larger array.
      // 2) because the calls to the underlying nodes are asynchronous, we
      //    need a defensive copy to avoid accidentally mutating the buffer
      //    before it is sent.
      /*将双缓冲内存中的数据拷贝到一个新的字节数组中去
      1) IPC代码，rpc调用，就是说他需要一次性将缓冲区的里的数据全部发送到journal node上去，通过rpc接口请求
      没有办法每次就发送一个大的数组的一部分
      2) 因为对journal node的调用是异步的，他需要一个防御性的拷贝，来避免在发送这段数据之前
      导致这个buffer缓冲被人修改了

      它的意思就是什么呢？需要一次性把所有的buffer里的数据都发送到journal node里去
      然后因为他是异步发送，为了避免说在异步发送的时候，还没发送，buffer的数据被人给修改了
      所以在这里他会将buffer里的数据先拷贝到一个新的字节数组里去
      * */
      DataOutputBuffer bufToSend = new DataOutputBuffer(numReadyBytes);
      buf.flushTo(bufToSend);
      //上面两行代码就是将buffer里的数据，先写入一个新的DataOutputBuffer 里取
      assert bufToSend.getLength() == numReadyBytes;
      //这里就是将新的DataOutputBuffer里的数据放到了一个byte[] data数组里去
      byte[] data = bufToSend.getData();
      assert data.length == bufToSend.getLength();

      //通过AsyncLoggerSet这个组件，发送edits log到大多数的journalnode上去
      //这个过程是异步发送的
      //这个AsyncLogger都是基于底层的一个单线程线程池异步发送网络请求，rpc调用
      //QuorumCall里可以获取到每个AsyncLogger对应点的future
      QuorumCall<AsyncLogger, Void> qcall = loggers.sendEdits(
          segmentTxId, firstTxToFlush,
          numReadyTxns, data);
      //在这里必须是等待edits log到大多数的journal node都成功以后，才可以往下执行
      //在这里会阻塞住，等待    监听器是qcall是上面方法返回值
      //通过这里，观察每个Future的结果，来实现quorum算法
      //3个journal node，必须有（3/2）+1 = 2个推送成功了才可以
      //5个journal node，必须有（5/2）+1 = 3个推送成功了才可以
      loggers.waitForWriteQuorum(qcall, writeTimeoutMs, "sendEdits");
      
      // Since we successfully wrote this batch, let the loggers know. Any future
      // RPCs will thus let the loggers know of the most recent transaction, even
      // if a logger has fallen behind.
      loggers.setCommittedTxId(firstTxToFlush + numReadyTxns - 1);
    }
  }

  @Override
  public String generateReport() {
    StringBuilder sb = new StringBuilder();
    sb.append("Writing segment beginning at txid " + segmentTxId + ". \n");
    loggers.appendReport(sb);
    return sb.toString();
  }
  
  @Override
  public String toString() {
    return "QuorumOutputStream starting at txid " + segmentTxId;
  }
}
