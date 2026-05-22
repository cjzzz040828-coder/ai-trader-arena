package com.aitrade.trade;

import com.aitrade.auth.CurrentUser;
import com.aitrade.common.ApiException;
import com.aitrade.entity.LlmActivity;
import com.aitrade.mapper.LlmActivityMapper;
import com.aitrade.trade.strategy.llm.LlmActivityEvent;
import com.aitrade.trade.strategy.llm.LlmActivityPublisher;
import com.aitrade.trade.strategy.llm.LlmCancelRegistry;
import com.aitrade.trade.strategy.llm.LlmInFlightRegistry;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/traders/{traderId}")
@RequiredArgsConstructor
public class LlmActivityController {

    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LIMIT = 200;

    private final TraderService traderService;
    private final LlmActivityPublisher publisher;
    private final LlmCancelRegistry cancelRegistry;
    private final LlmInFlightRegistry inFlightRegistry;
    private final LlmActivityMapper mapper;

    /** 订阅某 trader 的 LLM 决策实时活动流。EventSource 通过 ?token=xxx 鉴权。 */
    @GetMapping(value = "/llm-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long traderId, @CurrentUser Long userId) {
        traderService.getOwned(traderId, userId);
        return publisher.subscribe(traderId);
    }

    /** 请求中断当前正在跑的决策（如果有）。下一轮工具调用前生效。 */
    @PostMapping("/llm-cancel")
    public Map<String, Object> cancel(@PathVariable Long traderId, @CurrentUser Long userId) {
        traderService.getOwned(traderId, userId);
        Long decisionId = inFlightRegistry.current(traderId);
        Map<String, Object> r = new LinkedHashMap<>();
        if (decisionId == null) {
            r.put("requested", false);
            r.put("reason", "当前没有正在运行的决策");
            return r;
        }
        cancelRegistry.requestCancel(traderId, decisionId);
        LlmActivityEvent ev = new LlmActivityEvent(
                traderId, userId, decisionId,
                publisher.nextSeq(decisionId),
                "cancel_requested", null, null, null, null, null,
                "已发送停止请求，等待当前轮结束",
                LocalDateTime.now().toString());
        publisher.publish(ev);
        r.put("requested", true);
        r.put("decisionId", decisionId);
        return r;
    }

    /** 查历史活动。前端打开面板时回填。 */
    @GetMapping("/llm-activities")
    public List<LlmActivity> activities(@PathVariable Long traderId,
                                        @CurrentUser Long userId,
                                        @RequestParam(defaultValue = "200") int limit,
                                        @RequestParam(required = false) Long sinceId) {
        traderService.getOwned(traderId, userId);
        if (limit <= 0) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        QueryWrapper<LlmActivity> qw = new QueryWrapper<LlmActivity>()
                .eq("trader_id", traderId)
                .orderByDesc("id")
                .last("LIMIT " + limit);
        if (sinceId != null) qw.gt("id", sinceId);
        List<LlmActivity> rows = mapper.selectList(qw);
        // 前端按 id 升序渲染：这里反转
        java.util.Collections.reverse(rows);
        return rows;
    }
}
