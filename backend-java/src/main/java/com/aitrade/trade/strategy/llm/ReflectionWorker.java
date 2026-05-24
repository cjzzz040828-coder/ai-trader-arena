package com.aitrade.trade.strategy.llm;

import com.aitrade.entity.LlmDecisionMemory;
import com.aitrade.gateway.PythonGatewayClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 反思 worker：定期回查"N 天前的 LLM 决策实际兑现没"。
 *
 * 流程：
 *   1. 拉一批 verified_at IS NULL 且 created_at < now - min_age_days 的记录
 *   2. 对每条调 gateway.bars(code, 9=日K, horizon+8) 拉日 K
 *   3. 找 created_at 之后第 N 个交易日的 close（找不到时跳过，下个周期再试）
 *   4. 算 actual_return_pct = (close - price_at_decision) / price_at_decision * 100
 *   5. was_correct：BUY 且 return > +threshold 或 SELL 且 return < -threshold 视为对
 *   6. 写回 verified_at / price_after_horizon / actual_return_pct / was_correct
 */
@Slf4j
@Component
public class ReflectionWorker {

    private static final int FREQ_DAY_K = 9;

    private final DecisionMemoryService memoryService;
    private final PythonGatewayClient gateway;

    @Value("${reflection.horizon-days:5}")
    private int horizonDays;

    @Value("${reflection.threshold-pct:0.5}")
    private double thresholdPct;

    @Value("${reflection.min-age-days:5}")
    private int minAgeDays;

    @Value("${reflection.batch-size:200}")
    private int batchSize;

    public ReflectionWorker(DecisionMemoryService memoryService, PythonGatewayClient gateway) {
        this.memoryService = memoryService;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "${reflection.interval-ms:14400000}",
            initialDelayString = "${reflection.initial-delay-ms:1800000}")
    public void runOnce() {
        try {
            List<LlmDecisionMemory> pending = memoryService.getPendingForVerification(minAgeDays, batchSize);
            if (pending.isEmpty()) {
                log.debug("[reflection] no pending decisions");
                return;
            }
            int ok = 0, defer = 0, fail = 0;
            for (LlmDecisionMemory rec : pending) {
                try {
                    Outcome o = verifyOne(rec);
                    if (o == null) {
                        defer++;
                    } else {
                        memoryService.updateVerification(rec.getId(), horizonDays,
                                o.priceAfter, o.returnPct, o.wasCorrect);
                        ok++;
                    }
                } catch (Exception e) {
                    fail++;
                    log.warn("[reflection] verify id={} failed: {}", rec.getId(), e.getMessage());
                }
            }
            log.info("[reflection] cycle done: pending={} verified={} deferred={} failed={}",
                    pending.size(), ok, defer, fail);
        } catch (Exception e) {
            log.error("[reflection] runOnce failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 验证一条记录。返回 null 表示数据不够（停牌/新股/拉不到 N 根）需要下个周期再试。
     */
    Outcome verifyOne(LlmDecisionMemory rec) {
        String code = rec.getStockCode();
        // 多拉几根防节假日：horizon + 起点查找窗口 + 缓冲
        int barCount = Math.max(horizonDays + 30, 60);
        Map<String, Object> resp;
        try {
            resp = gateway.bars(code, FREQ_DAY_K, barCount);
        } catch (Exception e) {
            log.warn("[reflection] gateway.bars {} failed: {}", code, e.getMessage());
            return null;
        }
        Object data = resp == null ? null : resp.get("data");
        if (!(data instanceof List<?> arr) || arr.isEmpty()) return null;

        // 显式按 date 升序排序，不依赖 mootdx 默认顺序——它的版本升级可能改顺序，
        // 那样 startIdx + horizonDays 会反指到决策日"之前"，回报方向被反转。
        List<Map<?, ?>> sorted = new java.util.ArrayList<>();
        for (Object item : arr) {
            if (item instanceof Map<?, ?> row && dateOf(row) != null) sorted.add(row);
        }
        sorted.sort((a, b) -> {
            String da = dateOf(a), db = dateOf(b);
            return da.compareTo(db);
        });
        if (sorted.isEmpty()) return null;

        LocalDate decisionDay = rec.getCreatedAt().toLocalDate();

        // 找到 >= decisionDay 的第一根（决策日 close 或下一交易日 open 都视为"决策日基线"）
        int startIdx = -1;
        for (int i = 0; i < sorted.size(); i++) {
            Map<?, ?> row = sorted.get(i);
            String dateStr = dateOf(row);
            try {
                LocalDate d = LocalDate.parse(dateStr);
                if (!d.isBefore(decisionDay)) {
                    startIdx = i;
                    break;
                }
            } catch (Exception ignore) { }
        }
        if (startIdx < 0) return null;

        int targetIdx = startIdx + horizonDays;
        if (targetIdx >= sorted.size()) return null; // 还没到 horizon，下次再试

        Map<?, ?> targetRow = sorted.get(targetIdx);
        BigDecimal closeAfter = numVal(targetRow.get("close"));
        if (closeAfter == null || closeAfter.signum() <= 0) return null;

        BigDecimal base = rec.getPriceAtDecision();
        if (base == null || base.signum() <= 0) return null;

        BigDecimal returnPct = closeAfter.subtract(base)
                .multiply(BigDecimal.valueOf(100))
                .divide(base, 4, RoundingMode.HALF_UP);

        double retD = returnPct.doubleValue();
        int wasCorrect = 0;
        if ("BUY".equalsIgnoreCase(rec.getSide()) && retD > thresholdPct) wasCorrect = 1;
        else if ("SELL".equalsIgnoreCase(rec.getSide()) && retD < -thresholdPct) wasCorrect = 1;

        return new Outcome(closeAfter, returnPct, wasCorrect);
    }

    private static String dateOf(Map<?, ?> bar) {
        Object dt = bar.get("datetime");
        if (dt == null) dt = bar.get("date");
        if (dt == null) return null;
        String s = String.valueOf(dt);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    private static BigDecimal numVal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record Outcome(BigDecimal priceAfter, BigDecimal returnPct, int wasCorrect) {}
}
