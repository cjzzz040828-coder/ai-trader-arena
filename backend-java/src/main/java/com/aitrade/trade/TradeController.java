package com.aitrade.trade;

import com.aitrade.auth.CurrentUser;
import com.aitrade.common.ApiException;
import com.aitrade.entity.AiTrader;
import com.aitrade.entity.TradeOrder;
import com.aitrade.gateway.PythonGatewayClient;
import com.aitrade.mapper.TradeOrderMapper;
import com.aitrade.trade.dto.CreateTraderReq;
import com.aitrade.trade.dto.OrderVO;
import com.aitrade.trade.dto.PlaceOrderReq;
import com.aitrade.trade.dto.PositionVO;
import com.aitrade.trade.dto.TestLlmResult;
import com.aitrade.trade.dto.TraderVO;
import com.aitrade.trade.dto.UpdateTraderReq;
import com.aitrade.trade.strategy.LlmStrategyExecutor;
import com.aitrade.trade.strategy.MarketContext;
import com.aitrade.trade.strategy.StrategyOrchestrator;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class TradeController {

    private final TraderService traderService;
    private final OrderService orderService;
    private final LlmStrategyExecutor llmStrategyExecutor;
    private final StrategyOrchestrator strategyOrchestrator;
    private final PythonGatewayClient pythonGatewayClient;
    private final TradeOrderMapper tradeOrderMapper;

    @GetMapping("/api/traders")
    public List<TraderVO> myTraders(@CurrentUser Long userId) {
        return traderService.listMy(userId);
    }

    @PostMapping("/api/traders")
    public ResponseEntity<TraderVO> createTrader(@Valid @RequestBody CreateTraderReq req,
                                                 @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(traderService.create(req, userId));
    }

    @PutMapping("/api/traders/{id}")
    public TraderVO updateTrader(@PathVariable Long id,
                                 @Valid @RequestBody UpdateTraderReq req,
                                 @CurrentUser Long userId) {
        return traderService.update(id, req, userId);
    }

    @DeleteMapping("/api/traders/{id}")
    public Map<String, Object> deleteTrader(@PathVariable Long id, @CurrentUser Long userId) {
        // 先确认归属，再撤掉所有 PENDING 单（释放冻结资金），最后软删
        traderService.getOwned(id, userId);
        int cancelled = orderService.cancelAllPending(id, userId);
        traderService.softDelete(id, userId);
        return Map.of("deleted", true, "cancelledOrders", cancelled);
    }

    @PostMapping("/api/traders/{id}/reset")
    public TraderVO resetTrader(@PathVariable Long id, @CurrentUser Long userId) {
        traderService.getOwned(id, userId);
        orderService.cancelAllPending(id, userId);
        return traderService.reset(id, userId);
    }

    @PostMapping("/api/traders/{id}/test-llm")
    public TestLlmResult testLlm(@PathVariable Long id, @CurrentUser Long userId) {
        AiTrader trader = traderService.getOwned(id, userId);
        if (!"LLM".equals(trader.getStrategyType())) {
            throw ApiException.badRequest("只有 LLM 策略的 trader 才能测试连通性");
        }
        return llmStrategyExecutor.testConnection(trader);
    }

    /**
     * 手动触发一轮策略决策。绕过"交易时段"检查，方便周末/盘后验证。
     * 会真下 PENDING 单（如 LLM 决定下单），周一 9:30 开盘后由 MatchEngine 撮合。
     */
    @PostMapping("/api/traders/{id}/decide-now")
    public Map<String, Object> decideNow(@PathVariable Long id, @CurrentUser Long userId) {
        AiTrader trader = traderService.getOwned(id, userId);
        String type = trader.getStrategyType();
        if (!"MA".equals(type) && !"LLM".equals(type)) {
            throw ApiException.badRequest("仅 MA / LLM 策略 trader 可以手动触发决策");
        }
        if (Integer.valueOf(0).equals(trader.getEnabled())) {
            throw ApiException.badRequest("trader 已停用，请先在管理页启用调度");
        }

        Long beforePending = tradeOrderMapper.selectCount(new QueryWrapper<TradeOrder>()
                .eq("trader_id", id).eq("status", "PENDING"));

        MarketContext ctx;
        long t0 = System.currentTimeMillis();
        try {
            ctx = new MarketContext(pythonGatewayClient, true);
        } catch (Exception e) {
            throw new ApiException(500, "构造市场视图失败: " + e.getMessage());
        }
        if (ctx.watchlist().isEmpty()) {
            throw new ApiException(500, "Python 网关 watchlist 为空，无法决策");
        }
        try {
            strategyOrchestrator.runOnce(trader, ctx);
        } catch (Exception e) {
            log.error("[strategy] decide-now trader {} failed: {}", id, e.getMessage(), e);
            throw new ApiException(500, "决策失败: " + e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - t0;

        Long afterPending = tradeOrderMapper.selectCount(new QueryWrapper<TradeOrder>()
                .eq("trader_id", id).eq("status", "PENDING"));
        long newOrders = (afterPending == null ? 0 : afterPending) - (beforePending == null ? 0 : beforePending);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("strategy", type);
        r.put("actualMarketOpen", ctx.isActualMarketOpen());
        r.put("watchlistSize", ctx.watchlist().size());
        r.put("elapsedMs", elapsed);
        r.put("newOrders", newOrders);
        r.put("totalPending", afterPending);
        r.put("message", newOrders > 0
                ? "已下 " + newOrders + " 个 PENDING 单，" + (ctx.isActualMarketOpen() ? "等待 10s tick 撮合" : "等待下个交易时段撮合")
                : "本轮无新增订单");
        return r;
    }

    @GetMapping("/api/traders/{id}/positions")
    public List<PositionVO> positions(@PathVariable Long id, @CurrentUser Long userId) {
        traderService.getOwned(id, userId);
        return traderService.listPositions(id);
    }

    @GetMapping("/api/traders/{id}/orders")
    public List<OrderVO> orders(@PathVariable Long id,
                                @RequestParam(defaultValue = "50") int limit,
                                @CurrentUser Long userId) {
        traderService.getOwned(id, userId);
        return orderService.listOrders(id, limit);
    }

    @PostMapping("/api/orders")
    public ResponseEntity<OrderVO> place(@Valid @RequestBody PlaceOrderReq req,
                                         @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.place(req, userId));
    }

    @PostMapping("/api/orders/{id}/cancel")
    public OrderVO cancel(@PathVariable Long id, @CurrentUser Long userId) {
        return orderService.cancel(id, userId);
    }
}
