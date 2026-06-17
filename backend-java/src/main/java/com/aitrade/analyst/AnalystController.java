package com.aitrade.analyst;

import com.aitrade.auth.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dashboard 分析助手 SSE 端点。
 * EventSource 只能 GET 且无法加 header，token 走 ?token= query（JwtAuthFilter SSE 白名单已放行）。
 */
@Slf4j
@RestController
@RequestMapping("/api/analyst")
@RequiredArgsConstructor
public class AnalystController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, String>>> HISTORY_TYPE = new TypeReference<>() {};
    // 分析是 IO 密集 + 流式长连接，单独线程池跑，不占 Tomcat 工作线程。
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "analyst-sse");
        t.setDaemon(true);
        return t;
    });

    private final AnalystService analystService;

    /**
     * 发起一轮分析（流式）。
     * @param code    当前股票代码
     * @param q       用户提问
     * @param history 历史对话 JSON（前端维护，URL 编码的 [{role,content}]），可空
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String code,
                             @RequestParam(required = false, defaultValue = "") String q,
                             @RequestParam(required = false) String history,
                             @CurrentUser Long userId) {
        SseEmitter emitter = new SseEmitter(180_000L); // 3 分钟超时

        List<Map<String, String>> hist = List.of();
        if (history != null && !history.isBlank()) {
            try {
                hist = MAPPER.readValue(history, HISTORY_TYPE);
            } catch (Exception e) {
                log.debug("[analyst] bad history json, ignored: {}", e.getMessage());
            }
        }

        final List<Map<String, String>> finalHist = hist;
        emitter.onError(ex -> log.debug("[analyst] sse error: {}", ex.getMessage()));
        executor.submit(() -> analystService.chat(code, finalHist, q, emitter));
        return emitter;
    }
}
