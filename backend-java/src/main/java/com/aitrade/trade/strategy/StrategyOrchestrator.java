package com.aitrade.trade.strategy;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.trade.OrderService;
import com.aitrade.trade.dto.PlaceOrderReq;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 StrategyExecutor 返回的 Signal 落成实际订单。
 *   - 路由 strategy_type → Executor
 *   - 防 MA 边沿/LLM 反复：同 trader+code 有 PENDING 则跳过
 *   - T+1 兜底：SELL 信号且同 code 当日有 FILLED 的 BUY 则跳过
 *   - 仓位规则：BUY 用可用余额 10%（向下取整百股），SELL 全仓
 */
@Slf4j
@Component
public class StrategyOrchestrator {

    private static final BigDecimal BUY_FRACTION = new BigDecimal("0.10");

    private final OrderService orderService;
    private final PositionMapper positionMapper;
    private final Map<String, StrategyExecutor> executorsByType;

    public StrategyOrchestrator(OrderService orderService,
                                PositionMapper positionMapper,
                                List<StrategyExecutor> executors) {
        this.orderService = orderService;
        this.positionMapper = positionMapper;
        Map<String, StrategyExecutor> map = new HashMap<>();
        for (StrategyExecutor e : executors) map.put(e.strategyType(), e);
        this.executorsByType = map;
    }

    public void runOnce(AiTrader trader, MarketContext ctx) {
        StrategyExecutor exec = executorsByType.get(trader.getStrategyType());
        if (exec == null) {
            log.warn("[strategy] trader {} unknown strategy {}", trader.getId(), trader.getStrategyType());
            return;
        }
        List<Signal> signals;
        try {
            signals = exec.decide(trader, ctx);
        } catch (Exception e) {
            log.error("[strategy] trader {} decide failed: {}", trader.getId(), e.getMessage(), e);
            return;
        }
        int placed = 0, skipped = 0;
        for (Signal s : signals) {
            try {
                if (placeSignal(trader, ctx, s)) placed++;
                else skipped++;
            } catch (Exception e) {
                log.error("[strategy] trader {} place {} {} failed: {}",
                        trader.getId(), s.side(), s.stockCode(), e.getMessage());
                skipped++;
            }
        }
        if (!signals.isEmpty()) {
            log.info("[strategy] trader {} placed {} skipped {}", trader.getId(), placed, skipped);
        }
    }

    private boolean placeSignal(AiTrader trader, MarketContext ctx, Signal s) {
        BigDecimal price = ctx.priceOf(s.stockCode());
        if (price == null || price.signum() <= 0) {
            log.debug("[strategy] trader {} {} {} no price, skip", trader.getId(), s.side(), s.stockCode());
            return false;
        }

        if (orderService.hasPending(trader.getId(), s.stockCode())) {
            log.debug("[strategy] trader {} {} {} pending exists, skip",
                    trader.getId(), s.side(), s.stockCode());
            return false;
        }

        int amount;
        if ("BUY".equals(s.side())) {
            BigDecimal budget = trader.getBalance() == null ? BigDecimal.ZERO : trader.getBalance();
            BigDecimal target = budget.multiply(BUY_FRACTION);
            BigDecimal sharesRaw = target.divide(price, 6, RoundingMode.DOWN);
            int hundreds = sharesRaw.divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).intValue();
            if (hundreds <= 0) {
                log.debug("[strategy] trader {} BUY {} budget {} price {} too small",
                        trader.getId(), s.stockCode(), target, price);
                return false;
            }
            amount = hundreds * 100;
        } else if ("SELL".equals(s.side())) {
            if (orderService.boughtToday(trader.getId(), s.stockCode())) {
                log.debug("[strategy] trader {} SELL {} T+1 blocked", trader.getId(), s.stockCode());
                return false;
            }
            Position pos = positionMapper.selectOne(new QueryWrapper<Position>()
                    .eq("trader_id", trader.getId()).eq("stock_code", s.stockCode()));
            if (pos == null) return false;
            int sellable = pos.getAmount() - (pos.getFrozenAmount() == null ? 0 : pos.getFrozenAmount());
            if (sellable < 100) return false;
            amount = (sellable / 100) * 100;
        } else {
            return false;
        }

        PlaceOrderReq req = new PlaceOrderReq();
        req.setTraderId(trader.getId());
        req.setStockCode(s.stockCode());
        req.setSide(s.side());
        req.setPrice(price);
        req.setAmount(amount);
        orderService.place(req, trader.getUserId());
        log.info("[strategy] trader {} {} {} x {} @{} ({})",
                trader.getId(), s.side(), s.stockCode(), amount, price, s.reason());
        return true;
    }
}
