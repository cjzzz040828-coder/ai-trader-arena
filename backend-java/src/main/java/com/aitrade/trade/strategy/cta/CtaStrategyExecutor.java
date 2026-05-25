package com.aitrade.trade.strategy.cta;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.trade.strategy.MarketContext;
import com.aitrade.trade.strategy.Signal;
import com.aitrade.trade.strategy.StrategyExecutor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CTA 趋势/突破策略执行器。两种入场二选一：
 *   - DUAL_MA: 金叉买入；死叉作为"反向出场"信号（仅当 exitOnReverseSignal=true 才触发卖出）
 *   - BREAKOUT: 今日价突破过去 N 日 high 最大值买入；跌破过去 N 日 low 最小值作为反向出场信号
 *
 * 决策优先级（同一只票同一次 decide）：
 *   止损 &gt; 反向出场 &gt; 入场
 * 即：先看止损（固定 / 跟踪），再看反向信号是否触发，最后才考虑无持仓时的入场。
 *
 * 跟踪止损需要 position.high_since_entry：实盘由 MatchEngine 在 BUY 成交 + revalue 时维护；
 * 回测由 BacktestEngine 在每日 markHighWithDayHigh 时维护。null 时退化用 cost_price 兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CtaStrategyExecutor implements StrategyExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ENTRY_TYPES = Set.of("DUAL_MA", "BREAKOUT");

    private final PositionMapper positionMapper;

    @Override
    public String strategyType() { return "CTA"; }

    @Override
    public List<Signal> decide(AiTrader trader, MarketContext ctx) {
        return decideWith(trader, ctx, loadPositionsByCode(trader.getId()));
    }

    /** 回测引擎入口：持仓快照外部传入，不查 DB。 */
    public List<Signal> decideWith(AiTrader trader, MarketContext ctx, Map<String, Position> positionByCode) {
        CtaConfig cfg = parseConfig(trader);
        if (cfg == null) return List.of();

        String entryType = cfg.getEntry().getType().toUpperCase();
        boolean exitOnReverse = Boolean.TRUE.equals(cfg.getExitOnReverseSignal());
        int needBars = computeBarsNeeded(entryType, cfg.getEntry());

        List<Signal> signals = new ArrayList<>();
        int scanned = 0, buys = 0, sells = 0;

        for (String code : ctx.watchlist()) {
            BigDecimal price = ctx.priceOf(code);
            if (price == null || price.signum() <= 0) continue;
            List<Map<String, Object>> bars = ctx.bars(code, 9, needBars);
            if (bars.size() < needBars) continue;
            scanned++;

            double[][] ohlc = extractOhlc(bars);
            double todayPrice = price.doubleValue();

            EntryEval eval = evalEntry(entryType, cfg.getEntry(), ohlc, todayPrice);
            if (eval == null) continue;

            Position pos = positionByCode.get(code);
            int holding = pos == null ? 0 : pos.getAmount();
            int sellable = pos == null ? 0 : Math.max(0, pos.getAmount() - (pos.getFrozenAmount() == null ? 0 : pos.getFrozenAmount()));

            // 优先级 1：止损（仅持有可卖时检查）
            if (sellable > 0) {
                String stopReason = checkStopLoss(cfg.getStopLoss(), pos, todayPrice);
                if (stopReason != null) {
                    signals.add(Signal.sell(code, stopReason));
                    sells++;
                    continue;
                }
                // 优先级 2：反向出场（exitOnReverseSignal=true 才触发）
                if (exitOnReverse && eval.exit) {
                    signals.add(Signal.sell(code, "反向信号 " + eval.reason));
                    sells++;
                    continue;
                }
            }

            // 优先级 3：入场（无持仓时）
            if (eval.entry && holding == 0) {
                signals.add(Signal.buy(code, eval.reason));
                buys++;
            }
        }

        log.info("[strategy] trader {} ({}) CTA[{}] scan {} stocks, {} buy {} sell",
                trader.getId(), trader.getName(), entryType, scanned, buys, sells);
        return signals;
    }

    // ---------------- 配置解析 ----------------

    private CtaConfig parseConfig(AiTrader trader) {
        String json = trader.getCtaConfigJson();
        if (json == null || json.isBlank()) {
            log.warn("[strategy] CTA trader {} empty config, skip", trader.getId());
            return null;
        }
        try {
            CtaConfig cfg = JSON.readValue(json, CtaConfig.class);
            if (cfg.getEntry() == null || cfg.getEntry().getType() == null) return null;
            return cfg;
        } catch (Exception e) {
            log.warn("[strategy] CTA trader {} parse config failed: {}", trader.getId(), e.getMessage());
            return null;
        }
    }

    private int computeBarsNeeded(String entryType, CtaConfig.Entry entry) {
        int need = switch (entryType) {
            case "DUAL_MA" -> Math.max(safeInt(entry.getLongPeriod(), 20) + 5, 30);
            case "BREAKOUT" -> Math.max(safeInt(entry.getLookback(), 20) + 5, 30);
            default -> 30;
        };
        return Math.max(need, 30);
    }

    // ---------------- 入场/反向信号判断 ----------------

    private EntryEval evalEntry(String type, CtaConfig.Entry entry, double[][] ohlc, double todayPrice) {
        double[] closes = ohlc[0];
        double[] highs = ohlc[1];
        double[] lows = ohlc[2];

        if ("DUAL_MA".equals(type)) {
            int sh = safeInt(entry.getShortPeriod(), 5);
            int lo = safeInt(entry.getLongPeriod(), 20);
            if (sh >= lo || sh < 2 || lo < 2) return null;
            // 与 MovingAverageExecutor 对齐：closes 末尾是 today partial，prev 不含 today，curr 用 todayPrice 替换末尾
            double prevShort = avgTail(closes, sh, 1);
            double prevLong = avgTail(closes, lo, 1);
            double currShort = avgTailWithToday(closes, sh, todayPrice);
            double currLong = avgTailWithToday(closes, lo, todayPrice);
            boolean goldCross = prevShort <= prevLong && currShort > currLong;
            boolean deathCross = prevShort >= prevLong && currShort < currLong;
            String label = String.format("MA%d=%.3f MA%d=%.3f", sh, currShort, lo, currLong);
            if (goldCross) return new EntryEval(true, false, "金叉 " + label);
            if (deathCross) return new EntryEval(false, true, "死叉 " + label);
            return new EntryEval(false, false, label);
        }
        if ("BREAKOUT".equals(type)) {
            int lookback = safeInt(entry.getLookback(), 20);
            if (lookback < 5) return null;
            // 不含 today：取 [length-lookback-1, length-2] 共 lookback 根
            int end = highs.length - 1;  // exclusive
            int start = end - lookback;
            if (start < 0) return null;
            double highMax = arrayMax(highs, start, end);
            double lowMin = arrayMin(lows, start, end);
            boolean breakUp = todayPrice > highMax;
            boolean breakDown = todayPrice < lowMin;
            String label = String.format("price=%.3f, %d日 high=%.3f low=%.3f", todayPrice, lookback, highMax, lowMin);
            if (breakUp) return new EntryEval(true, false, "向上突破 " + label);
            if (breakDown) return new EntryEval(false, true, "向下击穿 " + label);
            return new EntryEval(false, false, label);
        }
        return null;
    }

    // ---------------- 止损判断 ----------------

    private String checkStopLoss(CtaConfig.StopLoss sl, Position pos, double todayPrice) {
        if (sl == null) return null;
        BigDecimal costPrice = pos.getCostPrice();
        if (costPrice == null || costPrice.signum() <= 0) return null;
        double cost = costPrice.doubleValue();

        if (sl.getFixedPct() != null && sl.getFixedPct() > 0) {
            double trigger = cost * (1.0 - sl.getFixedPct() / 100.0);
            if (todayPrice <= trigger) {
                return String.format("固定止损 价%.3f≤%.3f (cost=%.3f -%.1f%%)",
                        todayPrice, trigger, cost, sl.getFixedPct());
            }
        }
        if (sl.getTrailingPct() != null && sl.getTrailingPct() > 0) {
            BigDecimal highSinceEntry = pos.getHighSinceEntry();
            // 历史最高未维护时退化用成本价兜底（避免一上来就触发跟踪止损）
            double high = (highSinceEntry == null || highSinceEntry.signum() <= 0)
                    ? cost : highSinceEntry.doubleValue();
            double trigger = high * (1.0 - sl.getTrailingPct() / 100.0);
            if (todayPrice <= trigger) {
                return String.format("跟踪止损 价%.3f≤%.3f (high=%.3f -%.1f%%)",
                        todayPrice, trigger, high, sl.getTrailingPct());
            }
        }
        return null;
    }

    // ---------------- 辅助 ----------------

    private record EntryEval(boolean entry, boolean exit, String reason) {}

    private Map<String, Position> loadPositionsByCode(Long traderId) {
        Map<String, Position> map = new HashMap<>();
        for (Position p : positionMapper.selectList(new QueryWrapper<Position>()
                .eq("trader_id", traderId).gt("amount", 0))) {
            map.put(p.getStockCode(), p);
        }
        return map;
    }

    /** 取 closes 末尾 n 个的平均，offset=1 表示不含最后一根。 */
    private static double avgTail(double[] closes, int n, int offset) {
        int end = closes.length - offset;
        int start = end - n;
        if (start < 0) return 0.0;
        double sum = 0;
        for (int i = start; i < end; i++) sum += closes[i];
        return sum / n;
    }

    /** 取末尾 n 个 close 的均值，但用 todayPrice 替换末尾那根（=closes[length-1]）。
     *  与 MovingAverageExecutor.avgTailWithToday 算法一致。 */
    private static double avgTailWithToday(double[] closes, int n, double todayPrice) {
        if (closes.length < n) return 0.0;
        double sum = todayPrice;
        for (int i = closes.length - n; i < closes.length - 1; i++) sum += closes[i];
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP).doubleValue();
    }

    /** arr[start, end) 区间最大值。区间空返回 -Infinity（外层比较会自然失败）。 */
    private static double arrayMax(double[] arr, int start, int end) {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = start; i < end; i++) {
            if (!Double.isNaN(arr[i]) && arr[i] > m) m = arr[i];
        }
        return m;
    }

    private static double arrayMin(double[] arr, int start, int end) {
        double m = Double.POSITIVE_INFINITY;
        for (int i = start; i < end; i++) {
            if (!Double.isNaN(arr[i]) && arr[i] < m) m = arr[i];
        }
        return m;
    }

    private static double[][] extractOhlc(List<Map<String, Object>> bars) {
        int n = bars.size();
        double[] closes = new double[n];
        double[] highs = new double[n];
        double[] lows = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> bar = bars.get(i);
            closes[i] = parseD(bar.get("close"), bar.get("Close"));
            highs[i] = parseD(bar.get("high"), bar.get("High"));
            lows[i] = parseD(bar.get("low"), bar.get("Low"));
            // 容错：high/low 缺失退化为 close
            if (highs[i] == 0 && closes[i] > 0) highs[i] = closes[i];
            if (lows[i] == 0 && closes[i] > 0) lows[i] = closes[i];
        }
        return new double[][]{closes, highs, lows};
    }

    private static double parseD(Object a, Object b) {
        Object v = a != null ? a : b;
        if (v == null) return 0;
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }

    private static int safeInt(Integer v, int def) {
        return v == null ? def : v;
    }

    // ---------------- 静态校验（给 TraderService 用） ----------------

    /** 解析+基本校验，失败抛 IllegalArgumentException(message)。 */
    public static CtaConfig parseAndValidate(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("ctaConfigJson 不能为空");
        }
        CtaConfig cfg;
        try {
            cfg = JSON.readValue(json, CtaConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("ctaConfigJson 格式非法: " + e.getMessage());
        }
        CtaConfig.Entry entry = cfg.getEntry();
        if (entry == null || entry.getType() == null || entry.getType().isBlank()) {
            throw new IllegalArgumentException("entry.type 不能为空");
        }
        String type = entry.getType().toUpperCase();
        if (!ENTRY_TYPES.contains(type)) {
            throw new IllegalArgumentException("entry.type 仅支持 " + ENTRY_TYPES + "，实际: " + entry.getType());
        }
        if ("DUAL_MA".equals(type)) {
            Integer sh = entry.getShortPeriod();
            Integer lo = entry.getLongPeriod();
            if (sh == null || sh < 2 || sh > 60) {
                throw new IllegalArgumentException("DUAL_MA.shortPeriod 必须 2~60");
            }
            if (lo == null || lo < 2 || lo > 250) {
                throw new IllegalArgumentException("DUAL_MA.longPeriod 必须 2~250");
            }
            if (sh >= lo) {
                throw new IllegalArgumentException("DUAL_MA.shortPeriod 必须小于 longPeriod");
            }
        } else {  // BREAKOUT
            Integer lb = entry.getLookback();
            if (lb == null || lb < 5 || lb > 120) {
                throw new IllegalArgumentException("BREAKOUT.lookback 必须 5~120");
            }
        }
        CtaConfig.StopLoss sl = cfg.getStopLoss();
        if (sl == null) {
            throw new IllegalArgumentException("stopLoss 不能省略（防止裸跑），fixedPct / trailingPct 至少配一个");
        }
        boolean hasFixed = sl.getFixedPct() != null && sl.getFixedPct() > 0;
        boolean hasTrail = sl.getTrailingPct() != null && sl.getTrailingPct() > 0;
        if (!hasFixed && !hasTrail) {
            throw new IllegalArgumentException("stopLoss 至少配 fixedPct 或 trailingPct 其中一个 (0~50]");
        }
        if (hasFixed && (sl.getFixedPct() <= 0 || sl.getFixedPct() > 50)) {
            throw new IllegalArgumentException("stopLoss.fixedPct 必须 (0, 50]");
        }
        if (hasTrail && (sl.getTrailingPct() <= 0 || sl.getTrailingPct() > 50)) {
            throw new IllegalArgumentException("stopLoss.trailingPct 必须 (0, 50]");
        }
        return cfg;
    }
}
