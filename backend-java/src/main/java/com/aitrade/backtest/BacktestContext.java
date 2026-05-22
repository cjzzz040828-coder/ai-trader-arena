package com.aitrade.backtest;

import com.aitrade.trade.strategy.MarketContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回测专用 MarketContext，代表"模拟到 today 收盘"的市场视图：
 *   - watchlist: 回测开始时定下的代码列表，固定
 *   - priceOf(code): today 当日 close（来自快照 map，由构造器组装）
 *   - bars(code, freq, count): 全量历史里截至 today 的最近 count 根
 *   - openOnDate(code, date): 取指定日期的 open，给 Sandbox 用次日开盘价撮合
 *
 * 每个模拟日 new 一个新实例，本身不可变。
 */
public class BacktestContext extends MarketContext {

    private final LocalDate today;
    private final Map<String, List<Map<String, Object>>> fullBars;

    public BacktestContext(LocalDate today,
                           List<String> codes,
                           Map<String, List<Map<String, Object>>> fullBars,
                           Map<String, String> namesByCode) {
        super(codes, buildSnapshot(today, codes, fullBars, namesByCode), true);
        this.today = today;
        this.fullBars = fullBars;
    }

    @Override
    public List<Map<String, Object>> bars(String code, int frequency, int count) {
        List<Map<String, Object>> all = fullBars.get(code);
        if (all == null || all.isEmpty()) return Collections.emptyList();
        String tStr = today.toString();
        int endIdx = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            String dt = dateOf(all.get(i));
            if (dt != null && dt.compareTo(tStr) <= 0) {
                endIdx = i;
                break;
            }
        }
        if (endIdx < 0) return Collections.emptyList();
        int start = Math.max(0, endIdx - count + 1);
        return all.subList(start, endIdx + 1);
    }

    public LocalDate today() { return today; }

    /** 取指定日期的 open；如果当天没数据返回 null（停牌或非交易日）。 */
    public BigDecimal openOnDate(String code, LocalDate date) {
        List<Map<String, Object>> all = fullBars.get(code);
        if (all == null) return null;
        String dStr = date.toString();
        for (Map<String, Object> bar : all) {
            if (dStr.equals(dateOf(bar))) {
                Object o = bar.get("open");
                if (o == null) o = bar.get("Open");
                if (o == null) return null;
                try { return new BigDecimal(String.valueOf(o)); }
                catch (NumberFormatException e) { return null; }
            }
        }
        return null;
    }

    private static Map<String, Map<String, Object>> buildSnapshot(
            LocalDate today, List<String> codes,
            Map<String, List<Map<String, Object>>> fullBars,
            Map<String, String> namesByCode) {
        String tStr = today.toString();
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (String code : codes) {
            List<Map<String, Object>> all = fullBars.get(code);
            if (all == null || all.isEmpty()) continue;
            // 找当天那根 K 线（只接受 today 当日的 close 作为快照价；停牌跳过）
            for (int i = all.size() - 1; i >= 0; i--) {
                Map<String, Object> bar = all.get(i);
                String dt = dateOf(bar);
                if (dt == null) continue;
                int cmp = dt.compareTo(tStr);
                if (cmp > 0) continue;
                if (cmp < 0) break;
                Object close = bar.get("close");
                if (close == null) close = bar.get("Close");
                if (close == null) break;
                Map<String, Object> row = new HashMap<>();
                row.put("code", code);
                row.put("name", namesByCode == null ? code : namesByCode.getOrDefault(code, code));
                row.put("price", String.valueOf(close));
                out.put(code, row);
                break;
            }
        }
        return out;
    }

    private static String dateOf(Map<String, Object> bar) {
        Object dt = bar.get("datetime");
        if (dt == null) dt = bar.get("date");
        if (dt == null) return null;
        String s = String.valueOf(dt);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }
}
