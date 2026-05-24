package com.aitrade.trade.strategy.script;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.trade.strategy.MarketContext;
import com.aitrade.trade.strategy.Signal;
import com.aitrade.trade.strategy.StrategyExecutor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.script.CompiledScript;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SCRIPT 策略执行器：拿 trader.scriptCode 作为 JS 源码，在沙箱里跑 decide() 返回买/卖信号。
 *
 * 流程：
 *   1. 编译脚本（命中缓存就用旧的）
 *   2. 对 watchlist 每只 code，准备 OHLC 历史 + 持仓上下文 → 喂进 Bindings
 *   3. ScriptEngineFactory.runDecide(...) 跑 decide()
 *   4. 解析返回值（"BUY" / "SELL" / "HOLD" / null）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptStrategyExecutor implements StrategyExecutor {

    /** 跑指标至少要拉这么多根 K 线，太少 RSI/MACD/BOLL/KDJ 都没值。 */
    private static final int MIN_BARS = 60;

    private final PositionMapper positionMapper;
    private final ScriptEngineFactory scriptEngineFactory;

    @Override
    public String strategyType() { return "SCRIPT"; }

    @Override
    public List<Signal> decide(AiTrader trader, MarketContext ctx) {
        return decideWith(trader, ctx, loadPositionsByCode(trader.getId()));
    }

    /** 回测引擎入口：持仓快照外部传入，不查 DB。 */
    public List<Signal> decideWith(AiTrader trader, MarketContext ctx, Map<String, Position> positionByCode) {
        String code = trader.getScriptCode();
        if (code == null || code.isBlank()) {
            log.warn("[strategy] SCRIPT trader {} has empty script, skip", trader.getId());
            return List.of();
        }
        CompiledScript compiled;
        try {
            compiled = scriptEngineFactory.compile(code);
        } catch (Exception e) {
            log.warn("[strategy] SCRIPT trader {} compile failed: {}", trader.getId(), e.getMessage());
            return List.of();
        }

        ScriptApi api = new ScriptApi("trader-" + trader.getId());
        List<Signal> signals = new ArrayList<>();
        int scanned = 0, buys = 0, sells = 0, errs = 0;

        for (String stockCode : ctx.watchlist()) {
            BigDecimal price = ctx.priceOf(stockCode);
            if (price == null || price.signum() <= 0) continue;
            List<Map<String, Object>> bars = ctx.bars(stockCode, 9, MIN_BARS);
            if (bars.size() < MIN_BARS) continue;
            scanned++;

            double[][] ohlcv = extractOhlcv(bars);
            Position pos = positionByCode.get(stockCode);
            int holding = pos == null ? 0 : pos.getAmount();
            int sellable = pos == null ? 0 : Math.max(0, pos.getAmount() - (pos.getFrozenAmount() == null ? 0 : pos.getFrozenAmount()));
            double costPrice = (pos == null || pos.getCostPrice() == null) ? 0 : pos.getCostPrice().doubleValue();

            Map<String, Object> inputs = new HashMap<>();
            inputs.put("api", api);
            inputs.put("open", ohlcv[0]);
            inputs.put("close", ohlcv[1]);
            inputs.put("high", ohlcv[2]);
            inputs.put("low", ohlcv[3]);
            inputs.put("vol", ohlcv[4]);
            inputs.put("price", price.doubleValue());
            inputs.put("holding", holding);
            inputs.put("costPrice", costPrice);
            inputs.put("code", stockCode);
            inputs.put("name", ctx.nameOf(stockCode));

            String result;
            try {
                Object ret = scriptEngineFactory.runDecide(compiled, inputs);
                result = ret == null ? "HOLD" : String.valueOf(ret).trim().toUpperCase();
            } catch (Exception e) {
                errs++;
                if (errs <= 3) {
                    log.warn("[strategy] SCRIPT trader {} {} run failed: {}", trader.getId(), stockCode, e.getMessage());
                }
                continue;
            }

            if ("BUY".equals(result) && holding == 0) {
                signals.add(Signal.buy(stockCode, "SCRIPT 决策"));
                buys++;
            } else if ("SELL".equals(result) && sellable > 0) {
                signals.add(Signal.sell(stockCode, "SCRIPT 决策"));
                sells++;
            }
        }
        log.info("[strategy] trader {} ({}) SCRIPT scan {} stocks, {} buy {} sell, errs {}",
                trader.getId(), trader.getName(), scanned, buys, sells, errs);
        return signals;
    }

    /** 返回 [open, close, high, low, vol] 五条 double[]。 */
    private double[][] extractOhlcv(List<Map<String, Object>> bars) {
        int n = bars.size();
        double[] open = new double[n];
        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] vol = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> bar = bars.get(i);
            open[i] = parseD(bar.get("open"), bar.get("Open"));
            close[i] = parseD(bar.get("close"), bar.get("Close"));
            high[i] = parseD(bar.get("high"), bar.get("High"));
            low[i] = parseD(bar.get("low"), bar.get("Low"));
            vol[i] = parseD(bar.get("vol"), bar.get("Vol"));
            if (high[i] == 0 && close[i] > 0) high[i] = close[i];
            if (low[i] == 0 && close[i] > 0) low[i] = close[i];
            if (open[i] == 0 && close[i] > 0) open[i] = close[i];
        }
        return new double[][]{open, close, high, low, vol};
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
}
