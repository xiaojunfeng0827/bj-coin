package info.world.netty;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import info.world.config.SpringContextHolder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class NettyClient extends SimpleChannelInboundHandler<Object> {

    private SslContext sslCtx;
    private CountDownLatch latch;
    private URI uri;
    private String host;
    private int port;
    private String scheme;
    private NettyClient nettyClient;
    private NettyClientInitializer nettyClientInitializer;
    private Bootstrap bootstrap;
    private Channel channel;
    int repeatConnectCount = 0;
    private WebSocketClientHandshaker handshaker;
    private ChannelPromise handshakeFuture;
    private NettyDataHandler nettyDataHandler = SpringContextHolder.getBean(NettyDataHandler.class);
    /**
     * 是否需要触发重连  0:否  1:是（默认1）
     */
    private Integer isConnected = 1;

    public NettyClient(CountDownLatch latch, NettyClient nettyClient) {
        this.latch = latch;
        this.nettyClient = nettyClient;
    }

    public NettyClient(URI uri, CountDownLatch latch) {
        this.uri = uri;
        this.host = uri.getHost();
        this.port = uri.getPort();
        this.scheme = uri.getScheme();
        this.latch = latch;
        if ("wss".equals(scheme)) {
            //初始化SslContext，这个在wss协议升级的时候需要用到
            try {
                this.sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            } catch (SSLException e) {
                log.error(e.getMessage());
            }
        } else if ("ws".equals(scheme)) {
            this.sslCtx = null;
        }
        this.nettyClientInitializer = new NettyClientInitializer(latch, host, port, sslCtx, NettyClient.this);
    }

    public void connect() {
        EventLoopGroup group = new NioEventLoopGroup(4);
        try {
            bootstrap = new Bootstrap();
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .group(group)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .channel(NioSocketChannel.class)
                    .handler(nettyClientInitializer);
            doConnect(null, 20);
            //睡两秒的是为了让管道初始化成功，再做具体的数据订阅操作
        } catch (Exception e) {
            log.error("netty websocket connect fail~!!!,{} ", e.getMessage());
        }
    }

    /**
     * 关闭netty连接连接
     *
     * @param isRestart 0:不触发重连   1:触发重连
     */
    public void close(Integer isRestart) {
        try {
            isConnected = isRestart;
            channel.close().sync();
        } catch (Exception e) {
            isConnected = 1;
            log.error("isConnected={},close failed={}", isRestart, e.getMessage());
        }
    }

    /**
     * 这个方法netty执行的时候自己会去调
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.handshakeFuture = ctx.newPromise();
    }

    /**
     * 通道关闭将会触发此方法,并且进行重连
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.error("channelInactive,channel={},status={},isReconnected={}", ctx.channel(), ctx.channel().isActive(), nettyClient.getIsConnected());
        ctx.close();
        if (1 == nettyClient.getIsConnected() || null == nettyClient.isConnected) {
            nettyClient.doConnect(ctx, 20);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.WRITER_IDLE) {
                ctx.channel().writeAndFlush(new TextWebSocketFrame("ping"));
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel channel1 = ctx.channel();
        channel1.close();
        log.error("异常管道={},异常原因={}", channel1, cause.toString());
    }

    @SneakyThrows
    protected void doConnect(ChannelHandlerContext ctx, Integer count) {
        if (channel != null && channel.isActive()) {
            log.info("通道正常");
            return;
        }
        //重连次数每次加一
        repeatConnectCount++;
        if (repeatConnectCount > count) {
            if (null != ctx) {
                log.error("通道关闭、重连失败");
                ctx.channel().close();
                return;
            }
        }
        //建立HTTP连接
        ChannelFuture future = bootstrap.connect(host, port).addListener((ChannelFutureListener) futureListener -> {
            if (futureListener.isSuccess()) {
                channel = futureListener.channel();
                repeatConnectCount = 0;
                HttpHeaders httpHeaders = new DefaultHttpHeaders();
                WebSocketClientHandshaker webSocketClientHandshaker = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, true, httpHeaders, 65536 * 5);
                NettyClient handler = (NettyClient) channel.pipeline().get("websocketHandler");
                webSocketClientHandshaker.handshake(channel);
                handler.setHandshaker(webSocketClientHandshaker);
                log.info("websocket connect success!!!channel={}", channel);
            } else {
                futureListener.channel().eventLoop().schedule(() -> {
                    log.error("重试开启通道~" + repeatConnectCount);
                    doConnect(ctx, count);
                }, 3, TimeUnit.SECONDS);
            }
        });
        String s = "{\n" +
                "\"method\": \"SUBSCRIBE\",\n" +
                "\"params\":\n" +
                "[\n" +
                "\"btcusdt@aggTrade\"" +
                "],\n" +
                "\"id\": 1\n" +
                "}";

        future.channel().writeAndFlush(new TextWebSocketFrame(s));
        channel = future.channel();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object o) {
        Channel ch = ctx.channel();
        if (!ch.isActive()) {
            ch.close();
            return;
        }
        FullHttpResponse response;
        if (!this.handshaker.isHandshakeComplete()) {
            try {
                response = (FullHttpResponse) o;
                this.handshaker.finishHandshake(ch, response);
                this.handshakeFuture.setSuccess();
            } catch (Exception e) {
                log.error(e.getMessage());
            } finally {
                latch.countDown();
                String s = "{\n" +
                        "\"method\": \"SUBSCRIBE\",\n" +
                        "\"params\":\n" +
                        "[\n" +
                        "\"btcusdt@aggTrade\",\n" +
                        "\"btcusdt@depth\"\n" +
                        "],\n" +
                        "\"id\": 1\n" +
                        "}";
                ctx.channel().writeAndFlush(new TextWebSocketFrame(s));
            }
        } else if (o instanceof FullHttpResponse) {
            response = (FullHttpResponse) o;
            throw new IllegalStateException("Unexpected FullHttpResponse (getStatus=" + response.status() + ", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');
        } else {
            WebSocketFrame frame = (WebSocketFrame) o;
            if (frame instanceof BinaryWebSocketFrame) {
                BinaryWebSocketFrame webSocketFrame = (BinaryWebSocketFrame) frame;
                String result = webSocketFrame.content().toString(CharsetUtil.UTF_8);
                log.error("BinaryWebSocketFrame={}", result);
            }
            if (frame instanceof TextWebSocketFrame) {
                String content = ((TextWebSocketFrame) o).text();
                if (!"pong".equals(content)) {
                    JSONObject jsonObject = JSONObject.parseObject(content);
                    JSONArray jsonData = jsonObject.getJSONArray("data");
                    if (jsonData != null && !jsonData.isEmpty()) {
                        JSONObject argData = jsonObject.getJSONObject("arg");
                        String messageType;
                        if (argData != null) {
                            String channel = "channel";
                            if (argData.containsKey(channel)) {
                                messageType = argData.getString(channel);
                                if (messageType == null || messageType.isEmpty()) {
                                    return;
                                }
                            } else {
                                return;
                            }
                            Object instId = argData.get("instId");
                            if (instId != null) {
                                String symbol = argData.getString("instId").replaceAll("-", "/");
                                nettyDataHandler.onMessage(content, messageType, symbol);
                            }
                        }
                    }
                }
            }
        }
    }
}
