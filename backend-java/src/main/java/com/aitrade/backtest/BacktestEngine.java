package com.aitrade.backtest;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.gateway.PythonGatewayClient;
import com.aitrade.gateway.dto.SnapshotResponse;
import com.aitrade.trade.strategy.MovingAverageExecutor;
import com.aitrade.trade.strategy.Signal;
import com.aitrade.trade.strategy.cta.CtaStrategyExecutor;
import com.aitrade.trade.strategy.indicator.IndicatorStrategyExecutor;
import com.aitrade.trade.strategy.script.ScriptStrategyExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntConsumer;

/**
 * 回测主引擎。流程：
 *   1. 拉 watchlist：
 *      · trader.poolName 为空 → 一次性拉 default watchlist，整窗口都用这批 codes
 *      · trader.poolName 非空 → 拉该池所有历史快照，按"回测日 T 找 ≤T 最近一份快照"构建逐日池子；
 *                                整体 codes = 各快照 union（用于预拉 K 线）
 *   2. 对每只 code 一次性拉 BAR_COUNT 根日 K（受 gateway 接口限制，只能拉最近 N 根）
 *   3. 用全量 K 线交集推出回测窗口内的交易日序列
 *   4. 主循环：
 *      T 日 close 决策 → enqueue 信号（严格池模式下 watchlist = 当日池 ∪ 当前持仓）
 *      记 T 日 close 净值
 *      切到 T+1：用 T+1 open 撮合所有 pending
 *   5. 返回净值曲线 / 成交 / 收益率 / 最大回撤
 *
 * 严格池模式（trader 选了 poolName 时）有效抵御幸存者偏差：策略在 T 日只能看见 T 日实际入池
 * 的股票，对回测后才入池的"事后赢家"不可见。已持仓但出池的股票照常允许卖出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestEngine {

    // BAR_COUNT 在 ECS 仅 1GB 内存、JVM heap 384MB 的实测约束下定为 500：
    // watchlist 100~200 只票一次性预拉到内存里需要 ~250MB；BAR_COUNT 设到 800 时被压爆触发 OOM。
    // 500 根日 K ≈ 2 年自然日（节假日扣减），够覆盖一轮主要市场环境。
    private static final int BAR_COUNT = 500;
    private static final BigDecimal BUY_FRACTION = new BigDecimal("0.10");
    private static final int TRADING_DAYS_PER_YEAR = 252;
    /** 沪深 300 ETF（510300）作为基准。mootdx 同 bars 接口透传；拉不到时基准曲线会是空数组。 */
    private static final String BENCHMARK_CODE = "510300";

    private final PythonGatewayClient gateway;
    private final MovingAverageExecutor maExecutor;
    private final IndicatorStrategyExecutor indicatorExecutor;
    private final ScriptStrategyExecutor scriptExecutor;
    private final CtaStrategyExecutor ctaExecutor;

    public BacktestResult run(AiTrader trader,
                              LocalDate startDate,
                              LocalDate endDate,
                              BigDecimal initialBalance,
                              IntConsumer progressCallback) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate 必须不晚于 endDate");
        }

        // 1. 拉 watchlist + snapshot 取股票名（按 trader.poolName 选池）
        //    严格池模式下额外构建 dateToPoolCodes：把回测窗口内每个交易日映射到当时的池子代码集合
        String poolName = trader.getPoolName();
        boolean strictPool = poolName != null && !poolName.isBlank();
        List<String> codes;
        // 每个回测日 → 该日有效池子的 codes set。null 表示无须按日切池子（沿用整体 codes）。
        Map<LocalDate, Set<String>> poolByDate = null;
        // names 多源累积：池子快照 / watchlist / snapshot，任一拿到就行
        Map<String, String> namesByCode = new HashMap<>();

        if (strictPool) {
            // 拉池子的所有历史快照（list 接口只给元数据，需要按日期逐个 detail 拉）
            //   同时把每个快照里 entry.name 累加到 namesByCode，作为已退市/停牌票的 name 兜底
            Map<LocalDate, Set<String>> rawSnapshots = fetchPoolHistorySnapshots(poolName, namesByCode);
            if (rawSnapshots.isEmpty()) {
                throw new IllegalStateException("选股池 '" + poolName + "' 还没有任何历史快照。"
                        + "请先到『选股池』页面点『立即构建』生成至少一份快照（每周五 cron 也会自动归档）。");
            }
            // codes 整体取所有快照的并集
            Set<String> union = new HashSet<>();
            for (Set<String> s : rawSnapshots.values()) union.addAll(s);
            codes = new ArrayList<>(union);
            log.info("[backtest] strict pool='{}': {} 份历史快照, union codes={}",
                    poolName, rawSnapshots.size(), codes.size());
            poolByDate = rawSnapshots;  // 会在 computeTradingDays 之后投影到具体交易日
        } else {
            // 非严格模式：default watchlist 返回的 data 行里自带 name，一并累加进 namesByCode
            log.info("[backtest] trader {} fetching default watchlist ...", trader.getId());
            codes = fetchWatchlist(null, namesByCode);
            if (codes.isEmpty()) {
                throw new IllegalStateException("watchlist 为空");
            }
        }
        log.info("[backtest] trader {} watchlist resolved: {} codes", trader.getId(), codes.size());
        // 再调一次 snapshot 拿最新的 name（活跃股票优先用最新名）；snapshot 不返回的不覆盖原有兜底
        mergeNamesFromSnapshot(codes, namesByCode);

        // 2. 预拉每只股票最近 BAR_COUNT 根日 K
        // 这一步是回测里最长的阻塞段（500 只 * 单次 ~200ms = 100s+），所以每 50 只回报一次"准备阶段进度"，
        // 占 0-15% 这段，主循环占 15-100%。卡死时用户能从日志/进度条看到具体在第几只。
        Map<String, List<Map<String, Object>>> fullBars = new HashMap<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            List<Map<String, Object>> bars = fetchBars(code);
            if (!bars.isEmpty()) fullBars.put(code, bars);
            if ((i + 1) % 50 == 0 || i + 1 == codes.size()) {
                long elapsed = System.currentTimeMillis() - t0;
                log.info("[backtest] trader {} bars progress: {}/{} ({}ms elapsed, {} hit)",
                        trader.getId(), i + 1, codes.size(), elapsed, fullBars.size());
                if (progressCallback != null) {
                    int prog = Math.min(14, (i + 1) * 15 / codes.size());
                    progressCallback.accept(prog);
                }
            }
        }
        if (fullBars.isEmpty()) {
            throw new IllegalStateException("所有股票的历史 K 线均无数据");
        }

        // 2.5 拉沪深 300 基准 K 线（510300 ETF）。失败不影响主流程，benchmark 留空。
        List<Map<String, Object>> benchmarkBars = fetchBars(BENCHMARK_CODE);
        if (benchmarkBars.isEmpty()) {
            log.info("[backtest] benchmark {} 拉取为空，报告将无基准对比", BENCHMARK_CODE);
        }

        // 3. 算交易日序列：所有股票出现过的日期并集，再筛 [startDate, endDate]
        List<LocalDate> tradingDays = computeTradingDays(fullBars, startDate, endDate);
        if (tradingDays.size() < 2) {
            throw new IllegalStateException("回测窗口内交易日不足（need ≥ 2）：" + tradingDays.size());
        }
        log.info("[backtest] trader {} pool={} window {}~{} 交易日 {} 天, watchlist {} 只",
                trader.getId(), poolName == null ? "(默认)" : poolName,
                startDate, endDate, tradingDays.size(), codes.size());

        // 把"日期 → 池子 codes"的稀疏快照投影到每个交易日：T 日的有效池 = 历史快照中 ≤T 的最近一份。
        // 若 T 之前完全没有快照，回退用 ≥T 的最早一份（避免回测起始空池跑不起来）。
        Map<LocalDate, Set<String>> effectivePoolByDay = null;
        if (strictPool) {
            effectivePoolByDay = projectPoolToTradingDays(poolByDate, tradingDays);
        }

        // 4. 主循环
        BacktestSandbox sandbox = new BacktestSandbox(initialBalance);
        List<EquityPoint> equityCurve = new ArrayList<>();
        List<EngineTrade> trades = new ArrayList<>();

        for (int i = 0; i < tradingDays.size(); i++) {
            LocalDate today = tradingDays.get(i);
            // 严格池模式：当日 codes = 当日池 ∪ 当前持仓 codes（持仓未清不能"看不见"以致无法卖出）
            List<String> codesForToday = codes;
            if (effectivePoolByDay != null) {
                Set<String> dayPool = effectivePoolByDay.get(today);
                Set<String> merged = new HashSet<>(dayPool == null ? Collections.emptySet() : dayPool);
                merged.addAll(sandbox.positions().keySet());
                codesForToday = new ArrayList<>(merged);
            }
            BacktestContext ctx = new BacktestContext(today, codesForToday, fullBars, namesByCode);

            // A. 决策（用 today close 当作 MA 的"今日临时收盘价"）
            Map<String, Position> positions = sandboxPositionsToEntityMap(sandbox);
            List<Signal> signals;
            try {
                signals = dispatchDecide(trader, ctx, positions);
            } catch (Exception e) {
                log.warn("[backtest] decide failed at {}: {}", today, e.getMessage());
                signals = List.of();
            }

            // B. 落单到 sandbox（仿 Orchestrator.placeSignal）
            for (Signal s : signals) {
                placeSignal(trader, ctx, s, sandbox);
            }

            // C. 记 today close 净值（同时作为下一交易日撮合的 prev_close 用于涨跌停判定）
            Map<String, BigDecimal> closeByCode = collectPricesAt(today, fullBars, codes, true);
            BigDecimal equity = sandbox.equity(closeByCode);
            equityCurve.add(new EquityPoint(today.toString(), equity));

            // C2. 把 today high 喂给 sandbox，刷新所有持仓的 high_since_entry（CTA 跟踪止损用）
            sandbox.markHighWithDayHigh(collectHighsAt(today, fullBars, codes));

            // D. 切到下一交易日：撮合 pending（用今日 close 作为次日的 prev_close 做涨跌停校验）
            if (i + 1 < tradingDays.size()) {
                LocalDate nextDay = tradingDays.get(i + 1);
                sandbox.clearTodayBuys();
                Map<String, BigDecimal> openByCode = collectPricesAt(nextDay, fullBars, codes, false);
                List<BacktestSandbox.Fill> fills = sandbox.settleAtOpen(openByCode, closeByCode);
                for (BacktestSandbox.Fill f : fills) {
                    trades.add(new EngineTrade(
                            nextDay.toString(), f.code(),
                            namesByCode.getOrDefault(f.code(), f.code()),
                            f.side(),
                            f.amount(), f.price(), f.balanceAfter(), f.costPriceAtSell(),
                            findReason(signals, f.code(), f.side())));
                }
            }

            if (progressCallback != null) {
                // 准备阶段（拉 bars）占 0-15%，主循环占 15-100%
                int prog = Math.min(99, 15 + (i + 1) * 85 / tradingDays.size());
                if (i == tradingDays.size() - 1) prog = 100;
                progressCallback.accept(prog);
            }
        }

        return summarize(initialBalance, equityCurve, trades, benchmarkBars, tradingDays);
    }

    // ---------------- 主循环辅助 ----------------

    /** 按 trader.strategyType 分发到对应 Executor.decideWith。 */
    private List<Signal> dispatchDecide(AiTrader trader, BacktestContext ctx, Map<String, Position> positions) {
        String type = trader.getStrategyType() == null ? "" : trader.getStrategyType().toUpperCase();
        return switch (type) {
            case "MA" -> maExecutor.decideWith(trader, ctx, positions);
            case "INDICATOR" -> indicatorExecutor.decideWith(trader, ctx, positions);
            case "SCRIPT" -> scriptExecutor.decideWith(trader, ctx, positions);
            case "CTA" -> ctaExecutor.decideWith(trader, ctx, positions);
            default -> throw new IllegalStateException("回测不支持的策略类型: " + trader.getStrategyType());
        };
    }

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
            p.setHighSinceEntry(e.getValue().highSinceEntry);
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
    Map<String, BigDecimal> collectPricesAt(LocalDate date,
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

    /** 取指定日期每只股票的 high（用于刷新跟踪止损的高水位）。 */
    Map<String, BigDecimal> collectHighsAt(LocalDate date,
                                            Map<String, List<Map<String, Object>>> fullBars,
                                            List<String> codes) {
        Map<String, BigDecimal> out = new HashMap<>();
        String dStr = date.toString();
        for (String code : codes) {
            List<Map<String, Object>> all = fullBars.get(code);
            if (all == null) continue;
            for (Map<String, Object> bar : all) {
                String dt = dateOf(bar);
                if (!dStr.equals(dt)) continue;
                Object v = bar.get("high");
                if (v == null) v = bar.get("High");
                if (v == null) break;
                try {
                    BigDecimal h = new BigDecimal(String.valueOf(v));
                    if (h.signum() > 0) out.put(code, h);
                } catch (NumberFormatException ignored) {}
                break;
            }
        }
        return out;
    }

    List<LocalDate> computeTradingDays(Map<String, List<Map<String, Object>>> fullBars,
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
    private List<String> fetchWatchlist(String poolName, Map<String, String> namesOut) {
        Map<String, Object> resp = (poolName == null || poolName.isBlank())
                ? gateway.watchlist()
                : gateway.watchlist(poolName);
        Object data = resp == null ? null : resp.get("data");
        List<String> out = new ArrayList<>();
        int skippedSt = 0;
        if (data instanceof List<?> arr) {
            for (Object item : arr) {
                if (item instanceof Map<?, ?> row) {
                    Object code = row.get("code");
                    if (code != null) {
                        String c = String.valueOf(code);
                        Object name = row.get("name");
                        String n = name == null ? "" : String.valueOf(name);
                        // ST / *ST 退市风险股流动性差、回测假设易跑偏，整体跳过
                        if (isStName(n)) { skippedSt++; continue; }
                        out.add(c);
                        if (!n.isBlank() && !n.equals(c) && namesOut != null) namesOut.put(c, n);
                    }
                }
            }
        }
        if (skippedSt > 0) log.info("[backtest] filtered {} ST/退市风险 stocks from watchlist", skippedSt);
        return out;
    }

    /** ST 票判断：名称含 ST / *ST / S*ST 都视为风险股，统一过滤。 */
    private static boolean isStName(String name) {
        if (name == null) return false;
        String up = name.toUpperCase().replace(" ", "");
        return up.contains("ST") || up.startsWith("*");
    }

    /**
     * 拉指定 pool 的所有历史快照。返回 {快照日期 → 该快照包含的 codes set}。
     * gateway 的 /pool/{name}/history 只给元数据（日期+count），需要再调 /pool/{name}/history/{date}
     * 取每份快照的 codes。返回的 map 用 TreeMap 保证按日期升序。
     *
     * 同时把每个快照 entry.name 累加到 namesOut（非空、非 code 本身才写入），用作已退市股票的 name 兜底。
     */
    @SuppressWarnings("unchecked")
    private TreeMap<LocalDate, Set<String>> fetchPoolHistorySnapshots(String poolName,
                                                                       Map<String, String> namesOut) {
        TreeMap<LocalDate, Set<String>> out = new TreeMap<>();
        Map<String, Object> listResp;
        try {
            listResp = gateway.poolHistoryList(poolName);
        } catch (Exception e) {
            log.warn("[backtest] fetch pool history list failed: {}", e.getMessage());
            return out;
        }
        Object hist = listResp == null ? null : listResp.get("history");
        if (!(hist instanceof List<?> arr)) return out;
        for (Object item : arr) {
            if (!(item instanceof Map<?, ?> row)) continue;
            Object date = row.get("date");
            if (date == null) continue;
            String dateStr = String.valueOf(date);
            LocalDate d;
            try { d = LocalDate.parse(dateStr); } catch (Exception e) { continue; }
            try {
                Map<String, Object> detail = gateway.poolHistoryOne(poolName, dateStr);
                Object codesObj = detail == null ? null : detail.get("codes");
                if (!(codesObj instanceof List<?> codesArr)) continue;
                Set<String> codes = new HashSet<>();
                for (Object c : codesArr) {
                    if (c instanceof Map<?, ?> entry) {
                        Object code = entry.get("code");
                        if (code == null) continue;
                        String cs = String.valueOf(code);
                        codes.add(cs);
                        Object name = entry.get("name");
                        if (name != null && namesOut != null) {
                            String ns = String.valueOf(name);
                            if (!ns.isBlank() && !ns.equals(cs)) namesOut.put(cs, ns);
                        }
                    }
                }
                if (!codes.isEmpty()) out.put(d, codes);
            } catch (Exception e) {
                log.warn("[backtest] fetch pool snapshot {} {} failed: {}", poolName, dateStr, e.getMessage());
            }
        }
        return out;
    }

    /** 用实时 snapshot 给 namesByCode 补/覆盖名字。snapshot 拉不到的不影响已有兜底。 */
    void mergeNamesFromSnapshot(List<String> codes, Map<String, String> namesByCode) {
        try {
            SnapshotResponse snap = gateway.snapshot(String.join(",", codes));
            if (snap != null && snap.getData() != null) {
                for (Map<String, Object> row : snap.getData()) {
                    Object code = row.get("code");
                    Object name = row.get("name");
                    if (code == null || name == null) continue;
                    String c = String.valueOf(code);
                    String n = String.valueOf(name);
                    if (!n.isBlank() && !n.equals(c)) namesByCode.put(c, n);
                }
            }
        } catch (Exception e) {
            log.warn("[backtest] snapshot for names failed: {}", e.getMessage());
        }
    }

    /**
     * 把稀疏的历史快照映射到每个交易日：T 日的有效池 = 历史快照中 date ≤ T 的最近一份；
     * 若 T 早于任何快照，回退用 ≥T 的最早一份（避免回测起始没快照导致整窗口空池）。
     */
    private Map<LocalDate, Set<String>> projectPoolToTradingDays(Map<LocalDate, Set<String>> snapshots,
                                                                  List<LocalDate> tradingDays) {
        TreeMap<LocalDate, Set<String>> sorted = (snapshots instanceof TreeMap)
                ? (TreeMap<LocalDate, Set<String>>) snapshots
                : new TreeMap<>(snapshots);
        Map<LocalDate, Set<String>> out = new HashMap<>();
        for (LocalDate day : tradingDays) {
            Map.Entry<LocalDate, Set<String>> floor = sorted.floorEntry(day);
            if (floor != null) {
                out.put(day, floor.getValue());
            } else {
                // T 早于所有快照：用最早的一份做兜底（这通常发生在回测窗口的最开头几天）
                Map.Entry<LocalDate, Set<String>> first = sorted.firstEntry();
                if (first != null) out.put(day, first.getValue());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fetchBars(String code) {
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

    static String dateOf(Map<String, Object> bar) {
        Object dt = bar.get("datetime");
        if (dt == null) dt = bar.get("date");
        if (dt == null) return null;
        String s = String.valueOf(dt);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    // ---------------- 收益汇总 ----------------

    BacktestResult summarize(BigDecimal initial,
                                     List<EquityPoint> curve,
                                     List<EngineTrade> trades,
                                     List<Map<String, Object>> benchmarkBars,
                                     List<LocalDate> tradingDays) {
        BigDecimal finalEq = curve.isEmpty() ? initial : curve.get(curve.size() - 1).equity;
        BigDecimal returnPct = finalEq.subtract(initial).multiply(BigDecimal.valueOf(100))
                .divide(initial, 4, RoundingMode.HALF_UP);

        // 最大回撤
        BigDecimal peak = initial;
        BigDecimal maxDD = BigDecimal.ZERO;
        for (EquityPoint p : curve) {
            if (p.equity.compareTo(peak) > 0) peak = p.equity;
            BigDecimal dd = peak.subtract(p.equity).multiply(BigDecimal.valueOf(100))
                    .divide(peak.signum() == 0 ? BigDecimal.ONE : peak, 4, RoundingMode.HALF_UP);
            if (dd.compareTo(maxDD) > 0) maxDD = dd;
        }

        // 日收益序列（用 double 算统计量，避免 BigDecimal 标准差实现复杂度）
        int n = curve.size();
        double[] dailyReturns = new double[Math.max(0, n - 1)];
        for (int i = 1; i < n; i++) {
            double prev = curve.get(i - 1).equity.doubleValue();
            double cur = curve.get(i).equity.doubleValue();
            dailyReturns[i - 1] = prev == 0 ? 0 : (cur / prev - 1);
        }

        // 年化收益：(finalEquity/initial)^(252/n) - 1
        double annualReturn;
        if (n < 2 || initial.signum() <= 0) {
            annualReturn = 0;
        } else {
            double total = finalEq.doubleValue() / initial.doubleValue();
            if (total <= 0) {
                annualReturn = -1;
            } else {
                annualReturn = Math.pow(total, (double) TRADING_DAYS_PER_YEAR / n) - 1;
            }
        }

        double mean = mean(dailyReturns);
        double std = stddev(dailyReturns, mean);
        double downStd = downsideStddev(dailyReturns);
        double sqrt252 = Math.sqrt(TRADING_DAYS_PER_YEAR);
        double sharpe = std == 0 ? 0 : (mean / std) * sqrt252;
        double sortino = downStd == 0 ? 0 : (mean / downStd) * sqrt252;
        double calmar = maxDD.signum() == 0 ? 0 : annualReturn / (maxDD.doubleValue() / 100.0);

        // 胜率 / 盈亏比（FIFO 配对：每个 SELL 消耗最早的 BUY 同 code 仓位，得到一笔已平仓盈亏）
        TradeStats stats = pairTrades(trades);

        List<EquityPoint> benchmarkCurve = buildBenchmarkCurve(benchmarkBars, tradingDays, initial);
        List<MonthlyReturn> monthlyReturns = computeMonthlyReturns(curve, initial);

        return new BacktestResult(
                finalEq, returnPct, maxDD,
                bd(annualReturn * 100),
                bd(sharpe), bd(sortino), bd(calmar),
                bd(stats.winRatePct), bd(stats.profitLossRatio),
                curve, benchmarkCurve, monthlyReturns, trades);
    }

    /** double → BigDecimal(10,4)，NaN/Infinity 兜底为 0。 */
    private static BigDecimal bd(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return new BigDecimal(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static double mean(double[] arr) {
        if (arr.length == 0) return 0;
        double s = 0;
        for (double v : arr) s += v;
        return s / arr.length;
    }

    private static double stddev(double[] arr, double mean) {
        if (arr.length <= 1) return 0;
        double sumSq = 0;
        for (double v : arr) {
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / arr.length);
    }

    /** 只统计负收益部分的标准差（用于 Sortino）。 */
    private static double downsideStddev(double[] arr) {
        if (arr.length == 0) return 0;
        double sumSq = 0;
        int cnt = 0;
        for (double v : arr) {
            if (v < 0) {
                sumSq += v * v;
                cnt++;
            }
        }
        return cnt == 0 ? 0 : Math.sqrt(sumSq / cnt);
    }

    /** FIFO 配对 BUY/SELL，输出 winRate (%) 与 profit/loss ratio。 */
    private static TradeStats pairTrades(List<EngineTrade> trades) {
        Map<String, Deque<BigDecimal>> buyPriceQueues = new HashMap<>();
        Map<String, Deque<Integer>> buyAmtQueues = new HashMap<>();
        int wins = 0, total = 0;
        double sumWin = 0, sumLoss = 0;
        int winCnt = 0, lossCnt = 0;

        for (EngineTrade t : trades) {
            if ("BUY".equals(t.side())) {
                buyPriceQueues.computeIfAbsent(t.code(), k -> new ArrayDeque<>()).addLast(t.price());
                buyAmtQueues.computeIfAbsent(t.code(), k -> new ArrayDeque<>()).addLast(t.amount());
            } else if ("SELL".equals(t.side())) {
                Deque<BigDecimal> priceQ = buyPriceQueues.get(t.code());
                Deque<Integer> amtQ = buyAmtQueues.get(t.code());
                if (priceQ == null || amtQ == null) continue;
                int remaining = t.amount();
                double sellPrice = t.price().doubleValue();
                while (remaining > 0 && !amtQ.isEmpty()) {
                    int avail = amtQ.peekFirst();
                    double buyPrice = priceQ.peekFirst().doubleValue();
                    int use = Math.min(avail, remaining);
                    double pnl = (sellPrice - buyPrice) * use;
                    total++;
                    if (pnl > 0) {
                        wins++;
                        winCnt++;
                        sumWin += pnl;
                    } else if (pnl < 0) {
                        lossCnt++;
                        sumLoss += -pnl;
                    }
                    remaining -= use;
                    if (use == avail) {
                        amtQ.pollFirst();
                        priceQ.pollFirst();
                    } else {
                        amtQ.pollFirst();
                        amtQ.addFirst(avail - use);
                    }
                }
            }
        }
        TradeStats s = new TradeStats();
        s.winRatePct = total == 0 ? 0 : (wins * 100.0 / total);
        double avgWin = winCnt == 0 ? 0 : sumWin / winCnt;
        double avgLoss = lossCnt == 0 ? 0 : sumLoss / lossCnt;
        s.profitLossRatio = avgLoss == 0 ? 0 : avgWin / avgLoss;
        return s;
    }

    private static class TradeStats {
        double winRatePct;
        double profitLossRatio;
    }

    /** 把 510300 的日 K 归一化到 initialBalance 起点，按 tradingDays 投影。 */
    private List<EquityPoint> buildBenchmarkCurve(List<Map<String, Object>> bars,
                                                  List<LocalDate> tradingDays,
                                                  BigDecimal initial) {
        if (bars == null || bars.isEmpty() || tradingDays.isEmpty()) return Collections.emptyList();
        Map<String, BigDecimal> closeByDate = new HashMap<>();
        for (Map<String, Object> bar : bars) {
            String dt = dateOf(bar);
            if (dt == null) continue;
            Object v = bar.get("close");
            if (v == null) v = bar.get("Close");
            if (v == null) continue;
            try {
                BigDecimal price = new BigDecimal(String.valueOf(v));
                if (price.signum() > 0) closeByDate.put(dt, price);
            } catch (NumberFormatException ignored) {}
        }
        if (closeByDate.isEmpty()) return Collections.emptyList();
        // 起点：tradingDays 内第一个能命中 close 的日期
        BigDecimal baseClose = null;
        for (LocalDate d : tradingDays) {
            BigDecimal c = closeByDate.get(d.toString());
            if (c != null && c.signum() > 0) { baseClose = c; break; }
        }
        if (baseClose == null) return Collections.emptyList();
        List<EquityPoint> out = new ArrayList<>(tradingDays.size());
        BigDecimal lastVal = initial;
        for (LocalDate d : tradingDays) {
            BigDecimal c = closeByDate.get(d.toString());
            if (c != null && c.signum() > 0) {
                lastVal = initial.multiply(c).divide(baseClose, 4, RoundingMode.HALF_UP);
            }
            // 当日基准缺数据：复用上一日的值，避免曲线断点
            out.add(new EquityPoint(d.toString(), lastVal));
        }
        return out;
    }

    /** 用 equityCurve groupBy YYYY-MM 取每月末 equity 算月收益。 */
    private List<MonthlyReturn> computeMonthlyReturns(List<EquityPoint> curve, BigDecimal initial) {
        if (curve.isEmpty()) return Collections.emptyList();
        TreeMap<String, BigDecimal> monthLast = new TreeMap<>();
        for (EquityPoint p : curve) {
            if (p.date == null || p.date.length() < 7) continue;
            String ym = p.date.substring(0, 7);
            monthLast.put(ym, p.equity); // 顺序遍历，自然覆盖到当月最后一日
        }
        List<MonthlyReturn> out = new ArrayList<>(monthLast.size());
        BigDecimal prev = initial;
        for (Map.Entry<String, BigDecimal> e : monthLast.entrySet()) {
            BigDecimal cur = e.getValue();
            BigDecimal pct;
            if (prev.signum() <= 0) {
                pct = BigDecimal.ZERO;
            } else {
                pct = cur.subtract(prev).multiply(BigDecimal.valueOf(100))
                        .divide(prev, 4, RoundingMode.HALF_UP);
            }
            out.add(new MonthlyReturn(e.getKey(), pct));
            prev = cur;
        }
        return out;
    }

    // ---------------- 结果数据结构 ----------------

    public record EquityPoint(String date, BigDecimal equity) {}

    public record EngineTrade(String date, String code, String name, String side, int amount,
                              BigDecimal price, BigDecimal balanceAfter,
                              BigDecimal costPriceAtSell, String reason) {}

    public record MonthlyReturn(String ym, BigDecimal returnPct) {}

    public record BacktestResult(BigDecimal finalEquity,
                                 BigDecimal totalReturnPct,
                                 BigDecimal maxDrawdownPct,
                                 BigDecimal annualReturnPct,
                                 BigDecimal sharpeRatio,
                                 BigDecimal sortinoRatio,
                                 BigDecimal calmarRatio,
                                 BigDecimal winRatePct,
                                 BigDecimal profitLossRatio,
                                 List<EquityPoint> equityCurve,
                                 List<EquityPoint> benchmarkCurve,
                                 List<MonthlyReturn> monthlyReturns,
                                 List<EngineTrade> trades) {
        public List<Map<String, Object>> equityCurveAsList() {
            return curveAsList(equityCurve);
        }

        public List<Map<String, Object>> benchmarkCurveAsList() {
            return curveAsList(benchmarkCurve);
        }

        public List<Map<String, Object>> monthlyReturnsAsList() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (MonthlyReturn m : monthlyReturns) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ym", m.ym);
                row.put("returnPct", m.returnPct.setScale(4, RoundingMode.HALF_UP).toPlainString());
                out.add(row);
            }
            return out;
        }

        private static List<Map<String, Object>> curveAsList(List<EquityPoint> points) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (EquityPoint p : points) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", p.date);
                row.put("equity", p.equity.setScale(2, RoundingMode.HALF_UP).toPlainString());
                out.add(row);
            }
            return out;
        }
    }
}
