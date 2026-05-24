package com.aitrade.trade.strategy.indicator;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户自定义指标组合策略执行器。从 trader.indicatorConfigJson 解析配置，
 * 对每只 watchlist 股票计算配置的指标值，按规则触发买/卖信号。
 *
 * 当前覆盖：RSI / MACD / BOLL / KDJ。买卖规则可用阈值（&lt; &gt; ==）或交叉（CROSS_UP / CROSS_DOWN）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndicatorStrategyExecutor implements StrategyExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KNOWN_TYPES = Set.of("RSI", "MACD", "BOLL", "KDJ");
    private static final Set<String> KNOWN_OPS = Set.of("<", ">", "<=", ">=", "==", "CROSS_UP", "CROSS_DOWN");

    private final PositionMapper positionMapper;

    @Override
    public String strategyType() { return "INDICATOR"; }

    @Override
    public List<Signal> decide(AiTrader trader, MarketContext ctx) {
        return decideWith(trader, ctx, loadPositionsByCode(trader.getId()));
    }

    /** 回测引擎入口：持仓快照外部传入，不查 DB。 */
    public List<Signal> decideWith(AiTrader trader, MarketContext ctx, Map<String, Position> positionByCode) {
        IndicatorConfig cfg = parseConfig(trader);
        if (cfg == null) return List.of();

        int needBars = computeBarsNeeded(cfg);
        List<Signal> signals = new ArrayList<>();
        int scanned = 0, buys = 0, sells = 0;

        for (String code : ctx.watchlist()) {
            BigDecimal price = ctx.priceOf(code);
            if (price == null || price.signum() <= 0) continue;
            List<Map<String, Object>> bars = ctx.bars(code, 9, needBars);
            if (bars.size() < needBars) continue;
            scanned++;

            double[][] ohlc = extractOhlc(bars);
            double[] closes = ohlc[0];
            double[] highs = ohlc[1];
            double[] lows = ohlc[2];

            Map<String, double[]> series = computeAllIndicators(cfg, closes, highs, lows);
            if (series == null) continue;

            boolean buy = evalRules(cfg.getBuyRules(), cfg.getLogic(), series);
            boolean sell = evalRules(cfg.getSellRules(), cfg.getLogic(), series);

            Position pos = positionByCode.get(code);
            int holding = pos == null ? 0 : pos.getAmount();
            int sellable = pos == null ? 0 : Math.max(0, pos.getAmount() - (pos.getFrozenAmount() == null ? 0 : pos.getFrozenAmount()));

            if (buy && holding == 0) {
                signals.add(Signal.buy(code, "INDICATOR 买入规则触发"));
                buys++;
            } else if (sell && sellable > 0) {
                signals.add(Signal.sell(code, "INDICATOR 卖出规则触发"));
                sells++;
            }
        }
        log.info("[strategy] trader {} ({}) INDICATOR scan {} stocks, {} buy {} sell",
                trader.getId(), trader.getName(), scanned, buys, sells);
        return signals;
    }

    // ---------------- 配置解析 ----------------

    private IndicatorConfig parseConfig(AiTrader trader) {
        String json = trader.getIndicatorConfigJson();
        if (json == null || json.isBlank()) {
            log.warn("[strategy] INDICATOR trader {} has empty config, skip", trader.getId());
            return null;
        }
        try {
            IndicatorConfig cfg = JSON.readValue(json, IndicatorConfig.class);
            if (cfg.getIndicators() == null || cfg.getIndicators().isEmpty()) return null;
            return cfg;
        } catch (Exception e) {
            log.warn("[strategy] INDICATOR trader {} parse config failed: {}", trader.getId(), e.getMessage());
            return null;
        }
    }

    /** 取最大 lookback 所需 bar 数（取大头：MACD slow+signal、BOLL period、RSI period、KDJ n + 一些缓冲）。 */
    private int computeBarsNeeded(IndicatorConfig cfg) {
        int max = 30;
        for (IndicatorConfig.IndicatorDef d : cfg.getIndicators()) {
            int need = switch (d.getType() == null ? "" : d.getType().toUpperCase()) {
                case "RSI" -> intParam(d, "period", 14) + 5;
                case "MACD" -> intParam(d, "slow", 26) + intParam(d, "signal", 9) + 5;
                case "BOLL" -> intParam(d, "period", 20) + 5;
                case "KDJ" -> intParam(d, "n", 9) + intParam(d, "k", 3) + intParam(d, "d", 3) + 5;
                default -> 0;
            };
            if (need > max) max = need;
        }
        return Math.max(max, 30);
    }

    // ---------------- 指标计算分发 ----------------

    /** 算出所有配置的指标 → Map<"id.field", double[]>。任何一项失败返回 null（外层会跳过该股票）。 */
    private Map<String, double[]> computeAllIndicators(IndicatorConfig cfg,
                                                       double[] closes, double[] highs, double[] lows) {
        Map<String, double[]> out = new HashMap<>();
        for (IndicatorConfig.IndicatorDef d : cfg.getIndicators()) {
            String id = d.getId();
            if (id == null || id.isBlank()) return null;
            String type = d.getType() == null ? "" : d.getType().toUpperCase();
            switch (type) {
                case "RSI" -> {
                    int p = intParam(d, "period", 14);
                    out.put(id + ".value", Indicators.rsi(closes, p));
                }
                case "MACD" -> {
                    int f = intParam(d, "fast", 12);
                    int s = intParam(d, "slow", 26);
                    int sig = intParam(d, "signal", 9);
                    Indicators.Macd m = Indicators.macd(closes, f, s, sig);
                    out.put(id + ".macd", m.macd());
                    out.put(id + ".signal", m.signal());
                    out.put(id + ".hist", m.hist());
                }
                case "BOLL" -> {
                    int p = intParam(d, "period", 20);
                    double k = doubleParam(d, "k", 2.0);
                    Indicators.Boll b = Indicators.boll(closes, p, k);
                    out.put(id + ".upper", b.upper());
                    out.put(id + ".middle", b.middle());
                    out.put(id + ".lower", b.lower());
                }
                case "KDJ" -> {
                    int n = intParam(d, "n", 9);
                    int k = intParam(d, "k", 3);
                    int dp = intParam(d, "d", 3);
                    Indicators.Kdj kdj = Indicators.kdj(highs, lows, closes, n, k, dp);
                    out.put(id + ".k", kdj.k());
                    out.put(id + ".d", kdj.d());
                    out.put(id + ".j", kdj.j());
                }
                default -> {
                    log.debug("[strategy] unknown indicator type {} in INDICATOR config, skip", type);
                }
            }
        }
        // 同时把"价格"暴露成一个虚拟指标，用于布林带边界穿越等规则
        out.put("_close.value", closes);
        return out;
    }

    // ---------------- 规则求值 ----------------

    private boolean evalRules(List<IndicatorConfig.Rule> rules, String logic, Map<String, double[]> series) {
        if (rules == null || rules.isEmpty()) return false;
        boolean isOr = "OR".equalsIgnoreCase(logic);
        for (IndicatorConfig.Rule r : rules) {
            boolean ok = evalOne(r, series);
            if (isOr) {
                if (ok) return true;
            } else {
                if (!ok) return false;
            }
        }
        return !isOr;
    }

    private boolean evalOne(IndicatorConfig.Rule r, Map<String, double[]> series) {
        if (r == null || r.getOp() == null) return false;
        String op = r.getOp().toUpperCase();
        if (!KNOWN_OPS.contains(op) && !KNOWN_OPS.contains(r.getOp())) return false;

        double[] left = resolveSeries(r.getLeft(), series);
        if (left == null || left.length < 2) return false;
        int i = left.length - 1;

        boolean isCross = op.equals("CROSS_UP") || op.equals("CROSS_DOWN");
        if (isCross) {
            double[] right = resolveSeries(asString(r.getRight()), series);
            if (right == null || right.length < 2) return false;
            if (Double.isNaN(left[i]) || Double.isNaN(left[i - 1]) ||
                    Double.isNaN(right[i]) || Double.isNaN(right[i - 1])) return false;
            if (op.equals("CROSS_UP")) {
                return left[i - 1] <= right[i - 1] && left[i] > right[i];
            } else {
                return left[i - 1] >= right[i - 1] && left[i] < right[i];
            }
        }

        // 阈值规则：右值可以是常量或另一指标的当前值
        double leftV = left[i];
        if (Double.isNaN(leftV)) return false;
        Double rightV = resolveScalar(r.getRight(), series);
        if (rightV == null || Double.isNaN(rightV)) return false;
        return switch (op) {
            case "<" -> leftV < rightV;
            case ">" -> leftV > rightV;
            case "<=" -> leftV <= rightV;
            case ">=" -> leftV >= rightV;
            case "==" -> Math.abs(leftV - rightV) < 1e-9;
            default -> false;
        };
    }

    private double[] resolveSeries(String key, Map<String, double[]> series) {
        if (key == null) return null;
        return series.get(key.trim());
    }

    /** 右操作数：字符串视为指标引用取当前值，数字视为常量。 */
    private Double resolveScalar(Object right, Map<String, double[]> series) {
        if (right == null) return null;
        if (right instanceof Number n) return n.doubleValue();
        String s = right.toString().trim();
        if (s.isEmpty()) return null;
        double[] arr = series.get(s);
        if (arr != null && arr.length > 0) return arr[arr.length - 1];
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private String asString(Object v) { return v == null ? null : v.toString(); }

    // ---------------- 辅助 ----------------

    private static int intParam(IndicatorConfig.IndicatorDef d, String key, int def) {
        if (d.getParams() == null) return def;
        Number n = d.getParams().get(key);
        return n == null ? def : n.intValue();
    }

    private static double doubleParam(IndicatorConfig.IndicatorDef d, String key, double def) {
        if (d.getParams() == null) return def;
        Number n = d.getParams().get(key);
        return n == null ? def : n.doubleValue();
    }

    private double[][] extractOhlc(List<Map<String, Object>> bars) {
        int n = bars.size();
        double[] closes = new double[n];
        double[] highs = new double[n];
        double[] lows = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> bar = bars.get(i);
            closes[i] = parseD(bar.get("close"), bar.get("Close"));
            highs[i] = parseD(bar.get("high"), bar.get("High"));
            lows[i] = parseD(bar.get("low"), bar.get("Low"));
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

    private Map<String, Position> loadPositionsByCode(Long traderId) {
        Map<String, Position> map = new HashMap<>();
        for (Position p : positionMapper.selectList(new QueryWrapper<Position>()
                .eq("trader_id", traderId).gt("amount", 0))) {
            map.put(p.getStockCode(), p);
        }
        return map;
    }

    // ---------------- 静态校验（给 TraderService 用） ----------------

    /** 解析+基本校验，失败抛 IllegalArgumentException(message)。供 Service 在保存前调用。 */
    public static IndicatorConfig parseAndValidate(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("indicatorConfigJson 不能为空");
        }
        IndicatorConfig cfg;
        try {
            cfg = JSON.readValue(json, IndicatorConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("indicatorConfigJson 格式非法: " + e.getMessage());
        }
        if (cfg.getIndicators() == null || cfg.getIndicators().isEmpty()) {
            throw new IllegalArgumentException("至少需要 1 个指标");
        }
        if (cfg.getBuyRules() == null || cfg.getBuyRules().isEmpty()) {
            throw new IllegalArgumentException("至少需要 1 条买入规则");
        }
        if (cfg.getSellRules() == null || cfg.getSellRules().isEmpty()) {
            throw new IllegalArgumentException("至少需要 1 条卖出规则");
        }
        for (IndicatorConfig.IndicatorDef d : cfg.getIndicators()) {
            if (d.getId() == null || d.getId().isBlank()) {
                throw new IllegalArgumentException("指标 id 不能为空");
            }
            String t = d.getType() == null ? "" : d.getType().toUpperCase();
            if (!KNOWN_TYPES.contains(t)) {
                throw new IllegalArgumentException("不支持的指标类型: " + d.getType() + "（仅支持 " + KNOWN_TYPES + "）");
            }
        }
        for (IndicatorConfig.Rule r : concat(cfg.getBuyRules(), cfg.getSellRules())) {
            if (r.getOp() == null || !KNOWN_OPS.contains(r.getOp().toUpperCase())) {
                throw new IllegalArgumentException("规则操作符非法: " + (r.getOp()) + "（仅支持 " + KNOWN_OPS + "）");
            }
            if (r.getLeft() == null || r.getLeft().isBlank()) {
                throw new IllegalArgumentException("规则左操作数不能为空");
            }
            if (r.getRight() == null) {
                throw new IllegalArgumentException("规则右操作数不能为空");
            }
        }
        return cfg;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }
}
