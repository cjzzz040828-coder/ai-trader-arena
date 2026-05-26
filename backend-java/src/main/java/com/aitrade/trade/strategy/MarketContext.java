package com.aitrade.trade.strategy;

import com.aitrade.gateway.PythonGatewayClient;
import com.aitrade.gateway.dto.SnapshotResponse;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次策略调度 tick 内的市场视图。多个 trader 共用同一个实例，避免重复请求网关。
 *
 * - 构造时立即拉一次 watchlist + 批量 snapshot（用于价格判断 + marketStatus）
 * - bars 数据按需 lazy 拉取并缓存（MA 策略只会用 watchlist 子集）
 * - 任一调用失败抛 RuntimeException，由调度器统一处理
 */
@Slf4j
public class MarketContext {

    private final PythonGatewayClient gateway;

    protected final List<String> watchlistCodes;
    protected final Map<String, Map<String, Object>> snapshotByCode;
    private final boolean marketOpen;
    private final boolean actualMarketOpen;
    private final boolean premarket;
    private final String marketStatus;
    protected final Map<String, List<Map<String, Object>>> barsCache = new ConcurrentHashMap<>();

    public MarketContext(PythonGatewayClient gateway) {
        this(gateway, false, null);
    }

    public MarketContext(PythonGatewayClient gateway, boolean forceOpen) {
        this(gateway, forceOpen, null);
    }

    /** 子类直接喂数据用（回测）。gateway=null 时父类 bars() 不可调用，子类必须 override。 */
    protected MarketContext(List<String> codes,
                            Map<String, Map<String, Object>> snapshots,
                            boolean marketOpen) {
        this.gateway = null;
        this.watchlistCodes = Collections.unmodifiableList(codes);
        this.snapshotByCode = snapshots;
        this.actualMarketOpen = marketOpen;
        this.marketOpen = marketOpen;
        this.premarket = false;
        this.marketStatus = marketOpen ? "OPEN" : "CLOSED";
    }

    /**
     * @param poolName 指定从哪个 stock pool 拉 watchlist；null/blank 走默认 watchlist。
     */
    @SuppressWarnings("unchecked")
    public MarketContext(PythonGatewayClient gateway, boolean forceOpen, String poolName) {
        this.gateway = gateway;

        List<String> codes = new ArrayList<>();
        try {
            Map<String, Object> wl = (poolName == null || poolName.isBlank())
                    ? gateway.watchlist()
                    : gateway.watchlist(poolName);
            Object data = wl == null ? null : wl.get("data");
            if (data instanceof List<?> arr) {
                for (Object item : arr) {
                    if (item instanceof Map<?, ?> row) {
                        Object code = row.get("code");
                        if (code != null) codes.add(String.valueOf(code));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[strategy-ctx] watchlist(pool={}) failed: {}", poolName, e.getMessage());
        }
        this.watchlistCodes = Collections.unmodifiableList(codes);

        Map<String, Map<String, Object>> snapshots = new HashMap<>();
        boolean realOpen = false;
        boolean isPremarket = false;
        String status = "UNKNOWN";
        if (!codes.isEmpty()) {
            try {
                SnapshotResponse snap = gateway.snapshot(String.join(",", codes));
                if (snap != null) {
                    status = snap.getMarketStatus() == null ? "UNKNOWN" : snap.getMarketStatus().toUpperCase();
                    realOpen = "OPEN".equals(status);
                    isPremarket = "PREMARKET".equals(status);
                    if (snap.getData() != null) {
                        for (Map<String, Object> row : snap.getData()) {
                            Object code = row.get("code");
                            if (code != null) snapshots.put(String.valueOf(code), row);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[strategy-ctx] snapshot failed: {}", e.getMessage());
            }
        }
        this.snapshotByCode = snapshots;
        this.actualMarketOpen = realOpen;
        // 虚拟撮合系统：PREMARKET 也允许策略决策与挂 PENDING 单，开盘后由 MatchEngine 撮合。
        this.premarket = isPremarket;
        this.marketStatus = status;
        this.marketOpen = forceOpen || realOpen || isPremarket;
        if (forceOpen && !realOpen) {
            log.info("[strategy-ctx] forceOpen=true, actual market is CLOSED — strategy will run with last fallback snapshot");
        }
    }

    public boolean isMarketOpen() { return marketOpen; }
    public boolean isActualMarketOpen() { return actualMarketOpen; }
    public boolean isPremarket() { return premarket; }
    public String marketStatus() { return marketStatus; }

    public List<String> watchlist() { return watchlistCodes; }

    public Map<String, Map<String, Object>> snapshots() { return snapshotByCode; }

    public BigDecimal priceOf(String code) {
        Map<String, Object> row = snapshotByCode.get(code);
        if (row == null) return null;
        Object p = row.get("price");
        if (p == null) return null;
        try {
            return new BigDecimal(String.valueOf(p));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String nameOf(String code) {
        Map<String, Object> row = snapshotByCode.get(code);
        if (row == null) return null;
        Object n = row.get("name");
        return n == null ? null : String.valueOf(n);
    }

    /** 按需拉 K 线，本 tick 内缓存。frequency=9 日K，count=60。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> bars(String code, int frequency, int count) {
        String key = code + "/" + frequency + "/" + count;
        List<Map<String, Object>> cached = barsCache.get(key);
        if (cached != null) return cached;
        try {
            Map<String, Object> resp = gateway.bars(code, frequency, count);
            Object data = resp == null ? null : resp.get("data");
            List<Map<String, Object>> bars = new ArrayList<>();
            if (data instanceof List<?> arr) {
                for (Object item : arr) {
                    if (item instanceof Map<?, ?> row) {
                        bars.add((Map<String, Object>) row);
                    }
                }
            }
            barsCache.put(key, bars);
            return bars;
        } catch (Exception e) {
            log.warn("[strategy-ctx] bars {} failed: {}", code, e.getMessage());
            barsCache.put(key, Collections.emptyList());
            return Collections.emptyList();
        }
    }
}
