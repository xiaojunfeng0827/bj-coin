package info.world.netty;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Data
@Component
@Slf4j
public class NettyDataHandler {

    /**
     * 订阅K线频道 - 历史K线 - K线历史数据类型
     */
    private static final HashMap<String, String> PERIOD = new HashMap<>();

    /**
     * 今日开仓时间
     */
    private Map<String, Long> todayTimeMap = new HashMap<>();

    /**
     * 今日开仓价
     */
    private Map<String, BigDecimal> todayOpenMap = new HashMap<>();

    /**
     * 是否失效
     */
    public boolean invalid = false;

    /**
     * 订阅行情频道 - 频道标识
     */
    private static final String TICKERS = "tickers";

    /**
     * 订阅交易频道 - 频道标识
     */
    private static final String TRADES = "trades";

    /**
     * 订阅深度频道 - 频道标识
     */
    private static final String BOOKS = "books";

    /**
     * 所有交易对当前实时价格
     */
    private static Map<String, BigDecimal> symbolCurrentPrice = new HashMap<>();

    public NettyDataHandler() {
        PERIOD.put("candle1D", "1day");
    }

    public void onMessage(String message, String type, String symbol) {
        log.info("symbol={},type={},message={}", symbol, type, message);
    }

}
