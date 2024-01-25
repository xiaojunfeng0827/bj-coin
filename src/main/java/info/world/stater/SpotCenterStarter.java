package info.world.stater;

import info.world.netty.NettyClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;

@Component
@Slf4j
public class SpotCenterStarter implements CommandLineRunner {

    @Override
    public void run(String... args) {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        try {
            URI uri = new URI("ws://43.198.233.51:8814/ws");
            NettyClient nettyClient = new NettyClient(uri, countDownLatch);
            nettyClient.connect();
            countDownLatch.await();
        } catch (URISyntaxException | InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

}
