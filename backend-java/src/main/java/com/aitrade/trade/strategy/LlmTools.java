package com.aitrade.trade.strategy;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.entity.TradeOrder;
import com.aitrade.gateway.PythonGatewayClient;
import com.aitrade.gateway.dto.SnapshotResponse;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.mapper.TradeOrderMapper;
import com.aitrade.trade.OrderService;
import com.aitrade.trade.dto.OrderVO;
import com.aitrade.trade.dto.PlaceOrderReq;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Executor 使用的工具实现。
 * 工具结果会被序列化为 JSON 回灌给 LLM，所以 Map 用 LinkedHashMap 保证字段顺序、HashMap 也可以。
 * 失败统一返回 {ok: false, error: ...}，成功结构因工具而异（含 ok: true 时表示明确成功）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmTools {

    private final OrderService orderService;
    private final TradeOrderMapper tradeOrderMapper;
    private final PositionMapper positionMapper;
    private final PythonGatewayClient gateway;

    /** 隐式市价单的滑点（千分之）。LLM 不传 price 时，BUY 加 / SELL 减此比例，确保下个 tick 必成交。 */
    private static final BigDecimal IMPLICIT_SLIPPAGE = new BigDecimal("0.01"); // 1%

    public Map<String, Object> getStockAnalysis(AiTrader trader, MarketContext ctx, Map<String, Object> args) {
        String code = strVal(args.get("code"));
        if (code == null) return err("code 必填");
        if (!ctx.watchlist().contains(code)) return err("code " + code + " 不在 watchlist 内");

        List<Map<String, Object>> bars = ctx.bars(code, 9, 25);
        if (bars.size() < 5) return err("日 K 线数据不足: " + bars.size() + " 根");

        double[] closes = extractDoubles(bars, "close");
        double[] vols = extractDoubles(bars, "vol");
        double currentPrice = ctx.priceOf(code) == null ? closes[closes.length - 1] : ctx.priceOf(code).doubleValue();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", code);
        r.put("name", ctx.nameOf(code));
        r.put("current_price", round(currentPrice, 3));
        r.put("ma5", avgTailRounded(closes, 5));
        r.put("ma10", avgTailRounded(closes, 10));
        r.put("ma20", avgTailRounded(closes, 20));

        double lastClose = closes[closes.length - 1];
        double price5d = closes.length >= 6 ? closes[closes.length - 6] : lastClose;
        double price20d = closes.length >= 21 ? closes[closes.length - 21] : lastClose;
        r.put("change_5d_pct", round((lastClose - price5d) / price5d * 100, 2));
        r.put("change_20d_pct", round((lastClose - price20d) / price20d * 100, 2));

        double todayVol = vols.length > 0 ? vols[vols.length - 1] : 0;
        double avgVol5 = avgTail(vols, 5);
        r.put("vol_ratio_vs_5d_avg", avgVol5 > 0 ? round(todayVol / avgVol5, 2) : 0);

        // 最近 10 根 OHLC
        int start = Math.max(0, bars.size() - 10);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = start; i < bars.size(); i++) {
            Map<String, Object> b = bars.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", String.valueOf(b.getOrDefault("datetime", "?")).substring(0, Math.min(10, String.valueOf(b.getOrDefault("datetime", "?")).length())));
            row.put("open", b.get("open"));
            row.put("close", b.get("close"));
            row.put("high", b.get("high"));
            row.put("low", b.get("low"));
            row.put("vol", b.get("vol"));
            recent.add(row);
        }
        r.put("recent_10_bars", recent);
        return r;
    }

    public Map<String, Object> getMinuteChart(AiTrader trader, MarketContext ctx, Map<String, Object> args) {
        String code = strVal(args.get("code"));
        if (code == null) return err("code 必填");
        if (!ctx.watchlist().contains(code)) return err("code " + code + " 不在 watchlist 内");

        List<Map<String, Object>> bars = ctx.bars(code, 8, 240);
        String todayPrefix = LocalDate.now().toString();
        List<Map<String, Object>> samples = new ArrayList<>();
        int counter = 0;
        for (Map<String, Object> b : bars) {
            String dt = String.valueOf(b.get("datetime"));
            if (!dt.startsWith(todayPrefix)) continue;
            if (counter++ % 15 != 0) continue;  // 每 15 分钟采样
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", dt.substring(11, Math.min(16, dt.length())));
            row.put("close", b.get("close"));
            row.put("vol", b.get("vol"));
            samples.add(row);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", code);
        r.put("name", ctx.nameOf(code));
        r.put("sampled_every", "15min");
        r.put("samples", samples);
        return r;
    }

    public Map<String, Object> getRecentTrades(AiTrader trader, MarketContext ctx, Map<String, Object> args) {
        int limit = 20;
        Object l = args.get("limit");
        if (l instanceof Number) limit = Math.max(1, Math.min(50, ((Number) l).intValue()));

        List<TradeOrder> orders = tradeOrderMapper.selectList(new QueryWrapper<TradeOrder>()
                .eq("trader_id", trader.getId())
                .eq("status", "FILLED")
                .orderByDesc("filled_at")
                .last("LIMIT " + limit));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TradeOrder o : orders) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", o.getFilledAt() == null ? "?" : o.getFilledAt().toString());
            row.put("code", o.getStockCode());
            row.put("side", o.getSide());
            row.put("amount", o.getAmount());
            row.put("price", o.getFilledPrice() == null ? "?" : o.getFilledPrice().toPlainString());
            rows.add(row);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", rows.size());
        r.put("trades", rows);
        return r;
    }

    public Map<String, Object> placeOrder(AiTrader trader, MarketContext ctx, Map<String, Object> args) {
        String code = strVal(args.get("code"));
        String side = strVal(args.get("side"));
        Object amountArg = args.get("amount");
        Object priceArg = args.get("price");

        if (code == null || !ctx.watchlist().contains(code)) {
            return err("code 必填且必须在 watchlist 内");
        }
        if (side == null) return err("side 必填");
        side = side.toUpperCase();
        if (!"BUY".equals(side) && !"SELL".equals(side)) return err("side 必须为 BUY 或 SELL");

        if (!(amountArg instanceof Number)) return err("amount 必须是整数");
        int amount = ((Number) amountArg).intValue();
        if (amount <= 0 || amount % 100 != 0) return err("amount 必须是 100 的整数倍且大于 0");

        BigDecimal price;
        boolean priceExplicit;
        if (priceArg instanceof Number) {
            price = new BigDecimal(priceArg.toString());
            priceExplicit = true;
        } else if (priceArg instanceof String && !((String) priceArg).isBlank()) {
            try {
                price = new BigDecimal((String) priceArg);
            } catch (NumberFormatException e) {
                return err("price 格式错误");
            }
            priceExplicit = true;
        } else {
            // 隐式市价单：拿最新快照（ctx 的快照是决策开始时的，多轮推理后可能已经几分钟前）+ 加 1% 滑点。
            // MatchEngine 撮合规则是 BUY 限价 ≥ 现价 / SELL 限价 ≤ 现价 才成交，纯现价单遇到上行行情立刻就卡 PENDING。
            BigDecimal fresh = freshPriceOf(code);
            if (fresh == null) fresh = ctx.priceOf(code);
            if (fresh == null) return err("未指定 price 且无法获取最新报价");
            BigDecimal slip = fresh.multiply(IMPLICIT_SLIPPAGE);
            price = "BUY".equals(side) ? fresh.add(slip) : fresh.subtract(slip);
            price = price.setScale(2, RoundingMode.HALF_UP);
            if (price.signum() <= 0) return err("price 计算后非正");
            priceExplicit = false;
        }
        if (price.signum() <= 0) return err("price 必须大于 0");

        if (orderService.hasPending(trader.getId(), code)) {
            return err("同一股票已有 PENDING 单，请等待成交或择机重试");
        }
        if ("SELL".equals(side) && orderService.boughtToday(trader.getId(), code)) {
            return err("T+1 限制：当日买入的股票当日不能卖出");
        }
        if ("SELL".equals(side)) {
            Position pos = positionMapper.selectOne(new QueryWrapper<Position>()
                    .eq("trader_id", trader.getId()).eq("stock_code", code));
            int sellable = pos == null ? 0 : pos.getAmount() - nzi(pos.getFrozenAmount());
            if (sellable < amount) return err("可卖持仓不足: 持有可卖 " + sellable + " 股");
        }

        PlaceOrderReq req = new PlaceOrderReq();
        req.setTraderId(trader.getId());
        req.setStockCode(code);
        req.setSide(side);
        req.setPrice(price);
        req.setAmount(amount);
        try {
            OrderVO ord = orderService.place(req, trader.getUserId());
            log.info("[strategy-llm-tool] trader {} place_order {} {} x {} @{} -> order#{}",
                    trader.getId(), side, code, amount, price, ord.getId());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("order_id", ord.getId());
            r.put("status", ord.getStatus());
            r.put("message", String.format("%s %s x %d @%s 已挂单（等待下个 10s tick 撮合）",
                    side, code, amount, price.toPlainString()));
            if (priceExplicit) {
                String warning = buildPriceWarning(side, price, ctx.priceOf(code));
                if (warning != null) r.put("warning", warning);
            }
            return r;
        } catch (Exception e) {
            return err("下单失败: " + e.getMessage());
        }
    }

    /** 从网关重新拉一次单只票的快照，失败返回 null 由调用方走 fallback。 */
    private BigDecimal freshPriceOf(String code) {
        try {
            SnapshotResponse snap = gateway.snapshot(code);
            if (snap == null || snap.getData() == null) return null;
            for (Map<String, Object> row : snap.getData()) {
                Object c = row.get("code");
                if (c == null || !code.equals(String.valueOf(c))) continue;
                Object p = row.get("price");
                if (p == null) return null;
                return new BigDecimal(String.valueOf(p));
            }
            return null;
        } catch (Exception e) {
            log.warn("[strategy-llm-tool] freshPriceOf {} failed: {}", code, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> getPendingOrders(AiTrader trader, MarketContext ctx, Map<String, Object> args) {
        List<TradeOrder> orders = tradeOrderMapper.selectList(new QueryWrapper<TradeOrder>()
                .eq("trader_id", trader.getId())
                .eq("status", "PENDING")
                .orderByDesc("created_at"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TradeOrder o : orders) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order_id", o.getId());
            row.put("code", o.getStockCode());
            row.put("side", o.getSide());
            row.put("amount", o.getAmount());
            row.put("price", o.getPrice() == null ? "?" : o.getPrice().toPlainString());
            row.put("created_at", o.getCreatedAt() == null ? "?" : o.getCreatedAt().toString());
            rows.add(row);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", rows.size());
        r.put("orders", rows);
        return r;
    }

    public Map<String, Object> cancelOrder(AiTrader trader, MarketContext ctx, Map<String, Object> args) {
        Object idArg = args.get("order_id");
        if (!(idArg instanceof Number)) return err("order_id 必填且必须是整数");
        long orderId = ((Number) idArg).longValue();
        try {
            OrderVO ord = orderService.cancel(orderId, trader.getUserId());
            log.info("[strategy-llm-tool] trader {} cancel_order #{} -> ok", trader.getId(), orderId);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("order_id", ord.getId());
            r.put("status", ord.getStatus());
            r.put("message", "订单 #" + orderId + " 已撤销，冻结资金已释放");
            return r;
        } catch (Exception e) {
            return err("撤单失败: " + e.getMessage());
        }
    }

    /**
     * 给 LLM prompt 使用的全盘技术快照（不是工具，不走 HTTP）。
     * 每只股票返回 6 个核心指标：现价 / 当日涨跌 / MA5 / MA10 / MA20 / 5 日累计涨跌 / 量比。
     * 数据不足时对应字段返回 "?" 而不抛异常。
     *
     * 设计目的：让 LLM 在 buildInitialUserMessage 阶段就拿到全盘技术视图，
     * 凭这些指标筛 3~5 只深查 get_stock_analysis / get_minute_chart，
     * 而不是盲目挨个工具调用造成预算爆炸。
     */
    public Map<String, Object> getQuickSnapshot(String code, MarketContext ctx) {
        Map<String, Object> r = new LinkedHashMap<>();
        BigDecimal latest = ctx.priceOf(code);
        r.put("price", latest == null ? "?" : latest.toPlainString());

        Map<String, Object> snap = ctx.snapshots().get(code);
        r.put("change_pct", snap == null ? "?" : snap.getOrDefault("change_pct", "?"));

        List<Map<String, Object>> bars = ctx.bars(code, 9, 25);
        if (bars.size() < 5) {
            r.put("ma5", "?");
            r.put("ma10", "?");
            r.put("ma20", "?");
            r.put("change_5d_pct", "?");
            r.put("vol_ratio", "?");
            return r;
        }

        double[] closes = extractDoubles(bars, "close");
        double[] vols = extractDoubles(bars, "vol");
        r.put("ma5", avgTailRounded(closes, 5));
        r.put("ma10", closes.length >= 10 ? avgTailRounded(closes, 10) : "?");
        r.put("ma20", closes.length >= 20 ? avgTailRounded(closes, 20) : "?");

        double lastClose = closes[closes.length - 1];
        if (closes.length >= 6) {
            double price5d = closes[closes.length - 6];
            r.put("change_5d_pct", price5d > 0 ? round((lastClose - price5d) / price5d * 100, 2) : "?");
        } else {
            r.put("change_5d_pct", "?");
        }

        double todayVol = vols.length > 0 ? vols[vols.length - 1] : 0;
        double avgVol5 = avgTail(vols, 5);
        r.put("vol_ratio", avgVol5 > 0 ? round(todayVol / avgVol5, 2) : "?");
        return r;
    }

    // ---------------- helpers ----------------

    /**
     * 限价单挂价偏离现价超过 ±1% 时返回提示文本，否则 null。
     * 撮合规则（MatchEngine）：BUY 仅在 挂价 ≥ 现价 时成交；SELL 仅在 挂价 ≤ 现价 时成交。
     * 所以反向偏离（BUY 低于、SELL 高于）= 等回踩，往往挂着不成交。同向偏离（BUY 高于、
     * SELL 低于）安全，差价会在成交时回流到余额，不警告。
     */
    private static String buildPriceWarning(String side, BigDecimal price, BigDecimal latest) {
        if (latest == null || latest.signum() <= 0) return null;
        BigDecimal deviationPct = price.subtract(latest)
                .multiply(BigDecimal.valueOf(100))
                .divide(latest, 2, RoundingMode.HALF_UP);
        BigDecimal threshold = BigDecimal.ONE;  // 1%
        boolean buyTooLow = "BUY".equals(side) && deviationPct.compareTo(threshold.negate()) < 0;
        boolean sellTooHigh = "SELL".equals(side) && deviationPct.compareTo(threshold) > 0;
        if (!buyTooLow && !sellTooHigh) return null;
        String dirHint = "BUY".equals(side) ? "回落到挂价" : "上涨到挂价";
        String fixHint = "BUY".equals(side) ? "≥现价" : "≤现价";
        return String.format(
                "挂价 %s 偏离现价 %s（%s%%），按限价单规则需现价%s才成交，期间资金/持仓被冻结；"
                + "如要立即成交请撤单后不传 price 或重挂%s。",
                price.toPlainString(), latest.toPlainString(),
                deviationPct.toPlainString(), dirHint, fixHint);
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", false);
        r.put("error", msg);
        return r;
    }

    private static String strVal(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer nzi(Integer v) { return v == null ? 0 : v; }

    private static double[] extractDoubles(List<Map<String, Object>> bars, String key) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            Object v = bars.get(i).get(key);
            if (v == null) { out[i] = 0; continue; }
            try { out[i] = Double.parseDouble(String.valueOf(v)); }
            catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    private static double avgTail(double[] arr, int n) {
        if (arr.length < n || n <= 0) return 0;
        double sum = 0;
        for (int i = arr.length - n; i < arr.length; i++) sum += arr[i];
        return sum / n;
    }

    private static double avgTailRounded(double[] arr, int n) {
        return round(avgTail(arr, n), 3);
    }

    private static double round(double v, int digits) {
        return BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }
}
