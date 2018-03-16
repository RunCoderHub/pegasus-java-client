// Copyright (c) 2017, Xiaomi, Inc.  All rights reserved.
// This source code is licensed under the Apache License Version 2.0, which
// can be found in the LICENSE file in the root directory of this source tree.
package com.xiaomi.infra.pegasus.rpc.async;

import com.xiaomi.infra.pegasus.metrics.MetricsManager;
import com.xiaomi.infra.pegasus.rpc.Cluster;
import com.xiaomi.infra.pegasus.rpc.KeyHasher;
import com.xiaomi.infra.pegasus.rpc.ReplicationException;
import com.xiaomi.infra.pegasus.base.rpc_address;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by sunweijie@xiaomi.com on 16-11-11.
 */
public class ClusterManager extends Cluster {
    private int operationTimeout;
    private int retryDelay;
    private boolean enableCounter;

    private ConcurrentHashMap<rpc_address, ReplicaSession> replicaSessions;
    private EventLoopGroup metaGroup; // group used for handle meta logic
    private EventLoopGroup replicaGroup; // group used for handle io with replica servers
    private EventLoopGroup tableGroup; // group used for handle table logic
    private MetaSession metaSession;

    private static final Logger logger;
    private static final String osName;
    private static final String Linux = "Linux";
    static {
        logger = org.slf4j.LoggerFactory.getLogger(ClusterManager.class);
        Properties p = System.getProperties();
        osName = p.getProperty("os.name");
        logger.info("operating system name: {}", osName);
    }

    public ClusterManager(
            int timeout,
            int io_threads,
            boolean enableCounter,
            String perfCounterTags,
            int pushIntervalSecs,
            String[] address_list) throws IllegalArgumentException {
        setTimeout(timeout);
        this.enableCounter = enableCounter;
        if (enableCounter) {
            MetricsManager.detectHostAndInit(perfCounterTags, pushIntervalSecs);
        }

        replicaSessions = new ConcurrentHashMap<rpc_address, ReplicaSession>();
        replicaGroup = getEventLoopGroupInstance(io_threads);
        metaGroup = getEventLoopGroupInstance(1);
        tableGroup = getEventLoopGroupInstance(1);

        // the constructor of meta session is depend on the replicaSessions,
        // so the replicaSessions should be initialized earlier
        metaSession = new MetaSession(this, address_list, timeout, 10, metaGroup);
    }

    public EventExecutor getExecutor(String name, int threadCount) {
        return tableGroup.next();
    }

    public MetaSession getMetaSession() {
        return metaSession;
    }

    public ReplicaSession getReplicaSession(rpc_address address) {
        if (address.isInvalid()) {
            return null;
        }
        ReplicaSession ss = replicaSessions.get(address);
        if (ss != null)
            return ss;
        synchronized (this) {
            ss = replicaSessions.get(address);
            if (ss != null)
                return ss;
            ss = new ReplicaSession(address, replicaGroup, Cluster.SOCK_TIMEOUT);
            replicaSessions.put(address, ss);
            return ss;
        }
    }

    public int getTimeout() { return operationTimeout; }

    public long getRetryDelay(long timeoutMs) { return (timeoutMs < 3 ? 1: timeoutMs/3); }

    public int getRetryDelay() { return retryDelay; }

    public boolean counterEnabled() { return enableCounter; }

    public void setTimeout(int t) {
        operationTimeout = t;
        // set retry delay as t/3.
        retryDelay = (t < 3 ? 1 : t / 3);
    }

    public static EventLoopGroup getEventLoopGroupInstance(int threadsCount) {
        if (osName.equals(Linux)) {
            logger.debug("create epolled eventloop group");
            return new EpollEventLoopGroup(threadsCount);
        }
        logger.debug("create nio eventloop group");
        return new NioEventLoopGroup(threadsCount);
    }

    public static Class getSocketChannelClass() {
        if (osName.equals(Linux)) {
            logger.debug("create epolled Socket channel");
            return EpollSocketChannel.class;
        }
        logger.debug("create nio eventloop group");
        return NioSocketChannel.class;
    }

    @Override
    public TableHandler openTable(String name, KeyHasher h) throws ReplicationException {
        return new TableHandler(this, name, h);
    }

    @Override
    public void close() {
        if (enableCounter) {
            MetricsManager.finish();
        }

        metaSession.closeSession();
        for (Map.Entry<rpc_address, ReplicaSession> entry: replicaSessions.entrySet()) {
            entry.getValue().closeSession();
        }

        try {
            metaGroup.shutdownGracefully().sync();
            logger.info("close meta group");
        } catch (Exception ex) {
            logger.warn("close meta group failed: ", ex);
        }

        try {
            replicaGroup.shutdownGracefully().sync();
            logger.info("close replica group");
        } catch (Exception ex) {
            logger.warn("close replica group failed: ", ex);
        }

        try {
            tableGroup.shutdownGracefully();
            logger.info("close table group");
        } catch (Exception ex) {
            logger.warn("close table group failed: ", ex);
        }
    }
}
