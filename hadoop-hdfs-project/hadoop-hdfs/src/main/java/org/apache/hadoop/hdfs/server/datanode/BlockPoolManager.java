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
package org.apache.hadoop.hdfs.server.datanode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.PrivilegedExceptionAction;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.security.UserGroupInformation;

import org.apache.hadoop.thirdparty.com.google.common.base.Joiner;
import org.apache.hadoop.thirdparty.com.google.common.base.Preconditions;
import org.apache.hadoop.thirdparty.com.google.common.collect.Lists;
import org.apache.hadoop.thirdparty.com.google.common.collect.Maps;
import org.apache.hadoop.thirdparty.com.google.common.collect.Sets;
import org.slf4j.Logger;

/**
 * Manages the BPOfferService objects for the data node.
 * Creation, removal, starting, stopping, shutdown on BPOfferService
 * objects must be done via APIs in this class.
 */
@InterfaceAudience.Private
class BlockPoolManager {
  private static final Logger LOG = DataNode.LOG;
  
  private final Map<String, BPOfferService> bpByNameserviceId =
    Maps.newHashMap();
  private final Map<String, BPOfferService> bpByBlockPoolId =
    Maps.newHashMap();
  private final List<BPOfferService> offerServices =
      new CopyOnWriteArrayList<>();

  private final DataNode dn;

  //This lock is used only to ensure exclusion of refreshNamenodes
  private final Object refreshNamenodesLock = new Object();
  
  BlockPoolManager(DataNode dn) {
    this.dn = dn;
  }
  
  synchronized void addBlockPool(BPOfferService bpos) {
    Preconditions.checkArgument(offerServices.contains(bpos),
        "Unknown BPOS: %s", bpos);
    if (bpos.getBlockPoolId() == null) {
      throw new IllegalArgumentException("Null blockpool id");
    }
    bpByBlockPoolId.put(bpos.getBlockPoolId(), bpos);
  }
  
  /**
   * Returns a list of BPOfferService objects. The underlying list
   * implementation is a CopyOnWriteArrayList so it can be safely
   * iterated while BPOfferServices are being added or removed.
   *
   * Caution: The BPOfferService returned could be shutdown any time.
   */
  synchronized List<BPOfferService> getAllNamenodeThreads() {
    return Collections.unmodifiableList(offerServices);
  }
      
  synchronized BPOfferService get(String bpid) {
    return bpByBlockPoolId.get(bpid);
  }
  
  synchronized void remove(BPOfferService t) {
    offerServices.remove(t);
    if (t.hasBlockPoolId()) {
      // It's possible that the block pool never successfully registered
      // with any NN, so it was never added it to this map
      bpByBlockPoolId.remove(t.getBlockPoolId());
    }
    
    boolean removed = false;
    for (Iterator<BPOfferService> it = bpByNameserviceId.values().iterator();
         it.hasNext() && !removed;) {
      BPOfferService bpos = it.next();
      if (bpos == t) {
        it.remove();
        LOG.info("Removed " + bpos);
        removed = true;
      }
    }
    
    if (!removed) {
      LOG.warn("Couldn't remove BPOS " + t + " from bpByNameserviceId map");
    }
  }
  
  void shutDownAll(List<BPOfferService> bposList) throws InterruptedException {
    for (BPOfferService bpos : bposList) {
      bpos.stop(); //interrupts the threads
    }
    //now join
    for (BPOfferService bpos : bposList) {
      bpos.join();
    }
  }
  
  synchronized void startAll() throws IOException {
    try {
      //这里是极为关键的逻辑
      //datanode启动的时候，要向namenode去进行注册，让namenode感知到自己
      UserGroupInformation.getLoginUser().doAs(
          new PrivilegedExceptionAction<Object>() {
            @Override
            public Object run() throws Exception {
              for (BPOfferService bpos : offerServices) {
                //在启动BPServcieActor线程
                bpos.start();
              }
              return null;
            }
          });
    } catch (InterruptedException ex) {
      IOException ioe = new IOException();
      ioe.initCause(ex.getCause());
      throw ioe;
    }
  }
  
  void joinAll() {
    for (BPOfferService bpos: this.getAllNamenodeThreads()) {
      bpos.join();
    }
  }
  
  void refreshNamenodes(Configuration conf)
      throws IOException {
    LOG.info("Refresh request received for nameservices: " +
        conf.get(DFSConfigKeys.DFS_NAMESERVICES));

    Map<String, Map<String, InetSocketAddress>> newAddressMap = null;
    Map<String, Map<String, InetSocketAddress>> newLifelineAddressMap = null;

    try {
      newAddressMap =
          DFSUtil.getNNServiceRpcAddressesForCluster(conf);
      newLifelineAddressMap =
          DFSUtil.getNNLifelineRpcAddressesForCluster(conf);
    } catch (IOException ioe) {
      LOG.warn("Unable to get NameNode addresses.", ioe);
    }

    if (newAddressMap == null || newAddressMap.isEmpty()) {
      throw new IOException("No services to connect, missing NameNode " +
          "address.");
    }

    synchronized (refreshNamenodesLock) {
      doRefreshNamenodes(newAddressMap, newLifelineAddressMap);
    }
  }
  
  private void doRefreshNamenodes(
      Map<String, Map<String, InetSocketAddress>> addrMap,
      Map<String, Map<String, InetSocketAddress>> lifelineAddrMap)
      throws IOException {
    assert Thread.holdsLock(refreshNamenodesLock);

    Set<String> toRefresh = Sets.newLinkedHashSet();
    Set<String> toAdd = Sets.newLinkedHashSet();
    Set<String> toRemove;
    
    synchronized (this) {
      // Step 1. For each of the new nameservices, figure out whether
      // it's an update of the set of NNs for an existing NS,
      // or an entirely new nameservice.
      for (String nameserviceId : addrMap.keySet()) {
        if (bpByNameserviceId.containsKey(nameserviceId)) {
          toRefresh.add(nameserviceId);
        } else {
          toAdd.add(nameserviceId);
        }
      }
      
      // Step 2. Any nameservices we currently have but are no longer present
      // need to be removed.
      toRemove = Sets.newHashSet(Sets.difference(
          bpByNameserviceId.keySet(), addrMap.keySet()));
      
      assert toRefresh.size() + toAdd.size() ==
        addrMap.size() :
          "toAdd: " + Joiner.on(",").useForNull("<default>").join(toAdd) +
          "  toRemove: " + Joiner.on(",").useForNull("<default>").join(toRemove) +
          "  toRefresh: " + Joiner.on(",").useForNull("<default>").join(toRefresh);

      
      // Step 3. Start new nameservices
      if (!toAdd.isEmpty()) {
        LOG.info("Starting BPOfferServices for nameservices: " +
            Joiner.on(",").useForNull("<default>").join(toAdd));

        // 在这里就是这个BPOfferService，BPOfferActor
        // nameservice，就是一组namenode，两个namenode组成的，就是一个active + 一个standby
        // 一组namenode，就是一个nameservice。
        // 所以说初始化BlockPoolManager的时候，他其实会根据你的配置文件中的nameservice的配置
        // 对每个nameservice（一组namenode）,会创建一个BPOfferService

        // 如果你仅仅用的是hadoop的普通HA架构，就只有一个nameservice
        // 也就只有一个BPOfferService
        for (String nsToAdd : toAdd) {
          Map<String, InetSocketAddress> nnIdToAddr = addrMap.get(nsToAdd);
          Map<String, InetSocketAddress> nnIdToLifelineAddr =
              lifelineAddrMap.get(nsToAdd);
          ArrayList<InetSocketAddress> addrs =
              Lists.newArrayListWithCapacity(nnIdToAddr.size());
          ArrayList<String> nnIds =
              Lists.newArrayListWithCapacity(nnIdToAddr.size());
          ArrayList<InetSocketAddress> lifelineAddrs =
              Lists.newArrayListWithCapacity(nnIdToAddr.size());
          for (String nnId : nnIdToAddr.keySet()) {
            addrs.add(nnIdToAddr.get(nnId));
            nnIds.add(nnId);
            lifelineAddrs.add(nnIdToLifelineAddr != null ?
                nnIdToLifelineAddr.get(nnId) : null);
          }
          // 创建BPOfferService 核心代码
          BPOfferService bpos = createBPOS(nsToAdd, nnIds, addrs,
              lifelineAddrs);
          bpByNameserviceId.put(nsToAdd, bpos);
          offerServices.add(bpos);
        }
      }
      // startAll()就会去启动对应的BPServiceActor那些线程
      startAll();
    }

    // Step 4. Shut down old nameservices. This happens outside
    // of the synchronized(this) lock since they need to call
    // back to .remove() from another thread
    if (!toRemove.isEmpty()) {
      LOG.info("Stopping BPOfferServices for nameservices: " +
          Joiner.on(",").useForNull("<default>").join(toRemove));

      //如果有一些nameservice要删除掉，那么就要停止他对应的BPServiceActor线程
      for (String nsToRemove : toRemove) {
        BPOfferService bpos = bpByNameserviceId.get(nsToRemove);
        bpos.stop();
        bpos.join();
        // they will call remove on their own
      }
    }
    
    // Step 5. Update nameservices whose NN list has changed
    if (!toRefresh.isEmpty()) {
      // 如果某个nameservice他的namenode列表变化了，也是在这里来处理
      // 肯定的初始化新的BPServiceActor线程去跟新的namenode进行通信
      LOG.info("Refreshing list of NNs for nameservices: " +
          Joiner.on(",").useForNull("<default>").join(toRefresh));
      
      for (String nsToRefresh : toRefresh) {
        BPOfferService bpos = bpByNameserviceId.get(nsToRefresh);
        Map<String, InetSocketAddress> nnIdToAddr = addrMap.get(nsToRefresh);
        Map<String, InetSocketAddress> nnIdToLifelineAddr =
            lifelineAddrMap.get(nsToRefresh);
        ArrayList<InetSocketAddress> addrs =
            Lists.newArrayListWithCapacity(nnIdToAddr.size());
        ArrayList<InetSocketAddress> lifelineAddrs =
            Lists.newArrayListWithCapacity(nnIdToAddr.size());
        ArrayList<String> nnIds = Lists.newArrayListWithCapacity(
            nnIdToAddr.size());
        for (String nnId : nnIdToAddr.keySet()) {
          addrs.add(nnIdToAddr.get(nnId));
          lifelineAddrs.add(nnIdToLifelineAddr != null ?
              nnIdToLifelineAddr.get(nnId) : null);
          nnIds.add(nnId);
        }
        try {
          UserGroupInformation.getLoginUser()
              .doAs(new PrivilegedExceptionAction<Object>() {
                @Override
                public Object run() throws Exception {
                  bpos.refreshNNList(nsToRefresh, nnIds, addrs, lifelineAddrs);
                  return null;
                }
              });
        } catch (InterruptedException ex) {
          IOException ioe = new IOException();
          ioe.initCause(ex.getCause());
          throw ioe;
        }
      }
    }
  }

  /**
   * Extracted out for test purposes.
   */
  protected BPOfferService createBPOS(
      final String nameserviceId,
      List<String> nnIds,
      List<InetSocketAddress> nnAddrs,
      List<InetSocketAddress> lifelineNnAddrs) {
    return new BPOfferService(nameserviceId, nnIds, nnAddrs, lifelineNnAddrs,
        dn);
  }
}
