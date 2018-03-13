// Copyright (c) 2017, Xiaomi, Inc.  All rights reserved.
// This source code is licensed under the Apache License Version 2.0, which
// can be found in the LICENSE file in the root directory of this source tree.
package com.xiaomi.infra.pegasus.metrics;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.json.JSONException;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Created by weijiesun on 18-3-9.
 */
public class MetricsReporter {
    public MetricsReporter(int reportSecs, MetricsPool pool) {
        falconAgentIP = "127.0.0.1";
        falconAgentPort = 1988;
        falconAgentSocket = falconAgentIP + String.valueOf(falconAgentPort);

        reportIntervalSecs = reportSecs;
        falconRequestPath = "/v1/push";
        metrics = pool;

        boot = new Bootstrap();
        httpClientGroup = new NioEventLoopGroup(1);

        boot.group(httpClientGroup).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpContentDecompressor());
                        p.addLast(new HttpClientHandler());
                    }
                });
    }

    public void start() {
        tryConnect();
    }

    public void stop() {
        httpClientGroup.shutdownGracefully();
    }

    public void tryConnect() {
        boot.connect(falconAgentIP, falconAgentPort).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (channelFuture.isSuccess()) {
                    logger.info("create channel with {} succeed, wait it active", falconAgentSocket);
                } else {
                    logger.error("create channel with {} failed, connect later: ",
                            falconAgentSocket,
                            channelFuture.cause());
                    scheduleNextConnect();
                }
            }
        });
    }

    public void scheduleNextConnect() {
        httpClientGroup.schedule(new Runnable() {
            @Override
            public void run() {
                tryConnect();
            }
        }, (long) reportIntervalSecs, TimeUnit.SECONDS);
    }

    public void scheduleNextReport(final Channel channel) {
        httpClientGroup.schedule(new Runnable() {
            @Override
            public void run() {
                reportMetrics(channel);
            }
        }, reportIntervalSecs, TimeUnit.SECONDS);
    }

    public void reportMetrics(final Channel channel) {
        String json_metrics;
        try {
            json_metrics = metrics.metricsToJson();
        } catch (JSONException ex) {
            logger.warn("encode metrics to json failed, skip current report, retry later: ", ex);
            scheduleNextReport(channel);
            return;
        }

        logger.debug("generate metrics {} and try to report", json_metrics);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                falconRequestPath,
                Unpooled.copiedBuffer(json_metrics.getBytes()));
        request.headers().add(HttpHeaders.Names.HOST, falconAgentSocket);
        request.headers().add(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        request.headers().add(HttpHeaders.Names.CONTENT_LENGTH, request.content().readableBytes());
        request.headers().add(HttpHeaders.Names.CONTENT_TYPE, "application/x-www-form-urlencoded");

        channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (!channelFuture.isSuccess()) {
                    logger.warn("report to {} failed, skip current report, retry later: ",
                            channel.toString(),
                            channelFuture.cause());
                    channel.close();
                }
            }
        });
    }

    class HttpClientHandler extends SimpleChannelInboundHandler<HttpObject> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject httpObject) throws Exception {
            if (httpObject instanceof HttpResponse) {
                HttpResponse resp = (HttpResponse) httpObject;
                if (! resp.getStatus().equals(HttpResponseStatus.OK)) {
                    logger.error("http response status: {}", resp.getStatus().toString());
                }
                logger.debug("http response status {}: CONTENT:[", resp.getStatus().toString());
            }
            if (httpObject instanceof HttpContent) {
                HttpContent content = (HttpContent) httpObject;
                logger.debug(content.content().toString(CharsetUtil.UTF_8));

                if (content instanceof LastHttpContent) {
                    logger.debug("], response finished, schedule next report");
                    scheduleNextReport(ctx.channel());
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("{} exception got: ", ctx.channel().toString(), cause);
            ctx.close();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.info("channel {} is active", ctx.channel().toString());
            reportMetrics(ctx.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            logger.info("channel {} is inactive", ctx.channel().toString());
            scheduleNextConnect();
        }
    }

    private String falconAgentIP;
    private int falconAgentPort;
    private String falconAgentSocket; // IP:port;
    private int reportIntervalSecs;
    private String falconRequestPath;

    private MetricsPool metrics;

    private Bootstrap boot;
    private EventLoopGroup httpClientGroup;

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(MetricsReporter.class);
}