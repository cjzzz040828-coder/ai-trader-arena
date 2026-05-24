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
import com.aitrade.trade.strategy.script.ScriptApi;
import com.aitrade.trade.strategy.script.ScriptEngineFactory;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
    private final ScriptEngineFactory scriptEngineFactory;

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
        if (!"MA".equals(type) && !"LLM".equals(type) && !"INDICATOR".equals(type) && !"SCRIPT".equals(type)) {
            throw ApiException.badRequest("仅 MA / LLM / INDICATOR / SCRIPT 策略 trader 可以手动触发决策");
        }
        if (Integer.valueOf(0).equals(trader.getEnabled())) {
            throw ApiException.badRequest("trader 已停用，请先在管理页启用调度");
        }

        // 用 max(order_id) 而不是 PENDING 数量做 diff——因为 MatchEngine 可能在 runOnce 期间已经撮合掉
        // 新下的 PENDING 单，按 PENDING 数量算会偏少甚至负数。按 id 边界能精确拿到本轮新建的所有订单。
        Long beforeMaxOrderId = tradeOrderMapper.selectObjs(new QueryWrapper<TradeOrder>()
                .eq("trader_id", id).select("COALESCE(MAX(id), 0)")).stream()
                .findFirst().map(o -> ((Number) o).longValue()).orElse(0L);

        MarketContext ctx;
        long t0 = System.currentTimeMillis();
        try {
            ctx = new MarketContext(pythonGatewayClient, true, trader.getPoolName());
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

        // 本轮新建的所有订单（按 id 升序），包括已被 MatchEngine 立即撮合的
        List<TradeOrder> newOrders = tradeOrderMapper.selectList(new QueryWrapper<TradeOrder>()
                .eq("trader_id", id).gt("id", beforeMaxOrderId).orderByAsc("id"));
        Long pendingNow = tradeOrderMapper.selectCount(new QueryWrapper<TradeOrder>()
                .eq("trader_id", id).eq("status", "PENDING"));

        // 给前端用的精简结构，加上股票名字（从 ctx 的 snapshot 里拿）
        List<Map<String, Object>> newOrderDetails = new ArrayList<>(newOrders.size());
        for (TradeOrder o : newOrders) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.getId());
            row.put("code", o.getStockCode());
            row.put("name", ctx.nameOf(o.getStockCode()));
            row.put("side", o.getSide());
            row.put("amount", o.getAmount());
            row.put("price", o.getPrice());
            row.put("status", o.getStatus());
            newOrderDetails.add(row);
        }

        int newCount = newOrders.size();
        long filledNow = newOrders.stream().filter(o -> "FILLED".equals(o.getStatus())).count();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("strategy", type);
        r.put("actualMarketOpen", ctx.isActualMarketOpen());
        r.put("watchlistSize", ctx.watchlist().size());
        r.put("elapsedMs", elapsed);
        r.put("newOrders", newCount);
        r.put("filledNow", filledNow);
        r.put("totalPending", pendingNow);
        r.put("newOrderDetails", newOrderDetails);
        String msg;
        if (newCount == 0) {
            msg = "本轮无新增订单（策略未触发买卖信号或已被风控过滤）";
        } else if (ctx.isActualMarketOpen()) {
            msg = "本轮共下 " + newCount + " 单"
                    + (filledNow > 0 ? "，其中 " + filledNow + " 单已即时成交" : "，等待 10s tick 撮合");
        } else {
            msg = "本轮共下 " + newCount + " 个 PENDING 单，等待下个交易时段开盘撮合";
        }
        r.put("message", msg);
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

    /**
     * 干跑 SCRIPT 策略的脚本：编译 + 用一组 mock 数据调一次 decide()，把结果返回前端。
     * 用户在编辑器里点"测试编译"按钮时调用，保存前先验证。
     */
    @PostMapping("/api/traders/test-script")
    public Map<String, Object> testScript(@RequestBody Map<String, String> body, @CurrentUser Long userId) {
        String code = body == null ? null : body.get("scriptCode");
        Map<String, Object> r = new LinkedHashMap<>();
        if (code == null || code.isBlank()) {
            r.put("ok", false);
            r.put("message", "脚本为空");
            return r;
        }
        if (!code.contains("decide")) {
            r.put("ok", false);
            r.put("message", "脚本必须定义 function decide()");
            return r;
        }
        javax.script.CompiledScript cs;
        try {
            cs = scriptEngineFactory.compile(code);
        } catch (Exception e) {
            r.put("ok", false);
            r.put("message", "编译失败: " + e.getMessage());
            return r;
        }
        // mock 一组 OHLCV：60 根递增的 K 线，方便指标函数有值
        int n = 60;
        double[] close = new double[n], open = new double[n], high = new double[n], low = new double[n], vol = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = 10 + Math.sin(i / 5.0) * 2 + i * 0.05;
            open[i] = close[i] - 0.1;
            high[i] = close[i] + 0.2;
            low[i] = close[i] - 0.2;
            vol[i] = 100000 + i * 100;
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("api", new ScriptApi("test-user-" + userId));
        inputs.put("open", open);
        inputs.put("close", close);
        inputs.put("high", high);
        inputs.put("low", low);
        inputs.put("vol", vol);
        inputs.put("price", close[n - 1]);
        inputs.put("holding", 0);
        inputs.put("costPrice", 0.0);
        inputs.put("code", "000001");
        inputs.put("name", "测试样本");
        try {
            Object out = scriptEngineFactory.runDecide(cs, inputs);
            r.put("ok", true);
            r.put("sample", out == null ? "HOLD" : String.valueOf(out));
            r.put("message", "脚本运行成功，示例返回: " + (out == null ? "HOLD" : out));
        } catch (Exception e) {
            r.put("ok", false);
            r.put("message", "运行失败: " + e.getMessage());
        }
        return r;
    }
}
