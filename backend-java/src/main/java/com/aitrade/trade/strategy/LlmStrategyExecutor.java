package com.aitrade.trade.strategy;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.entity.TradeOrder;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.mapper.TradeOrderMapper;
import com.aitrade.trade.dto.TestLlmResult;
import com.aitrade.trade.strategy.llm.LlmActivityEvent;
import com.aitrade.trade.strategy.llm.LlmActivityPublisher;
import com.aitrade.trade.strategy.llm.LlmCancelRegistry;
import com.aitrade.trade.strategy.llm.LlmInFlightRegistry;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Executor 走 OpenAI 兼容的 function calling 模式。
 *
 * 流程：
 *   1. 系统提示 + 初始用户消息（投资策略 + 账户状态 + 持仓 + watchlist 概览 + 最近成交）
 *   2. 暴露 4 个工具：get_stock_analysis / get_minute_chart / get_recent_trades / place_order
 *   3. 多轮循环，最多 MAX_ROUNDS=10 次 HTTP 调用
 *   4. LLM 通过 place_order 直接下单（内部走 OrderService，所有防御不变）
 *   5. LLM 决定无操作时返回 stop，循环结束
 *
 * 返回空 List<Signal> 是约定：表示"executor 自己处理了下单"，Orchestrator 不再额外下单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmStrategyExecutor implements StrategyExecutor {

    private static final int MAX_ROUNDS = 10;
    private static final int WATCHLIST_OVERVIEW = 60;
    private static final int RECENT_TRADES_IN_PROMPT = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RestClient HTTP = RestClient.builder()
            .requestFactory(timeoutFactory())
            .defaultHeader("User-Agent", "aiTrade-LLM/1.0")
            .build();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final PositionMapper positionMapper;
    private final TradeOrderMapper tradeOrderMapper;
    private final LlmTools tools;
    private final LlmActivityPublisher activityPublisher;
    private final LlmCancelRegistry cancelRegistry;
    private final LlmInFlightRegistry inFlightRegistry;

    @Override
    public String strategyType() { return "LLM"; }

    @Override
    public List<Signal> decide(AiTrader trader, MarketContext ctx) {
        if (isBlank(trader.getLlmBaseUrl()) || isBlank(trader.getLlmApiKey()) || isBlank(trader.getLlmModel())) {
            log.warn("[strategy] LLM trader {} missing base_url/api_key/model, skip", trader.getId());
            return List.of();
        }

        long decisionId = activityPublisher.startDecision(trader.getId());
        if (!inFlightRegistry.tryAcquire(trader.getId(), decisionId)) {
            pub(decisionId, trader, "failed", null, null, null, null, null, "上一轮决策尚未结束，本次跳过");
            activityPublisher.finishDecision(trader.getId(), decisionId);
            return List.of();
        }

        try {
            String startMsg = String.format("model=%s, watchlist=%d, prompt=%d字",
                    trader.getLlmModel(),
                    ctx.watchlist().size(),
                    trader.getLlmPrompt() == null ? 0 : trader.getLlmPrompt().length());
            pub(decisionId, trader, "started", null, null, null, null, null, startMsg);

            if (ctx.watchlist().isEmpty()) {
                pub(decisionId, trader, "failed", null, null, null, null, null, "watchlist 为空");
                return List.of();
            }

            String url = stripTrailingSlash(trader.getLlmBaseUrl()) + "/v1/chat/completions";
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(msg("system", buildSystemPrompt()));
            messages.add(msg("user", buildInitialUserMessage(trader, ctx)));
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
                    return List.of();
                }

                messages.add(message);

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
            inFlightRegistry.release(trader.getId());
            activityPublisher.finishDecision(trader.getId(), decisionId);
        }
    }

    private void pub(long decisionId, AiTrader trader, String phase, Integer round,
                     String toolName, String toolCallId, String argsJson, String resultJson, String message) {
        int seq = activityPublisher.nextSeq(decisionId);
        LlmActivityEvent e = new LlmActivityEvent(
                trader.getId(), trader.getUserId(), decisionId, seq, phase, round,
                toolName, toolCallId, argsJson, resultJson, message,
                LocalDateTime.now().toString());
        activityPublisher.publish(e);
    }

    private Map<String, Object> dispatchTool(String name, AiTrader trader, MarketContext ctx, Map<String, Object> args) {
        if (name == null) return Map.of("ok", false, "error", "tool name missing");
        return switch (name) {
            case "get_stock_analysis" -> tools.getStockAnalysis(trader, ctx, args);
            case "get_minute_chart" -> tools.getMinuteChart(trader, ctx, args);
            case "get_recent_trades" -> tools.getRecentTrades(trader, ctx, args);
            case "place_order" -> tools.placeOrder(trader, ctx, args);
            default -> Map.of("ok", false, "error", "unknown tool: " + name);
        };
    }

    // ---------------- prompt 构造 ----------------

    private String buildSystemPrompt() {
        return """
                你是一个 A 股量化交易决策助手。你将收到用户的投资策略偏好、账户状态、当前持仓、watchlist 概览与最近成交。

                工作流程：
                1. 阅读初始信息，按投资策略筛出值得关注的 3~6 只股票。
                2. 调用 get_stock_analysis(code) / get_minute_chart(code) 查具体数据，必要时 get_recent_trades(limit) 查历史。
                3. 决策后通过 place_order(code, side, amount, price) 下单；可以连续下多笔。
                4. 完成后直接给一段简短总结（不再调任何工具），决策结束。
                5. 如果当前不需要任何操作（例如观望、持仓已合理、非合适买卖点），直接回复一句话说明并结束。

                约束：
                - amount 必须是 100 的整数倍（A 股最小买卖单位）；price 不传会用现价。
                - 同一股票若已有 PENDING 单不能再下单；T+1：当日买入的股票当日不能卖。
                - 同名工具调用不要重复（已查过的 code 别再查）。
                - 总工具调用不要超过 10 轮，请高效决策。
                - 中文交流；reason 字段或最终总结说明你做出该决策的依据。
                """;
    }

    private String buildInitialUserMessage(AiTrader trader, MarketContext ctx) {
        List<Position> positions = positionMapper.selectList(new QueryWrapper<Position>()
                .eq("trader_id", trader.getId()).gt("amount", 0));

        StringBuilder sb = new StringBuilder();
        sb.append("# 投资策略\n");
        sb.append(isBlank(trader.getLlmPrompt())
                ? "（未填写，请按通用稳健策略：均线趋势 + 适度仓位 + 回避高估值。）"
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

        sb.append("\n# Watchlist 概览（共 ").append(ctx.watchlist().size())
                .append(" 只，显示前 ").append(WATCHLIST_OVERVIEW).append("）\n");
        int n = 0;
        for (String code : ctx.watchlist()) {
            if (n++ >= WATCHLIST_OVERVIEW) break;
            Map<String, Object> row = ctx.snapshots().get(code);
            if (row == null) continue;
            sb.append("- ").append(code)
                    .append(" ").append(row.getOrDefault("name", ""))
                    .append(" 现价 ").append(row.getOrDefault("price", "?"))
                    .append(" 涨跌 ").append(row.getOrDefault("change_pct", "?")).append("%\n");
        }

        sb.append("\n请按你的策略思考并通过工具完成本轮决策。");
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
                fnTool("place_order",
                        "下买入或卖出订单。amount 必须是 100 的整数倍。price 不传则用现价（推荐）。下单成功会返回 order_id，下个 10s tick 撮合。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "code", Map.of("type", "string", "description", "6 位股票代码，必须在 watchlist 内"),
                                        "side", Map.of("type", "string", "enum", List.of("BUY", "SELL")),
                                        "amount", Map.of("type", "integer", "minimum", 100, "description", "股数，100 整数倍"),
                                        "price", Map.of("type", "number", "description", "限价（可选，默认现价）")
                                ),
                                "required", List.of("code", "side", "amount")
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
            Map<String, Object> resp = HTTP.post().uri(url)
                    .header("Authorization", "Bearer " + trader.getLlmApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(MAP_TYPE);
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

    private static org.springframework.http.client.SimpleClientHttpRequestFactory timeoutFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(60).toMillis());  // 多轮工具调用，单次 read 给宽点
        return f;
    }
}
