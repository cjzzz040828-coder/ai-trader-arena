package com.aitrade.trade.strategy.llm;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.LlmDecisionMemory;
import com.aitrade.mapper.LlmDecisionMemoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 决策记忆服务：
 *   - recordPlace 异步落 llm_decision_memory（每次 LLM place_order 成功一条）
 *   - updateReason 决策结束后回填本轮所有记录的 reason
 *   - formatRecentSummary 给 prompt 拼"过去 N 天回顾"段
 *   - getPendingForVerification 给 ReflectionWorker 用
 *   - updateVerification 给 ReflectionWorker 回写结果
 */
@Slf4j
@Component
public class DecisionMemoryService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final LlmDecisionMemoryMapper mapper;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "llm-decision-memory-writer");
        t.setDaemon(true);
        return t;
    });

    public DecisionMemoryService(LlmDecisionMemoryMapper mapper) {
        this.mapper = mapper;
    }

    @PreDestroy
    public void shutdown() {
        writer.shutdown();
    }

    /**
     * place_order 成功后异步写入决策快照。args 从 LLM tool_call 来。
     * 不阻塞 LLM 决策线程。
     */
    public void recordPlace(AiTrader trader, long decisionId, Map<String, Object> args,
                            Long orderId, BigDecimal priceAtDecision, String indicatorsSnapshot) {
        if (trader == null || priceAtDecision == null) return;
        String code = strVal(args.get("code"));
        String side = strVal(args.get("side"));
        Object amt = args.get("amount");
        if (code == null || side == null || !(amt instanceof Number)) return;

        LlmDecisionMemory rec = new LlmDecisionMemory();
        rec.setTraderId(trader.getId());
        rec.setUserId(trader.getUserId());
        rec.setDecisionId(decisionId);
        rec.setOrderId(orderId);
        rec.setStockCode(code);
        rec.setSide(side.toUpperCase());
        rec.setAmount(((Number) amt).intValue());
        rec.setPriceAtDecision(priceAtDecision);
        rec.setIndicatorsSnapshot(indicatorsSnapshot);
        rec.setCreatedAt(LocalDateTime.now());

        writer.submit(() -> {
            try {
                mapper.insert(rec);
            } catch (Exception e) {
                log.warn("[decision-memory] insert failed trader={} code={}: {}",
                        trader.getId(), code, e.getMessage());
            }
        });
    }

    /** 决策结束时把 LLM 最终 message 回填到本轮所有记录的 reason。 */
    public void updateReason(long decisionId, String reason) {
        if (reason == null || reason.isBlank()) return;
        String trimmed = reason.length() > 1000 ? reason.substring(0, 1000) : reason;
        writer.submit(() -> {
            try {
                UpdateWrapper<LlmDecisionMemory> w = new UpdateWrapper<>();
                w.eq("decision_id", decisionId).set("reason", trimmed);
                mapper.update(null, w);
            } catch (Exception e) {
                log.warn("[decision-memory] update reason failed decision={}: {}", decisionId, e.getMessage());
            }
        });
    }

    /**
     * 给 prompt 拼回顾段。包含 BUY/SELL 命中率、平均回报、最近 5 条已验证决策。
     * 没有任何已验证数据时返回空串（caller 不追加）。
     */
    public String formatRecentSummary(long traderId, int sinceDays) {
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(sinceDays);
            List<LlmDecisionMemory> verified = mapper.selectList(new QueryWrapper<LlmDecisionMemory>()
                    .eq("trader_id", traderId)
                    .ge("created_at", since)
                    .isNotNull("verified_at")
                    .orderByDesc("created_at"));
            if (verified.isEmpty()) return "";

            int buyTotal = 0, buyHit = 0, sellTotal = 0, sellHit = 0;
            double buyRetSum = 0.0, sellRetSum = 0.0;
            for (LlmDecisionMemory r : verified) {
                BigDecimal ret = r.getActualReturnPct();
                double retD = ret == null ? 0.0 : ret.doubleValue();
                boolean hit = r.getWasCorrect() != null && r.getWasCorrect() == 1;
                if ("BUY".equalsIgnoreCase(r.getSide())) {
                    buyTotal++;
                    buyRetSum += retD;
                    if (hit) buyHit++;
                } else if ("SELL".equalsIgnoreCase(r.getSide())) {
                    sellTotal++;
                    sellRetSum += retD;
                    if (hit) sellHit++;
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n# 你过去 ").append(sinceDays).append(" 天的决策回顾（用于自我校准）\n");
            if (buyTotal > 0) {
                sb.append("- BUY 决策 ").append(buyTotal).append(" 次，准确率 ")
                        .append(String.format("%.1f%%", buyHit * 100.0 / buyTotal))
                        .append("，平均回报 ").append(String.format("%+.2f%%", buyRetSum / buyTotal))
                        .append("\n");
            }
            if (sellTotal > 0) {
                sb.append("- SELL 决策 ").append(sellTotal).append(" 次，准确率 ")
                        .append(String.format("%.1f%%", sellHit * 100.0 / sellTotal))
                        .append("，平均回报 ").append(String.format("%+.2f%%", sellRetSum / sellTotal))
                        .append("\n");
            }
            int n = Math.min(5, verified.size());
            sb.append("- 最近 ").append(n).append(" 条已验证决策：\n");
            for (int i = 0; i < n; i++) {
                LlmDecisionMemory r = verified.get(i);
                String mark = (r.getWasCorrect() != null && r.getWasCorrect() == 1) ? "✓" : "✗";
                double ret = r.getActualReturnPct() == null ? 0.0 : r.getActualReturnPct().doubleValue();
                sb.append("  · ").append(r.getCreatedAt().format(DAY_FMT))
                        .append(" ").append(String.format("%-4s", r.getSide()))
                        .append(" ").append(r.getStockCode())
                        .append(" @").append(r.getPriceAtDecision().toPlainString())
                        .append(" → ").append(r.getVerifyHorizonDays() == null ? "?" : r.getVerifyHorizonDays())
                        .append("日后 ").append(r.getPriceAfterHorizon() == null ? "?" : r.getPriceAfterHorizon().toPlainString())
                        .append(" (").append(String.format("%+.2f%%", ret)).append(") ").append(mark).append("\n");
            }
            sb.append("请参考上述命中率，对当前判断做适度自我调整：低准确率方向请提高确信度门槛或减小仓位。\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[decision-memory] formatRecentSummary failed trader={}: {}", traderId, e.getMessage());
            return "";
        }
    }

    /** 拉一批待验证记录给 ReflectionWorker。 */
    public List<LlmDecisionMemory> getPendingForVerification(int minAgeDays, int limit) {
        LocalDateTime before = LocalDateTime.now().minusDays(minAgeDays);
        return mapper.selectList(new QueryWrapper<LlmDecisionMemory>()
                .isNull("verified_at")
                .lt("created_at", before)
                .orderByAsc("created_at")
                .last("LIMIT " + Math.max(1, limit)));
    }

    /** ReflectionWorker 回写验证结果。 */
    public void updateVerification(long id, int horizonDays, BigDecimal priceAfter,
                                   BigDecimal returnPct, int wasCorrect) {
        try {
            UpdateWrapper<LlmDecisionMemory> w = new UpdateWrapper<>();
            w.eq("id", id)
                    .set("verified_at", LocalDateTime.now())
                    .set("verify_horizon_days", horizonDays)
                    .set("price_after_horizon", priceAfter)
                    .set("actual_return_pct", returnPct)
                    .set("was_correct", wasCorrect);
            mapper.update(null, w);
        } catch (Exception e) {
            log.warn("[decision-memory] updateVerification failed id={}: {}", id, e.getMessage());
        }
    }

    private static String strVal(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
