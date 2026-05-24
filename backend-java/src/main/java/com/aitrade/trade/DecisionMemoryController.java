package com.aitrade.trade;

import com.aitrade.auth.CurrentUser;
import com.aitrade.entity.LlmDecisionMemory;
import com.aitrade.mapper.LlmDecisionMemoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 决策记忆查询：当前用户的历史决策 + 反思结果（命中率、N 日实际收益）。
 */
@RestController
@RequestMapping("/api/decision-memory")
@RequiredArgsConstructor
public class DecisionMemoryController {

    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 500;

    private final LlmDecisionMemoryMapper mapper;

    /** 当前用户的决策记忆列表（最新在前）。 */
    @GetMapping
    public List<LlmDecisionMemory> list(@CurrentUser Long userId,
                                        @RequestParam(required = false) Long traderId,
                                        @RequestParam(required = false) Boolean verifiedOnly,
                                        @RequestParam(defaultValue = "50") int size) {
        if (size <= 0) size = DEFAULT_SIZE;
        if (size > MAX_SIZE) size = MAX_SIZE;

        QueryWrapper<LlmDecisionMemory> q = new QueryWrapper<LlmDecisionMemory>()
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .last("LIMIT " + size);
        if (traderId != null) q.eq("trader_id", traderId);
        if (Boolean.TRUE.equals(verifiedOnly)) q.isNotNull("verified_at");
        return mapper.selectList(q);
    }

    /** 命中率汇总：BUY/SELL 各自的总数、已验证数、对的数量、平均回报。 */
    @GetMapping("/stats")
    public Map<String, Object> stats(@CurrentUser Long userId,
                                     @RequestParam(required = false) Long traderId,
                                     @RequestParam(defaultValue = "30") int sinceDays) {
        QueryWrapper<LlmDecisionMemory> q = new QueryWrapper<LlmDecisionMemory>()
                .eq("user_id", userId)
                .isNotNull("verified_at")
                .gt("created_at", java.time.LocalDateTime.now().minusDays(Math.max(1, sinceDays)));
        if (traderId != null) q.eq("trader_id", traderId);
        List<LlmDecisionMemory> rows = mapper.selectList(q);

        int buyTotal = 0, buyHit = 0, sellTotal = 0, sellHit = 0;
        double buyRetSum = 0.0, sellRetSum = 0.0;
        for (LlmDecisionMemory r : rows) {
            double ret = r.getActualReturnPct() == null ? 0.0 : r.getActualReturnPct().doubleValue();
            boolean hit = r.getWasCorrect() != null && r.getWasCorrect() == 1;
            if ("BUY".equalsIgnoreCase(r.getSide())) {
                buyTotal++;
                buyRetSum += ret;
                if (hit) buyHit++;
            } else if ("SELL".equalsIgnoreCase(r.getSide())) {
                sellTotal++;
                sellRetSum += ret;
                if (hit) sellHit++;
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sinceDays", sinceDays);
        r.put("buyTotal", buyTotal);
        r.put("buyHit", buyHit);
        r.put("buyAccuracy", buyTotal == 0 ? 0 : Math.round(buyHit * 10000.0 / buyTotal) / 100.0);
        r.put("buyAvgReturnPct", buyTotal == 0 ? 0 : Math.round(buyRetSum * 100.0 / buyTotal) / 100.0);
        r.put("sellTotal", sellTotal);
        r.put("sellHit", sellHit);
        r.put("sellAccuracy", sellTotal == 0 ? 0 : Math.round(sellHit * 10000.0 / sellTotal) / 100.0);
        r.put("sellAvgReturnPct", sellTotal == 0 ? 0 : Math.round(sellRetSum * 100.0 / sellTotal) / 100.0);
        return r;
    }
}
