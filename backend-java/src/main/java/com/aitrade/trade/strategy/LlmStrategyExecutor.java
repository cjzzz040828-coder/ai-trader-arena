package com.aitrade.trade.strategy;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.entity.TradeOrder;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.mapper.TradeOrderMapper;
import com.aitrade.trade.dto.TestLlmResult;
import com.aitrade.trade.strategy.llm.DecisionMemoryService;
import com.aitrade.trade.strategy.llm.LlmActivityEvent;
import com.aitrade.trade.strategy.llm.LlmActivityPublisher;
import com.aitrade.trade.strategy.llm.LlmCancelRegistry;
import com.aitrade.trade.strategy.llm.LlmInFlightRegistry;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LLM Executor 走 OpenAI 兼容的 function calling 模式。
 *
 * 流程：
 *   1. 系统提示 + 初始用户消息（投资策略 + 账户状态 + 持仓 + watchlist 概览 + 最近成交）
 *   2. 暴露 6 个工具：get_stock_analysis / get_minute_chart / get_recent_trades
 *      / place_order / get_pending_orders / cancel_order
 *   3. 多轮循环，最多 MAX_ROUNDS=10 次 HTTP 调用
 *   4. LLM 通过 place_order 直接下单（内部走 OrderService，所有防御不变）；
 *      也可用 cancel_order 撤掉自己的 PENDING 单（释放冻结资金后重新决策）
 *   5. LLM 决定无操作时返回 stop，循环结束
 *
 * 返回空 List&lt;Signal&gt; 是约定：表示"executor 自己处理了下单"，Orchestrator 不再额外下单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmStrategyExecutor implements StrategyExecutor {

    private static final int MAX_ROUNDS = 10;
    private static final int WATCHLIST_OVERVIEW = 500;
    private static final int RECENT_TRADES_IN_PROMPT = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RestClient HTTP = RestClient.builder()
            .requestFactory(timeoutFactory())
            .defaultHeader("User-Agent", "aiTrade-LLM/1.0")
            .build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final PositionMapper positionMapper;
    private final TradeOrderMapper tradeOrderMapper;
    private final LlmTools tools;
    private final LlmActivityPublisher activityPublisher;
    private final LlmCancelRegistry cancelRegistry;
    private final LlmInFlightRegistry inFlightRegistry;
    private final DecisionMemoryService decisionMemoryService;

    @Override
    public String strategyType() { return "LLM"; }

    @Override
    public List<Signal> decide(AiTrader trader, MarketContext ctx) {
        if (isBlank(trader.getLlmBaseUrl()) || isBlank(trader.getLlmApiKey()) || isBlank(trader.getLlmModel())) {
            log.warn("[strategy] LLM trader {} missing base_url/api_key/model, skip", trader.getId());
            return List.of();
        }

        // 上一轮还在跑（多见于 LLM read 接近 readTimeout）不是"失败"，静默跳过，
        // 不要在 activity 历史里制造一条假的 failed 决策。
        if (inFlightRegistry.current(trader.getId()) != null) {
            log.debug("[strategy] LLM trader {} previous decision still in-flight, skip silently", trader.getId());
            return List.of();
        }

        long decisionId = activityPublisher.startDecision(trader.getId());
        if (!inFlightRegistry.tryAcquire(trader.getId(), decisionId)) {
            activityPublisher.finishDecision(trader.getId(), decisionId);
            log.debug("[strategy] LLM trader {} lost in-flight race, skip silently", trader.getId());
            return List.of();
        }

        try {
            // 注册当前线程到 cancelRegistry：用户点停止时会 interrupt 此线程，
            // 让阻塞在 HTTP send() 上的调用立即抛 InterruptedException 退出，
            // 不必等 readTimeout (180s) 自然返回。
            cancelRegistry.registerThread(trader.getId(), Thread.currentThread());

            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildInitialUserMessage(trader, ctx);
            String promptJson;
            try {
                Map<String, String> promptMap = new LinkedHashMap<>();
                promptMap.put("system", systemPrompt);
                promptMap.put("user", userPrompt);
                promptJson = MAPPER.writeValueAsString(promptMap);
            } catch (Exception e) {
                promptJson = null;
            }
            String startMsg = String.format("model=%s, watchlist=%d, prompt=%d字",
                    trader.getLlmModel(),
                    ctx.watchlist().size(),
                    trader.getLlmPrompt() == null ? 0 : trader.getLlmPrompt().length());
            pub(decisionId, trader, "started", null, null, null, null, null, startMsg, promptJson);

            if (ctx.watchlist().isEmpty()) {
                pub(decisionId, trader, "failed", null, null, null, null, null, "watchlist 为空");
                return List.of();
            }

            String url = stripTrailingSlash(trader.getLlmBaseUrl()) + "/v1/chat/completions";
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(msg("system", systemPrompt));
            messages.add(msg("user", userPrompt));
            List<Map<String, Object>> toolsDef = buildToolsDefinition();

            int totalToolCalls = 0;
            int placedOrders = 0;
            long t0 = System.currentTimeMillis();
            for (int round = 0; round < MAX_ROUNDS; round++) {
                if (cancelRegistry.isCancelled(trader.getId(), decisionId)) {
                    pub(decisionId, trader, "cancelled", round, null, null, null, null, "用户在第 " + round + " 轮前取消");
                    return List.of();
                }

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", trader.getLlmModel());
                body.put("messages", messages);
                body.put("tools", toolsDef);
                body.put("temperature", 0.7);
                body.put("max_tokens", 1500);

                Map<String, Object> resp;
                try {
                    byte[] raw = HTTP.post().uri(url)
                            .header("Authorization", "Bearer " + trader.getLlmApiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(byte[].class);
                    // 某些 LLM 网关响应 Content-Type 是 octet-stream，让 Jackson 自己解析字节流避开 converter 校验
                    resp = raw == null ? null : MAPPER.readValue(new String(raw, StandardCharsets.UTF_8), MAP_TYPE_REF);
                } catch (Exception e) {
                    // cancel API 通过 thread.interrupt() 打断 HTTP send()，
                    // 抛出的 ResourceAccessException 在这里捕获 → 走 cancelled 分支。
                    if (cancelRegistry.isCancelled(trader.getId(), decisionId)) {
                        log.info("[strategy] LLM trader {} round {} HTTP cancelled by user",
                                trader.getId(), round);
                        pub(decisionId, trader, "cancelled", round, null, null, null, null,
                                "用户在 LLM 等待中取消（HTTP 已中断）");
                        return List.of();
                    }
                    log.error("[strategy] LLM trader {} round {} HTTP failed: {}",
                            trader.getId(), round, e.getMessage());
                    pub(decisionId, trader, "failed", round, null, null, null, null,
                            "HTTP 调用失败: " + truncate(e.getMessage(), 500));
                    return List.of();
                }
                if (resp == null) {
                    log.warn("[strategy] LLM trader {} round {} empty response", trader.getId(), round);
                    pub(decisionId, trader, "failed", round, null, null, null, null, "LLM 返回空响应");
                    return List.of();
                }

                Object choicesObj = resp.get("choices");
                if (!(choicesObj instanceof List<?>) || ((List<?>) choicesObj).isEmpty()) {
                    log.warn("[strategy] LLM trader {} round {} no choices", trader.getId(), round);
                    pub(decisionId, trader, "failed", round, null, null, null, null, "LLM 响应里没有 choices");
                    return List.of();
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> choice = (Map<String, Object>) ((List<?>) choicesObj).get(0);
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                String finishReason = String.valueOf(choice.getOrDefault("finish_reason", ""));
                Object toolCallsObj = message == null ? null : message.get("tool_calls");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> toolCalls = (toolCallsObj instanceof List)
                        ? (List<Map<String, Object>>) toolCallsObj : null;

                if (toolCalls == null || toolCalls.isEmpty()) {
                    String content = message == null ? "" : String.valueOf(message.getOrDefault("content", ""));
                    log.info("[strategy] LLM trader {} ({}) done in {} rounds, {}ms, calls={} placed={}, final: {}",
                            trader.getId(), trader.getName(), round + 1, System.currentTimeMillis() - t0,
                            totalToolCalls, placedOrders, truncate(content, 200));
                    pub(decisionId, trader, "final", round, null, null, null, null,
                            truncate(content, 4000));
                    if (placedOrders > 0 && content != null && !content.isBlank()) {
                        decisionMemoryService.updateReason(decisionId, truncate(content, 1000));
                    }
                    return List.of();
                }

                // 只保留协议规定字段后再回传：Kimi K2 Thinking / GLM-4.5V / Qwen3-VL 等会附带
                // reasoning_content 等扩展字段，原样回传会让 SiliconFlow 校验失败抛 20015。
                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", message.get("content"));
                assistantMsg.put("tool_calls", toolCalls);
                messages.add(assistantMsg);

                for (Map<String, Object> tc : toolCalls) {
                    totalToolCalls++;
                    String tcId = strVal(tc.get("id"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                    String name = fn == null ? null : strVal(fn.get("name"));
                    String argsJson = fn == null ? "{}" : strVal(fn.get("arguments"));
                    if (argsJson == null) argsJson = "{}";

                    pub(decisionId, trader, "tool_call", round, name, tcId, argsJson, null, null);

                    if (cancelRegistry.isCancelled(trader.getId(), decisionId)) {
                        pub(decisionId, trader, "cancelled", round, name, tcId, null, null,
                                "用户在工具调用前取消，不再执行 " + name);
                        return List.of();
                    }

                    Map<String, Object> argsMap;
                    try {
                        argsMap = MAPPER.readValue(argsJson, new TypeReference<>() {});
                    } catch (Exception e) {
                        argsMap = Map.of();
                    }

                    Map<String, Object> result;
                    try {
                        result = dispatchTool(name, trader, ctx, argsMap);
                    } catch (Exception e) {
                        log.error("[strategy] LLM trader {} tool {} exec failed: {}",
                                trader.getId(), name, e.getMessage(), e);
                        result = Map.of("ok", false, "error", "tool exec failed: " + e.getMessage());
                    }
                    if ("place_order".equals(name) && Boolean.TRUE.equals(result.get("ok"))) {
                        placedOrders++;
                        try {
                            BigDecimal snapPrice = extractDecisionPrice(argsMap, ctx);
                            Long orderId = result.get("order_id") instanceof Number
                                    ? ((Number) result.get("order_id")).longValue() : null;
                            if (snapPrice != null) {
                                decisionMemoryService.recordPlace(trader, decisionId, argsMap, orderId, snapPrice, null);
                            }
                        } catch (Exception ex) {
                            log.warn("[strategy] decision-memory record skipped: {}", ex.getMessage());
                        }
                    }

                    String resultJson;
                    try {
                        resultJson = MAPPER.writeValueAsString(result);
                    } catch (Exception e) {
                        resultJson = "{\"ok\":false,\"error\":\"result serialize failed\"}";
                    }
                    pub(decisionId, trader, "tool_result", round, name, tcId, null, resultJson, null);

                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", tcId);
                    toolMsg.put("content", resultJson);
                    messages.add(toolMsg);
                }

                if ("stop".equals(finishReason)) {
                    log.debug("[strategy] LLM trader {} got stop with tool_calls; continue one more round", trader.getId());
                }
            }

            log.warn("[strategy] LLM trader {} reached MAX_ROUNDS={}, abort. calls={} placed={}",
                    trader.getId(), MAX_ROUNDS, totalToolCalls, placedOrders);
            pub(decisionId, trader, "failed", MAX_ROUNDS - 1, null, null, null, null,
                    "达到最大轮次 " + MAX_ROUNDS + "，强制结束。tool_calls=" + totalToolCalls + " placed=" + placedOrders);
            return List.of();
        } finally {
            cancelRegistry.unregisterThread(trader.getId(), Thread.currentThread());
            // 清除 interrupt 标志，避免本线程后续被复用时携带脏中断状态。
            Thread.interrupted();
            inFlightRegistry.release(trader.getId());
            // 清当前 decisionId 的取消标志，避免 cancelledDecisionByTrader 缓慢累积；
            // clear 只在 traderId 上的标志匹配本 decisionId 时移除，不会误伤后续决策。
            cancelRegistry.clear(trader.getId(), decisionId);
            activityPublisher.finishDecision(trader.getId(), decisionId);
        }
    }

    private void pub(long decisionId, AiTrader trader, String phase, Integer round,
                     String toolName, String toolCallId, String argsJson, String resultJson, String message) {
        pub(decisionId, trader, phase, round, toolName, toolCallId, argsJson, resultJson, message, null);
    }

    private void pub(long decisionId, AiTrader trader, String phase, Integer round,
                     String toolName, String toolCallId, String argsJson, String resultJson, String message,
                     String promptJson) {
        int seq = activityPublisher.nextSeq(decisionId);
        LlmActivityEvent e = new LlmActivityEvent(
                trader.getId(), trader.getUserId(), decisionId, seq, phase, round,
                toolName, toolCallId, argsJson, resultJson, message,
                promptJson,
                LocalDateTime.now().toString());
        activityPublisher.publish(e);
    }

    private Map<String, Object> dispatchTool(String name, AiTrader trader, MarketContext ctx, Map<String, Object> args) {
        if (name == null) return Map.of("ok", false, "error", "tool name missing");
        return switch (name) {
            case "get_stock_analysis" -> tools.getStockAnalysis(trader, ctx, args);
            case "get_minute_chart" -> tools.getMinuteChart(trader, ctx, args);
            case "get_recent_trades" -> tools.getRecentTrades(trader, ctx, args);
            case "get_stock_news" -> tools.getStockNews(trader, ctx, args);
            case "place_order" -> tools.placeOrder(trader, ctx, args);
            case "get_pending_orders" -> tools.getPendingOrders(trader, ctx, args);
            case "cancel_order" -> tools.cancelOrder(trader, ctx, args);
            default -> Map.of("ok", false, "error", "unknown tool: " + name);
        };
    }

    /** 还原 place_order 决策瞬间用到的价格：优先 args.price，缺省走 ctx.priceOf。 */
    private BigDecimal extractDecisionPrice(Map<String, Object> args, MarketContext ctx) {
        Object priceArg = args == null ? null : args.get("price");
        if (priceArg instanceof Number) {
            return new BigDecimal(priceArg.toString());
        }
        if (priceArg instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignore) { }
        }
        String code = args == null ? null : (args.get("code") == null ? null : String.valueOf(args.get("code")));
        return code == null ? null : ctx.priceOf(code);
    }

    // ---------------- prompt 构造 ----------------

    private String buildSystemPrompt() {
        return """
                你是一个 A 股量化交易决策助手。你将收到用户的投资策略偏好、账户状态、当前持仓、watchlist 概览与最近成交。

                工作流程：
                1. **基于全盘技术快照初筛**：用户消息里的 "Watchlist 全盘技术快照" 已经把所有标的的现价、当日涨跌、MA5/10/20、5 日涨跌、量比一次性给齐了——**这就是你的初筛工作台，请认真扫一遍全盘，再按投资策略圈出 3~5 只重点关注标的**。不要光看前几行就下结论。
                2. 对圈出的 3~5 只调用 get_stock_analysis(code) 看最近 10 根 K 线 OHLC + 20 日涨跌，必要时 get_minute_chart(code) 看今日分时；get_stock_news(code) 看个股最新新闻（业绩 / 评级 / 利好利空）；get_recent_trades(limit) 看自己历史成交。
                   - **新闻已预置**：用户消息里已经带了"大盘情绪（财联社电报）"和"持仓个股最新新闻"两个 section，这两块**不要重复用 get_stock_news 拉**——预算紧张，只对**未持仓但准备建仓的候选股**调 get_stock_news。
                   - **扫描宽度建议**：本轮深查 3~5 只**不同**标的即可，避免每次只押同样几只老熟脸——全盘快照已经覆盖全部 watchlist，你能看到的远比"几只熟脸"多。
                   - **预算硬约束**：累计工具调用达到 6 次后必须开始下单，不允许继续查询——剩下的额度要留给 place_order / cancel_order，否则会撞最大轮次被强制中断。
                3. 决策后通过 place_order(code, side, amount, price) 下单；可以连续下多笔。
                4. 如有未成交的 PENDING 单且原决策已不再合理（如行情反转、关键位破位、挂价过远难以成交），可调用 get_pending_orders 查看挂单列表，然后用 cancel_order(order_id) 撤销。撤单会立即释放冻结资金/持仓，撤完即可重新下单。
                5. 完成后直接给一段简短总结（不再调任何工具），决策结束。
                6. **观望需要前提**：仅当当前持仓已 ≥ 3 只且每只都健康（无明显风险信号、未到止盈/止损位、技术形态完好）时，才允许回复一句话说明观望理由并结束。否则不允许"看完什么都不做"——必须本轮至少做一笔实际操作（建仓 / 加仓 / 止盈 / 止损 / 调仓换股），按你的策略选最合理的一笔。

                约束：
                - amount 必须是 100 的整数倍（A 股最小买卖单位）。
                - **限价单规则（很重要）**：BUY 的 price 是"成交价上限"，挂价必须 ≥ 现价才会成交；SELL 的 price 是"成交价下限"，挂价必须 ≤ 现价才会成交。**反向挂（BUY 比现价低 / SELL 比现价高）= 等回踩，单子卡在 PENDING 冻结资金/持仓，往往一天都不成交，不是"抄底/逢高出"**。想立即按市价成交：**不传 price**（系统用最新快照）。想加滑点缓冲：BUY 略高于现价 / SELL 略低于现价——多冻结的钱成交后自动回流。挂价反向偏离 >1% 时 place_order 响应会带 warning，看到 warning 一般应撤单重挂或不传 price。
                - 同一股票若已有 PENDING 单不能再下单；T+1：当日买入的股票当日不能卖。
                - cancel_order 只能撤本 trader 的 PENDING 单（系统已强制校验，撤别人的会报错）。
                - 同名工具调用不要重复（已查过的 code 别再查）。
                - **总工具调用次数（含查询+下单）≤ 12 次**；HTTP 轮次最多 10 轮，超出会被系统强制中断。请把额度优先留给下单环节，高效决策。
                - 中文交流；reason 字段或最终总结说明你做出该决策的依据。
                """;
    }

    private String buildInitialUserMessage(AiTrader trader, MarketContext ctx) {
        List<Position> positions = positionMapper.selectList(new QueryWrapper<Position>()
                .eq("trader_id", trader.getId()).gt("amount", 0));

        StringBuilder sb = new StringBuilder();
        sb.append("# 投资策略\n");
        sb.append(isBlank(trader.getLlmPrompt())
                ? "（未填写专属策略，请按以下默认目标交易）\n"
                + "- 目标持仓：3~5 只活跃股，单只仓位 ≤ 30%；现金留存 10~20%。\n"
                + "- **建仓优先**：当前若空仓或持仓不足 3 只，本轮必须从 watchlist 中至少选 1 只买入建仓，不要光看不下手。\n"
                + "- 风格偏好：日内趋势 + 均线突破；单笔盈利 5~8% 考虑止盈，亏损 3~5% 考虑止损。\n"
                + "- 风控红线：不追涨停 / 跌停股、不重仓 ST 票、不在尾盘 14:50 后开新仓。"
                : trader.getLlmPrompt().trim());

        sb.append("\n\n# 账户状态\n");
        sb.append("初始资产: ").append(trader.getInitialBalance() == null ? "1000000" : trader.getInitialBalance().toPlainString()).append(" 元\n");
        sb.append("可用资金: ").append(trader.getBalance() == null ? "0" : trader.getBalance().toPlainString()).append(" 元\n");
        sb.append("冻结资金: ").append(trader.getFrozenBalance() == null ? "0" : trader.getFrozenBalance().toPlainString()).append(" 元\n");

        sb.append("\n# 当前持仓（").append(positions.size()).append(" 只）\n");
        if (positions.isEmpty()) {
            sb.append("（空仓）\n");
        } else {
            for (Position p : positions) {
                String name = ctx.nameOf(p.getStockCode());
                BigDecimal latest = ctx.priceOf(p.getStockCode());
                BigDecimal cost = p.getCostPrice();
                String pnlPct = "?";
                if (cost != null && cost.signum() > 0 && latest != null) {
                    pnlPct = latest.subtract(cost).multiply(BigDecimal.valueOf(100))
                            .divide(cost, 2, java.math.RoundingMode.HALF_UP).toPlainString();
                }
                sb.append("- ").append(p.getStockCode())
                        .append(" ").append(name == null ? "" : name)
                        .append(" 持仓 ").append(p.getAmount())
                        .append(" 成本 ").append(cost == null ? "?" : cost.toPlainString())
                        .append(" 现价 ").append(latest == null ? "?" : latest.toPlainString())
                        .append(" 浮动盈亏 ").append(pnlPct).append("%\n");
            }
        }

        // 最近 N 条 FILLED 成交（让模型有"自己的足迹"上下文）
        List<TradeOrder> recent = tradeOrderMapper.selectList(new QueryWrapper<TradeOrder>()
                .eq("trader_id", trader.getId()).eq("status", "FILLED")
                .orderByDesc("filled_at").last("LIMIT " + RECENT_TRADES_IN_PROMPT));
        sb.append("\n# 最近成交（").append(recent.size()).append(" 条）\n");
        if (recent.isEmpty()) {
            sb.append("（尚无成交记录）\n");
        } else {
            for (TradeOrder o : recent) {
                sb.append("- ").append(o.getFilledAt() == null ? "?" : o.getFilledAt().toString())
                        .append(" ").append(o.getSide())
                        .append(" ").append(o.getStockCode())
                        .append(" x ").append(o.getAmount())
                        .append(" @").append(o.getFilledPrice() == null ? "?" : o.getFilledPrice().toPlainString())
                        .append("\n");
            }
        }

        // watchlist 截断不能按固定顺序——否则 LLM 永远只看到前 N 只、决策反复收敛到同一片标的。
        // 策略：当前持仓必带（上下文不能丢）+ 剩余从 watchlist 随机抽样填满 WATCHLIST_OVERVIEW。
        List<String> fullList = ctx.watchlist();
        List<String> shown;
        if (fullList.size() <= WATCHLIST_OVERVIEW) {
            shown = new ArrayList<>(fullList);
        } else {
            LinkedHashSet<String> picked = new LinkedHashSet<>();
            for (Position p : positions) picked.add(p.getStockCode());
            List<String> pool = new ArrayList<>(fullList);
            pool.removeAll(picked);
            Collections.shuffle(pool, ThreadLocalRandom.current());
            for (String code : pool) {
                if (picked.size() >= WATCHLIST_OVERVIEW) break;
                picked.add(code);
            }
            shown = new ArrayList<>(picked);
        }

        sb.append("\n# Watchlist 全盘技术快照（共 ").append(fullList.size())
                .append(" 只，本轮展示 ").append(shown.size()).append(" 只，持仓必带）\n");
        sb.append("字段格式：代码 名称 现价(当日%) MA5/10/20 5日% 量比Q（量比=今日量/5日均量，>1.5 显著放量）。");
        sb.append("请基于这份全盘快照初筛，再用 get_stock_analysis / get_minute_chart 深查 3~5 只。\n");
        for (String code : shown) {
            Map<String, Object> snap = ctx.snapshots().get(code);
            if (snap == null) continue;
            Map<String, Object> brief = tools.getQuickSnapshot(code, ctx);
            sb.append("- ").append(code)
                    .append(" ").append(snap.getOrDefault("name", ""))
                    .append(" ").append(brief.get("price"))
                    .append("(").append(brief.get("change_pct")).append("%)")
                    .append(" MA").append(brief.get("ma5"))
                    .append("/").append(brief.get("ma10"))
                    .append("/").append(brief.get("ma20"))
                    .append(" 5d").append(brief.get("change_5d_pct")).append("%")
                    .append(" Q").append(brief.get("vol_ratio"))
                    .append("\n");
        }

        sb.append("\n请按你的策略思考并通过工具完成本轮决策。");

        String memorySummary = decisionMemoryService.formatRecentSummary(trader.getId(), 30);
        if (memorySummary != null && !memorySummary.isEmpty()) {
            sb.append(memorySummary);
        }

        // 新闻 / 情绪注入：失败安静返回空串，不影响决策主流程
        String sentiment = tools.formatMarketSentimentSection(5);
        if (!sentiment.isEmpty()) sb.append(sentiment);
        String posNews = tools.formatPositionNewsSection(ctx, positions);
        if (!posNews.isEmpty()) sb.append(posNews);

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildToolsDefinition() {
        return List.of(
                fnTool("get_stock_analysis",
                        "获取某只股票的技术分析数据：MA5/MA10/MA20、5/20 日累计涨跌幅、量比（今日量 vs 5 日均量）、最近 10 根日 K OHLC。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("code", Map.of("type", "string", "description", "6 位股票代码")),
                                "required", List.of("code")
                        )),
                fnTool("get_minute_chart",
                        "获取某只股票今日的分时线（每 15 分钟采样一个收盘价与成交量）。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("code", Map.of("type", "string", "description", "6 位股票代码")),
                                "required", List.of("code")
                        )),
                fnTool("get_recent_trades",
                        "查询此 trader 的最近已成交订单。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("limit", Map.of("type", "integer", "minimum", 1, "maximum", 50)),
                                "required", List.of()
                        )),
                fnTool("get_stock_news",
                        "拉取某只股票最近的新闻（东方财富数据源，含业绩公告/机构评级/利好利空消息）。返回 N 条 {time, title, content(截断), source}。用于判断个股突发事件、基本面变化、市场关注度。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "code", Map.of("type", "string", "description", "6 位股票代码"),
                                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 10, "description", "默认 5 条")
                                ),
                                "required", List.of("code")
                        )),
                fnTool("place_order",
                        "下买入或卖出订单。amount 必须是 100 的整数倍。**限价规则：BUY 挂价是上限（必须 ≥ 现价才成交），SELL 挂价是下限（必须 ≤ 现价才成交）；反向挂会卡 PENDING 不成交。想立即成交：不传 price 用现价（推荐）。** 下单成功返回 order_id，下个 10s tick 撮合；挂价反向偏离 >1% 时响应会带 warning 字段。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "code", Map.of("type", "string", "description", "6 位股票代码，必须在 watchlist 内"),
                                        "side", Map.of("type", "string", "enum", List.of("BUY", "SELL")),
                                        "amount", Map.of("type", "integer", "minimum", 100, "description", "股数，100 整数倍"),
                                        "price", Map.of("type", "number", "description", "限价（可选，**强烈建议不传**。不传 = 拉最新报价 + 1% 滑点，下个 tick 必成交。传了等于限价单：BUY 低于现价 / SELL 高于现价会卡 PENDING 直到行情回踩，多轮推理期间行情可能已经走开，限价单大概率成不了交。除非你明确想『挂单等回踩』，否则一律省略 price。)")
                                ),
                                "required", List.of("code", "side", "amount")
                        )),
                fnTool("get_pending_orders",
                        "查询本 trader 当前所有未成交（PENDING）的挂单，返回 order_id / code / side / amount / price / created_at。撤单前先用此工具拿到 order_id。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(),
                                "required", List.of()
                        )),
                fnTool("cancel_order",
                        "撤销本 trader 的一笔 PENDING 单，撤单成功会立即释放冻结资金/持仓。只能撤本 trader 的 PENDING 单（系统强制校验）。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "order_id", Map.of("type", "integer", "description", "待撤销的订单 ID（必须是本 trader 的 PENDING 单）")
                                ),
                                "required", List.of("order_id")
                        ))
        );
    }

    private Map<String, Object> fnTool(String name, String desc, Object parameters) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", desc);
        fn.put("parameters", parameters);
        return Map.of("type", "function", "function", fn);
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    // ---------------- 测试连接（保留旧逻辑，给前端 testLlm 按钮用） ----------------

    public TestLlmResult testConnection(AiTrader trader) {
        if (isBlank(trader.getLlmBaseUrl()) || isBlank(trader.getLlmApiKey()) || isBlank(trader.getLlmModel())) {
            return new TestLlmResult(false, "请先填写 base_url / api_key / model", null, "missing config");
        }
        String url = stripTrailingSlash(trader.getLlmBaseUrl()) + "/v1/chat/completions";
        Map<String, Object> body = Map.of(
                "model", trader.getLlmModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", "Respond with the single token 'OK'."),
                        Map.of("role", "user", "content", "ping")
                ),
                "temperature", 0,
                "max_tokens", 10
        );
        long t0 = System.currentTimeMillis();
        try {
            // 某些 LLM 网关响应 Content-Type 是 application/octet-stream，
            // RestClient 找不到 Map 的 converter 会抛 UnknownContentTypeException；
            // 用 byte[] + Jackson 手动解析绕开 converter 校验（与 decide() 一致）。
            byte[] raw = HTTP.post().uri(url)
                    .header("Authorization", "Bearer " + trader.getLlmApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            Map<String, Object> resp = raw == null ? null
                    : MAPPER.readValue(new String(raw, StandardCharsets.UTF_8), MAP_TYPE_REF);
            long elapsed = System.currentTimeMillis() - t0;
            String content = extractContent(resp);
            if (content == null || content.isBlank()) {
                return new TestLlmResult(false, "调用成功但模型未返回内容", null, "empty content");
            }
            return new TestLlmResult(true,
                    "测试成功（" + elapsed + "ms，模型: " + trader.getLlmModel() + "）",
                    truncate(content.trim(), 500), null);
        } catch (HttpClientErrorException e) {
            String msg;
            int code = e.getStatusCode().value();
            if (code == 401 || code == 403) msg = "API key 无效或权限不足";
            else if (code == 404) msg = "endpoint 路径不存在，请检查 base_url（注意不要带 /v1）";
            else if (code == 429) msg = "请求被限流（429）";
            else msg = "客户端错误: HTTP " + code;
            return new TestLlmResult(false, msg, null, truncate(e.getResponseBodyAsString(), 500));
        } catch (HttpServerErrorException e) {
            return new TestLlmResult(false,
                    "服务端错误: HTTP " + e.getStatusCode().value(),
                    null, truncate(e.getResponseBodyAsString(), 500));
        } catch (ResourceAccessException e) {
            return new TestLlmResult(false, "连接失败或超时: " + e.getMessage(), null, e.toString());
        } catch (Exception e) {
            return new TestLlmResult(false, "测试失败: " + e.getMessage(), null, e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> resp) {
        if (resp == null) return null;
        Object choices = resp.get("choices");
        if (!(choices instanceof List<?> arr) || arr.isEmpty()) return null;
        Object first = arr.get(0);
        if (!(first instanceof Map<?, ?> choice)) return null;
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> msg)) return null;
        Object content = msg.get("content");
        return content == null ? null : String.valueOf(content);
    }

    // ---------------- helpers ----------------

    private static String strVal(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    /**
     * 用 JDK11+ 自带的 java.net.http.HttpClient 作为 RestClient 底层。
     *
     * 历史上用 SimpleClientHttpRequestFactory（基于古老的 HttpURLConnection），在某些 HTTPS +
     * 大 body（带 tools 定义的 LLM 请求）场景下会把 response 的 Content-Type 错误地报成
     * application/octet-stream，导致 Spring RestClient 找不到 converter 提取响应。
     * JdkClientHttpRequestFactory 是 Spring 6 推荐项，行为更稳定，无需额外依赖。
     *
     * readTimeout 给到 180s：SiliconFlow 上 deepseek 带 tools 的复杂推理偶尔会到 60-120s
     * （首 token 慢 + tool_calls 多），60s 不够。MAX_ROUNDS=10 也是分多轮调用，每轮独立计时。
     */
    private static org.springframework.http.client.JdkClientHttpRequestFactory timeoutFactory() {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        var f = new org.springframework.http.client.JdkClientHttpRequestFactory(client);
        f.setReadTimeout(Duration.ofSeconds(180));
        return f;
    }
}
