package com.aitrade.trade;

import com.aitrade.auth.CurrentUser;
import com.aitrade.entity.LlmActivity;
import com.aitrade.mapper.LlmActivityMapper;
import com.aitrade.trade.strategy.llm.LlmActivityPublisher;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户级 LLM 活动端点。dashboard 驾驶舱用，一次订阅当前用户的所有 LLM trader 事件。
 * 详情页/单 trader 用 LlmActivityController 的 trader 级端点。
 */
@RestController
@RequestMapping("/api/llm-activity")
@RequiredArgsConstructor
public class UserLlmActivityController {

    private static final int DEFAULT_PER_TRADER = 20;
    private static final int MAX_PER_TRADER = 200;

    private final TraderService traderService;
    private final LlmActivityPublisher publisher;
    private final LlmActivityMapper mapper;

    /** 订阅当前用户所有 LLM trader 的实时活动流。不做 in-flight 回放，基线走 /recent。 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@CurrentUser Long userId) {
        return publisher.subscribeUser(userId);
    }

    /** 聚合查询：当前用户所有 LLM trader 的最近 N 条活动，按 traderId 分组。dashboard 基线用。 */
    @GetMapping("/recent")
    public Map<Long, List<LlmActivity>> recent(@CurrentUser Long userId,
                                               @RequestParam(defaultValue = "20") int perTrader) {
        if (perTrader <= 0) perTrader = DEFAULT_PER_TRADER;
        if (perTrader > MAX_PER_TRADER) perTrader = MAX_PER_TRADER;

        List<Long> traderIds = traderService.listMyLlmTraderIds(userId);
        Map<Long, List<LlmActivity>> result = new LinkedHashMap<>();
        for (Long tid : traderIds) {
            List<LlmActivity> rows = mapper.selectList(new QueryWrapper<LlmActivity>()
                    .eq("trader_id", tid)
                    .orderByDesc("id")
                    .last("LIMIT " + perTrader));
            Collections.reverse(rows);
            result.put(tid, rows);
        }
        return result;
    }

    /** 清空当前用户所有 LLM 活动记录（不可恢复）。进行中的决策不会被中断。 */
    @DeleteMapping
    public Map<String, Object> clearAll(@CurrentUser Long userId) {
        int deleted = publisher.clearForUser(userId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("deleted", deleted);
        return r;
    }
}
