package com.aitrade.backtest;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.gateway.PythonGatewayClient;
import com.aitrade.gateway.dto.SnapshotResponse;
import com.aitrade.trade.strategy.MovingAverageExecutor;
import com.aitrade.trade.strategy.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.IntConsumer;

/**
 * 回测主引擎。流程：
 *   1. 拉 watchlist + 当前 snapshot（取股票名）
 *   2. 对每只 code 一次性拉 BAR_COUNT 根日 K（受 gateway 接口限制，只能拉最近 N 根）
 *   3. 用全量 K 线交集推出回测窗口内的交易日序列
 *   4. 主循环：
 *      T 日 close 决策 → enqueue 信号
 *      记 T 日 close 净值
 *      切到 T+1：用 T+1 open 撮合所有 pending
 *   5. 返回净值曲线 / 成交 / 收益率 / 最大回撤
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestEngine {

    private static final int BAR_COUNT = 300;
    private static final BigDecimal BUY_FRACTION = new BigDecimal("0.10");

    private final PythonGatewayClient gateway;
    private final MovingAverageExecutor maExecutor;

    public BacktestResult run(AiTrader trader,
                              LocalDate startDate,
                              LocalDate endDate,
                              BigDecimal initialBalance,
                              IntConsumer progressCallback) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate 必须不晚于 endDate");
        }

        // 1. 拉 watchlist + snapshot 取股票名
        List<String> codes = fetchWatchlist();
        if (codes.isEmpty()) {
            throw new IllegalStateException("watchlist 为空");
        }
        Map<String, String> namesByCode = fetchNames(codes);

        // 2. 预拉每只股票最近 BAR_COUNT 根日 K
        Map<String, List<Map<String, Object>>> fullBars = new HashMap<>();
        for (String code : codes) {
            List<Map<String, Object>> bars = fetchBars(code);
            if (!bars.isEmpty()) fullBars.put(code, bars);
        }
        if (fullBars.isEmpty()) {
            throw new IllegalStateException("所有股票的历史 K 线均无数据");
        }

        // 3. 算交易日序列：所有股票出现过的日期并集，再筛 [startDate, endDate]
        List<LocalDate> tradingDays = computeTradingDays(fullBars, startDate, endDate);
        if (tradingDays.size() < 2) {
            throw new IllegalStateException("回测窗口内交易日不足（need ≥ 2）：" + tradingDays.size());
        }
        log.info("[backtest] trader {} window {}~{} 交易日 {} 天, watchlist {} 只",
                trader.getId(), startDate, endDate, tradingDays.size(), codes.size());

        // 4. 主循环
        BacktestSandbox sandbox = new BacktestSandbox(initialBalance);
        List<EquityPoint> equityCurve = new ArrayList<>();
        List<EngineTrade> trades = new ArrayList<>();

        for (int i = 0; i < tradingDays.size(); i++) {
            LocalDate today = tradingDays.get(i);
            BacktestContext ctx = new BacktestContext(today, codes, fullBars, namesByCode);

            // A. 决策（用 today close 当作 MA 的"今日临时收盘价"）
            Map<String, Position> positions = sandboxPositionsToEntityMap(sandbox);
            List<Signal> signals;
            try {
                signals = maExecutor.decideWith(trader, ctx, positions);
            } catch (Exception e) {
                log.warn("[backtest] decide failed at {}: {}", today, e.getMessage());
                signals = List.of();
            }

            // B. 落单到 sandbox（仿 Orchestrator.placeSignal）
            for (Signal s : signals) {
                placeSignal(trader, ctx, s, sandbox);
            }

            // C. 记 today close 净值
            Map<String, BigDecimal> closeByCode = collectPricesAt(today, fullBars, codes, true);
            BigDecimal equity = sandbox.equity(closeByCode);
            equityCurve.add(new EquityPoint(today.toString(), equity));

            // D. 切到下一交易日：撮合 pending
            if (i + 1 < tradingDays.size()) {
                LocalDate nextDay = tradingDays.get(i + 1);
                sandbox.clearTodayBuys();
                Map<String, BigDecimal> openByCode = collectPricesAt(nextDay, fullBars, codes, false);
                List<BacktestSandbox.Fill> fills = sandbox.settleAtOpen(openByCode);
                for (BacktestSandbox.Fill f : fills) {
                    trades.add(new EngineTrade(
                            nextDay.toString(), f.code(), f.side(),
                            f.amount(), f.price(), f.balanceAfter(),
                            findReason(signals, f.code(), f.side())));
                }
            }

            if (progressCallback != null) {
                int prog = Math.min(99, (i + 1) * 100 / tradingDays.size());
                if (i == tradingDays.size() - 1) prog = 100;
                progressCallback.accept(prog);
            }
        }

        return summarize(initialBalance, equityCurve, trades);
    }

    // ---------------- 主循环辅助 ----------------

    private void placeSignal(AiTrader trader, BacktestContext ctx, Signal s, BacktestSandbox sandbox) {
        BigDecimal price = ctx.priceOf(s.stockCode());
        if (price == null || price.signum() <= 0) return;
        if (sandbox.hasPending(s.stockCode())) return;

        if ("BUY".equals(s.side())) {
            BigDecimal budget = sandbox.getBalance().multiply(BUY_FRACTION);
            BigDecimal sharesRaw = budget.divide(price, 6, RoundingMode.DOWN);
            int hundreds = sharesRaw.divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).intValue();
            if (hundreds <= 0) return;
            sandbox.enqueueBuy(s.stockCode(), hundreds * 100, price);
        } else if ("SELL".equals(s.side())) {
            if (sandbox.boughtToday(s.stockCode())) return;
            BacktestSandbox.Pos pos = sandbox.position(s.stockCode());
            if (pos == null || pos.amount < 100) return;
            int amt = (pos.amount / 100) * 100;
            sandbox.enqueueSell(s.stockCode(), amt, price);
        }
    }

    /** sandbox 持仓转 Position 实体 map（喂给 MovingAverageExecutor.decideWith）。 */
    private Map<String, Position> sandboxPositionsToEntityMap(BacktestSandbox sandbox) {
        Map<String, Position> out = new HashMap<>();
        for (Map.Entry<String, BacktestSandbox.Pos> e : sandbox.positions().entrySet()) {
            Position p = new Position();
            p.setStockCode(e.getKey());
            p.setAmount(e.getValue().amount);
            p.setFrozenAmount(0);
            p.setCostPrice(e.getValue().costPrice);
            out.put(e.getKey(), p);
        }
        return out;
    }

    /** 找 fills 对应信号的 reason（信号 -> 同 code+side 第一条匹配的 reason）。找不到返回空串。 */
    private String findReason(List<Signal> signals, String code, String side) {
        for (Signal s : signals) {
            if (code.equals(s.stockCode()) && side.equals(s.side())) return s.reason();
        }
        return "";
    }

    /** 取指定日期每只股票的价格。useCloseElseOpen=true 取 close，false 取 open。 */
    private Map<String, BigDecimal> collectPricesAt(LocalDate date,
                                                    Map<String, List<Map<String, Object>>> fullBars,
                                                    List<String> codes,
                                                    boolean useCloseElseOpen) {
        Map<String, BigDecimal> out = new HashMap<>();
        String dStr = date.toString();
        String key = useCloseElseOpen ? "close" : "open";
        String keyCap = useCloseElseOpen ? "Close" : "Open";
        for (String code : codes) {
            List<Map<String, Object>> all = fullBars.get(code);
            if (all == null) continue;
            for (Map<String, Object> bar : all) {
                String dt = dateOf(bar);
                if (!dStr.equals(dt)) continue;
                Object v = bar.get(key);
                if (v == null) v = bar.get(keyCap);
                if (v == null) break;
                try {
                    BigDecimal price = new BigDecimal(String.valueOf(v));
                    if (price.signum() > 0) out.put(code, price);
                } catch (NumberFormatException ignored) {}
                break;
            }
        }
        return out;
    }

    private List<LocalDate> computeTradingDays(Map<String, List<Map<String, Object>>> fullBars,
                                                LocalDate start, LocalDate end) {
        TreeSet<String> allDates = new TreeSet<>();
        for (List<Map<String, Object>> bars : fullBars.values()) {
            for (Map<String, Object> bar : bars) {
                String dt = dateOf(bar);
                if (dt != null) allDates.add(dt);
            }
        }
        String sStr = start.toString();
        String eStr = end.toString();
        List<LocalDate> out = new ArrayList<>();
        for (String d : allDates) {
            if (d.compareTo(sStr) < 0) continue;
            if (d.compareTo(eStr) > 0) break;
            try { out.add(LocalDate.parse(d)); }
            catch (Exception ignored) {}
        }
        return out;
    }

    // ---------------- gateway 数据拉取 ----------------

    @SuppressWarnings("unchecked")
    private List<String> fetchWatchlist() {
        Map<String, Object> resp = gateway.watchlist();
        Object data = resp == null ? null : resp.get("data");
        List<String> out = new ArrayList<>();
        if (data instanceof List<?> arr) {
            for (Object item : arr) {
                if (item instanceof Map<?, ?> row) {
                    Object code = row.get("code");
                    if (code != null) out.add(String.valueOf(code));
                }
            }
        }
        return out;
    }

    private Map<String, String> fetchNames(List<String> codes) {
        Map<String, String> out = new HashMap<>();
        try {
            SnapshotResponse snap = gateway.snapshot(String.join(",", codes));
            if (snap != null && snap.getData() != null) {
                for (Map<String, Object> row : snap.getData()) {
                    Object code = row.get("code");
                    Object name = row.get("name");
                    if (code != null && name != null) {
                        out.put(String.valueOf(code), String.valueOf(name));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[backtest] fetch names failed: {}", e.getMessage());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchBars(String code) {
        try {
            Map<String, Object> resp = gateway.bars(code, 9, BAR_COUNT);
            Object data = resp == null ? null : resp.get("data");
            List<Map<String, Object>> out = new ArrayList<>();
            if (data instanceof List<?> arr) {
                for (Object item : arr) {
                    if (item instanceof Map<?, ?> row) out.add((Map<String, Object>) row);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[backtest] fetch bars {} failed: {}", code, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String dateOf(Map<String, Object> bar) {
        Object dt = bar.get("datetime");
        if (dt == null) dt = bar.get("date");
        if (dt == null) return null;
        String s = String.valueOf(dt);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    // ---------------- 收益汇总 ----------------

    private BacktestResult summarize(BigDecimal initial, List<EquityPoint> curve, List<EngineTrade> trades) {
        BigDecimal finalEq = curve.isEmpty() ? initial : curve.get(curve.size() - 1).equity;
        BigDecimal returnPct = finalEq.subtract(initial).multiply(BigDecimal.valueOf(100))
                .divide(initial, 4, RoundingMode.HALF_UP);

        BigDecimal peak = initial;
        BigDecimal maxDD = BigDecimal.ZERO;
        for (EquityPoint p : curve) {
            if (p.equity.compareTo(peak) > 0) peak = p.equity;
            BigDecimal dd = peak.subtract(p.equity).multiply(BigDecimal.valueOf(100))
                    .divide(peak.signum() == 0 ? BigDecimal.ONE : peak, 4, RoundingMode.HALF_UP);
            if (dd.compareTo(maxDD) > 0) maxDD = dd;
        }
        return new BacktestResult(finalEq, returnPct, maxDD, curve, trades);
    }

    // ---------------- 结果数据结构 ----------------

    public record EquityPoint(String date, BigDecimal equity) {}

    public record EngineTrade(String date, String code, String side, int amount,
                              BigDecimal price, BigDecimal balanceAfter, String reason) {}

    public record BacktestResult(BigDecimal finalEquity,
                                 BigDecimal totalReturnPct,
                                 BigDecimal maxDrawdownPct,
                                 List<EquityPoint> equityCurve,
                                 List<EngineTrade> trades) {
        public List<Map<String, Object>> equityCurveAsList() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (EquityPoint p : equityCurve) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", p.date);
                row.put("equity", p.equity.setScale(2, RoundingMode.HALF_UP).toPlainString());
                out.add(row);
            }
            return out;
        }
    }
}
