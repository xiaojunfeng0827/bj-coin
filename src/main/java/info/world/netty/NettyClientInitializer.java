package info.world.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.CountDownLatch;

public class NettyClientInitializer extends ChannelInitializer<SocketChannel> {

    private final CountDownLatch latch;
    private final String host;
    private final int port;
    private final SslContext sslCtx;
    private final NettyClient nettyClient;

    public NettyClientInitializer(CountDownLatch latch, String host, int port, SslContext sslCtx, NettyClient nettyClient) {
        this.latch = latch;
        this.host = host;
        this.port = port;
        this.sslCtx = sslCtx;
        this.nettyClient = nettyClient;
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) {
        ChannelPipeline pipeline = socketChannel.pipeline();
        //添加wss协议支持
        if (null != sslCtx) {
            pipeline.addLast(sslCtx.newHandler(socketChannel.alloc(), host, port));
        }
        SimpleChannelInboundHandler<Object> handler = new NettyClient(latch, nettyClient);
        //添加心跳支持,超过5秒没有 ping 服务器就会触发 READER_IDLE 事件，进行ping服务器操作
        pipeline.addLast(new IdleStateHandler(0, 5, 0));
        pipeline.addLast(new HttpClientCodec(), new HttpObjectAggregator(65535));
        pipeline.addLast("websocketHandler", handler);
    }

}
