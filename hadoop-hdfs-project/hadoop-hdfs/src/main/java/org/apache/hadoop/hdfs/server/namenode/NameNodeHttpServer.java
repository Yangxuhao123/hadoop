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
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_WEBHDFS_REST_CSRF_ENABLED_DEFAULT;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_WEBHDFS_REST_CSRF_ENABLED_KEY;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.HashMap;

import javax.servlet.ServletContext;

import org.apache.hadoop.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ha.HAServiceProtocol;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.server.aliasmap.InMemoryAliasMap;
import org.apache.hadoop.hdfs.server.common.JspHelper;
import org.apache.hadoop.hdfs.server.common.TokenVerifier;
import org.apache.hadoop.hdfs.server.namenode.startupprogress.StartupProgress;
import org.apache.hadoop.hdfs.server.namenode.web.resources.NamenodeWebHdfsMethods;
import org.apache.hadoop.hdfs.web.WebHdfsFileSystem;
import org.apache.hadoop.hdfs.web.resources.AclPermissionParam;
import org.apache.hadoop.hdfs.web.resources.Param;
import org.apache.hadoop.hdfs.web.resources.UserParam;
import org.apache.hadoop.http.HttpConfig;
import org.apache.hadoop.http.HttpServer2;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.http.RestCsrfPreventionFilter;

import com.sun.jersey.api.core.ResourceConfig;

/**
 * Encapsulates the HTTP server started by the NameNode. 
 */
@InterfaceAudience.Private
public class NameNodeHttpServer {
  private HttpServer2 httpServer;
  private final Configuration conf;
  private final NameNode nn;
  
  private InetSocketAddress httpAddress;
  private InetSocketAddress httpsAddress;
  private final InetSocketAddress bindAddress;
  
  public static final String NAMENODE_ADDRESS_ATTRIBUTE_KEY = "name.node.address";
  public static final String FSIMAGE_ATTRIBUTE_KEY = "name.system.image";
  protected static final String NAMENODE_ATTRIBUTE_KEY = "name.node";
  public static final String STARTUP_PROGRESS_ATTRIBUTE_KEY = "startup.progress";
  public static final String ALIASMAP_ATTRIBUTE_KEY = "name.system.aliasmap";

  NameNodeHttpServer(Configuration conf, NameNode nn,
      InetSocketAddress bindAddress) {
    this.conf = conf;
    this.nn = nn;
    this.bindAddress = bindAddress;
  }

  public static void initWebHdfs(Configuration conf, String hostname,
      String httpKeytab,
      HttpServer2 httpServer2, String jerseyResourcePackage)
      throws IOException {
    // set user pattern based on configuration file
    UserParam.setUserPattern(conf.get(
        HdfsClientConfigKeys.DFS_WEBHDFS_USER_PATTERN_KEY,
        HdfsClientConfigKeys.DFS_WEBHDFS_USER_PATTERN_DEFAULT));
    AclPermissionParam.setAclPermissionPattern(conf.get(
        HdfsClientConfigKeys.DFS_WEBHDFS_ACL_PERMISSION_PATTERN_KEY,
        HdfsClientConfigKeys.DFS_WEBHDFS_ACL_PERMISSION_PATTERN_DEFAULT));

    final String pathSpec = WebHdfsFileSystem.PATH_PREFIX + "/*";

    // add REST CSRF prevention filter
    if (conf.getBoolean(DFS_WEBHDFS_REST_CSRF_ENABLED_KEY,
        DFS_WEBHDFS_REST_CSRF_ENABLED_DEFAULT)) {
      Map<String, String> restCsrfParams = RestCsrfPreventionFilter
          .getFilterParams(conf, "dfs.webhdfs.rest-csrf.");
      String restCsrfClassName = RestCsrfPreventionFilter.class.getName();
      HttpServer2.defineFilter(httpServer2.getWebAppContext(),
          restCsrfClassName, restCsrfClassName, restCsrfParams,
          new String[] {pathSpec});
    }

    // add webhdfs packages
    final Map<String, String> params = new HashMap<>();
    params.put(ResourceConfig.FEATURE_MATCH_MATRIX_PARAMS, "true");
    httpServer2.addJerseyResourcePackage(
        jerseyResourcePackage + ";" + Param.class.getPackage().getName(),
        pathSpec, params);
  }

  /**
   * @see DFSUtil#getHttpPolicy(org.apache.hadoop.conf.Configuration)
   * for information related to the different configuration options and
   * Http Policy is decided.
   */
  void start() throws IOException {
    HttpConfig.Policy policy = DFSUtil.getHttpPolicy(conf);
    final String infoHost = bindAddress.getHostName();

    final InetSocketAddress httpAddr = bindAddress;
    final String httpsAddrString = conf.getTrimmed(
        DFSConfigKeys.DFS_NAMENODE_HTTPS_ADDRESS_KEY,
        DFSConfigKeys.DFS_NAMENODE_HTTPS_ADDRESS_DEFAULT);
    //bindAddress，就是http server绑定在哪个端口上
    //明显是自己可以配置，也是有自己的默认值，默认的http端口就是50070
    //每次启动集群之后，就会访问他的50070端口

    //NameNodeHttpServer 绑定了50070端口号 接受http请求
    //他提供的最主要的一块 就是平时访问的hdfs工作台
    //在这个端口上可以查看集群的状态、里面的元数据、目录结构、block信息
    InetSocketAddress httpsAddr = NetUtils.createSocketAddr(httpsAddrString);

    if (httpsAddr != null) {
      // If DFS_NAMENODE_HTTPS_BIND_HOST_KEY exists then it overrides the
      // host name portion of DFS_NAMENODE_HTTPS_ADDRESS_KEY.
      final String bindHost =
          conf.getTrimmed(DFSConfigKeys.DFS_NAMENODE_HTTPS_BIND_HOST_KEY);
      if (bindHost != null && !bindHost.isEmpty()) {
        httpsAddr = new InetSocketAddress(bindHost, httpsAddr.getPort());
      }
    }

    // 这是hadoop自己实现的一套http服务
    // hadoop http server这块，不是hdfs里的，是在hadoop-common里的，是整个hadoop通用的
    // 其实就是实现了一套可以接收http请求的一套机制
    HttpServer2.Builder builder = DFSUtil.httpServerTemplateForNNAndJN(conf,
        httpAddr, httpsAddr, "hdfs",
        DFSConfigKeys.DFS_NAMENODE_KERBEROS_INTERNAL_SPNEGO_PRINCIPAL_KEY,
        DFSConfigKeys.DFS_NAMENODE_KEYTAB_FILE_KEY);

    final boolean xFrameEnabled = conf.getBoolean(
        DFSConfigKeys.DFS_XFRAME_OPTION_ENABLED,
        DFSConfigKeys.DFS_XFRAME_OPTION_ENABLED_DEFAULT);

    final String xFrameOptionValue = conf.getTrimmed(
        DFSConfigKeys.DFS_XFRAME_OPTION_VALUE,
        DFSConfigKeys.DFS_XFRAME_OPTION_VALUE_DEFAULT);

    builder.configureXFrame(xFrameEnabled).setXFrameOption(xFrameOptionValue);

    httpServer = builder.build();

    if (policy.isHttpsEnabled()) {
      // assume same ssl port for all datanodes
      InetSocketAddress datanodeSslPort = NetUtils.createSocketAddr(conf.getTrimmed(
          DFSConfigKeys.DFS_DATANODE_HTTPS_ADDRESS_KEY, infoHost + ":"
              + DFSConfigKeys.DFS_DATANODE_HTTPS_DEFAULT_PORT));
      httpServer.setAttribute(DFSConfigKeys.DFS_DATANODE_HTTPS_PORT_KEY,
          datanodeSslPort.getPort());
    }
    String httpKeytab = conf.get(DFSUtil.getSpnegoKeytabKey(conf,
        DFSConfigKeys.DFS_NAMENODE_KEYTAB_FILE_KEY));
    initWebHdfs(conf, bindAddress.getHostName(), httpKeytab, httpServer,
        NamenodeWebHdfsMethods.class.getPackage().getName());

    httpServer.setAttribute(NAMENODE_ATTRIBUTE_KEY, nn);
    httpServer.setAttribute(JspHelper.CURRENT_CONF, conf);
    // 这块代码是比较关键的
    // 就是在httpserver2里面绑定了一些servlet
    // 这些servlet相当于就是定义好了，自己可以接收哪些http请求，接收到了这些请求之后，由谁来处理
    setupServlets(httpServer);
    // 完事了以后，启动httpserver2
    httpServer.start();

    int connIdx = 0;
    if (policy.isHttpEnabled()) {
      httpAddress = httpServer.getConnectorAddress(connIdx++);
      conf.set(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY,
          NetUtils.getHostPortString(httpAddress));
    }

    if (policy.isHttpsEnabled()) {
      httpsAddress = httpServer.getConnectorAddress(connIdx);
      conf.set(DFSConfigKeys.DFS_NAMENODE_HTTPS_ADDRESS_KEY,
          NetUtils.getHostPortString(httpsAddress));
    }
  }

  /**
   * Joins the httpserver.
   */
  public void join() throws InterruptedException {
    if (httpServer != null) {
      httpServer.join();
    }
  }

  void stop() throws Exception {
    if (httpServer != null) {
      httpServer.stop();
    }
  }

  InetSocketAddress getHttpAddress() {
    return httpAddress;
  }

  InetSocketAddress getHttpsAddress() {
    return httpsAddress;
  }

  /**
   * Sets fsimage for use by servlets.
   * 
   * @param fsImage FSImage to set
   */
  void setFSImage(FSImage fsImage) {
    httpServer.setAttribute(FSIMAGE_ATTRIBUTE_KEY, fsImage);
  }

  /**
   * Sets address of namenode for use by servlets.
   * 
   * @param nameNodeAddress InetSocketAddress to set
   */
  void setNameNodeAddress(InetSocketAddress nameNodeAddress) {
    httpServer.setAttribute(NAMENODE_ADDRESS_ATTRIBUTE_KEY,
        NetUtils.getConnectAddress(nameNodeAddress));
  }

  /**
   * Sets startup progress of namenode for use by servlets.
   * 
   * @param prog StartupProgress to set
   */
  void setStartupProgress(StartupProgress prog) {
    httpServer.setAttribute(STARTUP_PROGRESS_ATTRIBUTE_KEY, prog);
  }

  /**
   * Sets the aliasmap URI.
   *
   * @param aliasMap the alias map used.
   */
  void setAliasMap(InMemoryAliasMap aliasMap) {
    httpServer.setAttribute(ALIASMAP_ATTRIBUTE_KEY, aliasMap);
  }

  private static void setupServlets(HttpServer2 httpServer) {
    //HttpServer2是一种通用的http服务的技术
    //可以针对你指定的这个端口号进行监听
    //如果有人给这个端口发送请求，会被HttpServer2给拦截，他会做统一的请求转发
    //比如请求了一个http://192.168.31.19:50070/listDirs?dir=/user/warehouse
    //此时HttpServer2就需要将这个/listDIrs这个接口转发给对应的Servlet，哪个servlet在监听这个/listDirs的请求
    //同时将dir=/user/warehouse的参数转发给那个servlet
    //由那个servlet来进行处理，然后将结果返回给浏览器
    httpServer.addInternalServlet("startupProgress",
        StartupProgressServlet.PATH_SPEC, StartupProgressServlet.class);
    httpServer.addInternalServlet("fsck", "/fsck", FsckServlet.class,
        true);
    httpServer.addInternalServlet("imagetransfer", ImageServlet.PATH_SPEC,
        ImageServlet.class, true);
    httpServer.addInternalServlet(IsNameNodeActiveServlet.SERVLET_NAME,
        IsNameNodeActiveServlet.PATH_SPEC,
        IsNameNodeActiveServlet.class);
    //activenamnode提供了一个image transfer的接口
    //专门用来接收standby namenode执行完checkpoint之后发送过来的fsimage文件
    httpServer.addInternalServlet(NetworkTopologyServlet.SERVLET_NAME,
        NetworkTopologyServlet.PATH_SPEC, NetworkTopologyServlet.class);
  }

  static FSImage getFsImageFromContext(ServletContext context) {
    return (FSImage)context.getAttribute(FSIMAGE_ATTRIBUTE_KEY);
  }

  public static NameNode getNameNodeFromContext(ServletContext context) {
    return (NameNode)context.getAttribute(NAMENODE_ATTRIBUTE_KEY);
  }

  public static TokenVerifier
      getTokenVerifierFromContext(ServletContext context) {
    return (TokenVerifier) context.getAttribute(NAMENODE_ATTRIBUTE_KEY);
  }

  static Configuration getConfFromContext(ServletContext context) {
    return (Configuration)context.getAttribute(JspHelper.CURRENT_CONF);
  }

  static InMemoryAliasMap getAliasMapFromContext(ServletContext context) {
    return (InMemoryAliasMap) context.getAttribute(ALIASMAP_ATTRIBUTE_KEY);
  }

  public static InetSocketAddress getNameNodeAddressFromContext(
      ServletContext context) {
    return (InetSocketAddress)context.getAttribute(
        NAMENODE_ADDRESS_ATTRIBUTE_KEY);
  }

  /**
   * Returns StartupProgress associated with ServletContext.
   * 
   * @param context ServletContext to get
   * @return StartupProgress associated with context
   */
  static StartupProgress getStartupProgressFromContext(
      ServletContext context) {
    return (StartupProgress)context.getAttribute(STARTUP_PROGRESS_ATTRIBUTE_KEY);
  }

  public static HAServiceProtocol.HAServiceState getNameNodeStateFromContext(ServletContext context) {
    return getNameNodeFromContext(context).getServiceState();
  }

  /**
   * Returns the httpServer.
   * @return HttpServer2
   */
  @VisibleForTesting
  public HttpServer2 getHttpServer() {
    return httpServer;
  }
}
