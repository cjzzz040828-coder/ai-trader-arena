package com.aitrade.analyst;

import com.aitrade.gateway.PythonGatewayClient;
import com.aitrade.gateway.dto.SnapshotResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard AI 分析助手。
 *
 * 与交易 LLM trader 完全解耦：
 *   - 用全局 analyst.llm 配置（非某个 trader 的配置）
 *   - 只读：拉行情/K线/新闻组上下文，纯文本对话，**不暴露任何下单工具**
 *   - 流式：LLM stream=true，逐 chunk 转发给前端 SseEmitter
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalystService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final RestClient HTTP = RestClient.create();

    private final AnalystProperties props;
    private final PythonGatewayClient gateway;

    private static final String SYSTEM_PROMPT = """
            你是一个专业的 A 股盘面分析助手，服务于交易看盘场景。用户正在看某只股票，你会收到该股的实时行情、
            最近日 K（OHLC）、均线、量比和最新新闻。请基于这些数据做分析。

            要求：
            1. 用中文，简洁专业，分点表达。
            2. 结合给定数据分析：趋势（多/空/震荡）、关键支撑位与压力位、量价关系、近期新闻面影响。
            3. 可以给出明确的操作建议（买入/卖出/观望及理由、参考价位），但这是建议而非指令。
            4. **你没有下单能力，绝不声称已下单或能下单**；只提供分析与建议。
            5. 数据有限时如实说明不确定性，不要编造没有的数据（如不要虚构财务指标）。
            6. 提醒用户风险自负，分析仅供参考。
            """;

    /**
     * 一轮分析对话（流式）。组上下文 → 拼 messages → 调 LLM stream → 转发到 emitter。
     *
     * @param code    当前股票代码
     * @param history 历史对话 [{role, content}, ...]（前端维护，不含本轮 userMsg）
     * @param userMsg 本轮用户提问
     * @param emitter SSE 通道
     */
    public void chat(String code, List<Map<String, String>> history, String userMsg, SseEmitter emitter) {
        try {
            if (!props.configured()) {
                sendError(emitter, "分析助手未配置 LLM（需设置 ANALYST_LLM_BASE_URL / API_KEY / MODEL）");
                emitter.complete();
                return;
            }

            String context = buildContext(code);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(msg("system", SYSTEM_PROMPT));
            if (context != null && !context.isBlank()) {
                messages.add(msg("system", "【当前股票实时数据】\n" + context));
            }
            if (history != null) {
                for (Map<String, String> h : history) {
                    String role = h.get("role");
                    String content = h.get("content");
                    if (("user".equals(role) || "assistant".equals(role)) && content != null && !content.isBlank()) {
                        messages.add(msg(role, content));
                    }
                }
            }
            messages.add(msg("user", userMsg == null || userMsg.isBlank() ? "请分析这只股票当前的走势与机会。" : userMsg));

            streamLlm(messages, emitter);
        } catch (Exception e) {
            log.error("[analyst] chat failed: {}", e.getMessage(), e);
            try { sendError(emitter, "分析失败: " + e.getMessage()); } catch (Exception ignore) { }
            emitter.complete();
        }
    }

    /** 拉 gateway 数据组装当前股票上下文文本。任一数据缺失都降级，不阻断分析。 */
    private String buildContext(String code) {
        if (code == null || code.isBlank()) return "";
        StringBuilder sb = new StringBuilder();

        // 现价 / 名称 / 涨跌
        String name = code;
        try {
            SnapshotResponse snap = gateway.snapshot(code);
            if (snap != null && snap.getData() != null && !snap.getData().isEmpty()) {
                Map<String, Object> s = snap.getData().get(0);
                name = String.valueOf(s.getOrDefault("name", code));
                sb.append("代码: ").append(code).append("  名称: ").append(name).append("\n");
                sb.append("现价: ").append(s.get("price"))
                        .append("  涨跌幅: ").append(s.get("change_pct")).append("%\n");
            }
        } catch (Exception e) {
            log.warn("[analyst] snapshot {} failed: {}", code, e.getMessage());
        }

        // 日 K + 均线 + 量比
        try {
            Map<String, Object> resp = gateway.bars(code, 9, 25);
            Object dataObj = resp == null ? null : resp.get("data");
            if (dataObj instanceof List<?> bars && !bars.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) bars;
                double[] closes = rows.stream().mapToDouble(b -> toDouble(b.get("close"))).toArray();
                double[] vols = rows.stream().mapToDouble(b -> toDouble(b.get("vol"))).toArray();
                double last = closes[closes.length - 1];
                sb.append("MA5: ").append(avgTail(closes, 5))
                        .append("  MA10: ").append(avgTail(closes, 10))
                        .append("  MA20: ").append(avgTail(closes, 20)).append("\n");
                double p5 = closes.length >= 6 ? closes[closes.length - 6] : last;
                double p20 = closes.length >= 21 ? closes[closes.length - 21] : last;
                sb.append("5日涨跌: ").append(pct(last, p5)).append("%")
                        .append("  20日涨跌: ").append(pct(last, p20)).append("%\n");
                double todayVol = vols[vols.length - 1];
                double avgVol5 = avgTailRaw(vols, 5);
                sb.append("量比(今日/5日均): ").append(avgVol5 > 0 ? round(todayVol / avgVol5, 2) : 0).append("\n");

                int start = Math.max(0, rows.size() - 10);
                sb.append("最近 ").append(rows.size() - start).append(" 根日K (日期 开 高 低 收 量):\n");
                for (int i = start; i < rows.size(); i++) {
                    Map<String, Object> b = rows.get(i);
                    String dt = String.valueOf(b.getOrDefault("datetime", "?"));
                    sb.append("  ").append(dt.length() >= 10 ? dt.substring(0, 10) : dt)
                            .append(" ").append(b.get("open"))
                            .append(" ").append(b.get("high"))
                            .append(" ").append(b.get("low"))
                            .append(" ").append(b.get("close"))
                            .append(" ").append(b.get("vol")).append("\n");
                }
            }
        } catch (Exception e) {
            log.warn("[analyst] bars {} failed: {}", code, e.getMessage());
        }

        // 最新新闻
        try {
            Map<String, Object> news = gateway.stockNews(code, 5);
            Object itemsObj = news == null ? null : news.get("items");
            if (itemsObj instanceof List<?> items && !items.isEmpty()) {
                sb.append("最新新闻:\n");
                int n = 0;
                for (Object it : items) {
                    if (n++ >= 5) break;
                    if (it instanceof Map<?, ?> m) {
                        sb.append("  - ").append(m.get("time")).append(" ").append(m.get("title")).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[analyst] news {} failed: {}", code, e.getMessage());
        }

        return sb.toString();
    }

    /** 流式调 LLM：stream=true，逐行读 SSE chunk，把 delta.content 推给前端 emitter。 */
    private void streamLlm(List<Map<String, Object>> messages, SseEmitter emitter) {
        String url = stripSlash(props.getBaseUrl()) + "/v1/chat/completions";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", messages);
        body.put("temperature", 0.7);
        body.put("max_tokens", 1500);
        body.put("stream", true);

        try {
            String reqJson = MAPPER.writeValueAsString(body);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(reqJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String errBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                sendError(emitter, "LLM HTTP " + resp.statusCode() + ": " + truncate(errBody, 300));
                emitter.complete();
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    if ("[DONE]".equals(payload)) break;
                    String delta = extractDelta(payload);
                    if (delta != null && !delta.isEmpty()) {
                        emitter.send(SseEmitter.event().name("delta").data(delta, MediaType.TEXT_PLAIN));
                    }
                }
            }
            emitter.send(SseEmitter.event().name("done").data("ok"));
            emitter.complete();
        } catch (Exception e) {
            log.error("[analyst] stream llm failed: {}", e.getMessage());
            try { sendError(emitter, "LLM 流式调用失败: " + truncate(e.getMessage(), 200)); } catch (Exception ignore) { }
            emitter.complete();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractDelta(String jsonChunk) {
        try {
            Map<String, Object> m = MAPPER.readValue(jsonChunk, MAP_TYPE);
            Object choices = m.get("choices");
            if (choices instanceof List<?> arr && !arr.isEmpty() && arr.get(0) instanceof Map<?, ?> c) {
                Object deltaObj = ((Map<String, Object>) c).get("delta");
                if (deltaObj instanceof Map<?, ?> d) {
                    Object content = ((Map<String, Object>) d).get("content");
                    return content == null ? null : String.valueOf(content);
                }
            }
        } catch (Exception ignore) { }
        return null;
    }

    private void sendError(SseEmitter emitter, String msg) throws java.io.IOException {
        emitter.send(SseEmitter.event().name("error").data(msg, MediaType.TEXT_PLAIN));
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    // ---- 数值工具 ----
    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private static double avgTailRaw(double[] arr, int n) {
        if (arr.length == 0) return 0;
        int start = Math.max(0, arr.length - n);
        double sum = 0;
        for (int i = start; i < arr.length; i++) sum += arr[i];
        return sum / (arr.length - start);
    }

    private static String avgTail(double[] arr, int n) {
        if (arr.length < n) return "-";
        return String.valueOf(round(avgTailRaw(arr, n), 3));
    }

    private static String pct(double cur, double base) {
        if (base == 0) return "0";
        return String.valueOf(round((cur - base) / base * 100, 2));
    }

    private static double round(double v, int d) {
        double f = Math.pow(10, d);
        return Math.round(v * f) / f;
    }

    private static String stripSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
