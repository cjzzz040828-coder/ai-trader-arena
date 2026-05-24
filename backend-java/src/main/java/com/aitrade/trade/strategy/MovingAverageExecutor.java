package com.aitrade.trade.strategy;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.mapper.PositionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MA 双均线策略：
 *   bars 按时间升序，最后一根为最近一个交易日的 K 线。
 *   把 snapshot 的 price 当作"今日的临时收盘价"拼到 bars 后面，
 *   比较昨日 MA(short/long) 和今日 MA(short/long) 的关系判断金叉/死叉。
 *
 *   金叉（昨日 short ≤ long 且 今日 short > long）且**无持仓** → BUY
 *   死叉（昨日 short ≥ long 且 今日 short < long）且**有持仓** → SELL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MovingAverageExecutor implements StrategyExecutor {

    private final PositionMapper positionMapper;

    @Override
    public String strategyType() { return "MA"; }

    @Override
    public List<Signal> decide(AiTrader trader, MarketContext ctx) {
        return decideWith(trader, ctx, loadPositionsByCode(trader.getId()));
    }

    /** 给定持仓快照做决策，不查 DB。回测引擎用这个入口。 */
    public List<Signal> decideWith(AiTrader trader, MarketContext ctx, Map<String, Position> positionByCode) {
        int sh = trader.getMaShort() == null ? 5 : trader.getMaShort();
        int lo = trader.getMaLong() == null ? 20 : trader.getMaLong();
        if (sh >= lo) {
            log.warn("[strategy] MA trader {} invalid params short={} long={}, skip", trader.getId(), sh, lo);
            return List.of();
        }

        List<Signal> signals = new ArrayList<>();
        int scanned = 0, buys = 0, sells = 0;
        for (String code : ctx.watchlist()) {
            BigDecimal price = ctx.priceOf(code);
            if (price == null || price.signum() <= 0) continue;
            List<Map<String, Object>> bars = ctx.bars(code, 9, Math.max(lo + 5, 30));
            if (bars.size() < lo + 1) continue;
            scanned++;

            double[] closes = extractCloses(bars);
            if (closes.length < lo + 1) continue;

            double prevShort = avgTail(closes, sh, 1);   // 不含今日的最近 short 个 close
            double prevLong = avgTail(closes, lo, 1);
            double todayPrice = price.doubleValue();
            double currShort = avgTailWithToday(closes, sh, todayPrice);
            double currLong = avgTailWithToday(closes, lo, todayPrice);

            boolean goldCross = prevShort <= prevLong && currShort > currLong;
            boolean deathCross = prevShort >= prevLong && currShort < currLong;
            Position pos = positionByCode.get(code);
            int holding = pos == null ? 0 : pos.getAmount();
            int sellable = pos == null ? 0 : Math.max(0, pos.getAmount() - (pos.getFrozenAmount() == null ? 0 : pos.getFrozenAmount()));

            if (goldCross && holding == 0) {
                signals.add(Signal.buy(code, String.format("金叉 MA%d=%.3f > MA%d=%.3f", sh, currShort, lo, currLong)));
                buys++;
            } else if (deathCross && sellable > 0) {
                signals.add(Signal.sell(code, String.format("死叉 MA%d=%.3f < MA%d=%.3f", sh, currShort, lo, currLong)));
                sells++;
            }
        }
        log.info("[strategy] trader {} ({}) MA scan {} stocks, {} buy {} sell",
                trader.getId(), trader.getName(), scanned, buys, sells);
        return signals;
    }

    private Map<String, Position> loadPositionsByCode(Long traderId) {
        Map<String, Position> map = new HashMap<>();
        for (Position p : positionMapper.selectList(new QueryWrapper<Position>()
                .eq("trader_id", traderId).gt("amount", 0))) {
            map.put(p.getStockCode(), p);
        }
        return map;
    }

    private double[] extractCloses(List<Map<String, Object>> bars) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            Object c = bars.get(i).get("close");
            if (c == null) c = bars.get(i).get("Close");
            try {
                out[i] = c == null ? 0.0 : Double.parseDouble(String.valueOf(c));
            } catch (NumberFormatException e) {
                out[i] = 0.0;
            }
        }
        return out;
    }

    /** 取 closes 末尾的 n 个值，向前偏移 offset 位（offset=1 表示不含最末一根）的平均。 */
    private double avgTail(double[] closes, int n, int offset) {
        int end = closes.length - offset;
        int start = end - n;
        if (start < 0) return 0.0;
        double sum = 0;
        for (int i = start; i < end; i++) sum += closes[i];
        return sum / n;
    }

    /**
     * 取最近 n 个 close 的均值，但用 todayPrice 替换末尾那根（=bars 最后一根=今日 partial close）。
     *
     * 调用约定：closes 末尾是"今日"那根（实盘 mootdx 日 K 盘中即为今日 partial，回测里 BacktestContext.bars
     * 也明确含 today）。所以正确算法是：取 [length-n .. length-2] 共 n-1 个老 close + todayPrice = n 个值。
     * 不能用"追加"的方式（length-n+1 .. length-1 + todayPrice），那样会把今日 close 算两次。
     */
    private double avgTailWithToday(double[] closes, int n, double todayPrice) {
        if (closes.length < n) return 0.0;
        double sum = todayPrice;
        for (int i = closes.length - n; i < closes.length - 1; i++) sum += closes[i];
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP).doubleValue();
    }
}
