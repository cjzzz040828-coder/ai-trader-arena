package com.aitrade.trade.strategy.llm;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 只认匹配的 decisionId 才算 cancelled，避免上一次决策被取消的标志影响下一次。
 * scheduler / decide-now 进入 decide() 时拿到自己的 decisionId，每次检查时带上。
 */
@Component
public class LlmCancelRegistry {

    private final ConcurrentHashMap<Long, Long> cancelledDecisionByTrader = new ConcurrentHashMap<>();

    public void requestCancel(long traderId, long decisionId) {
        cancelledDecisionByTrader.put(traderId, decisionId);
    }

    public boolean isCancelled(long traderId, long decisionId) {
        Long c = cancelledDecisionByTrader.get(traderId);
        return c != null && c == decisionId;
    }

    /** 决策结束时调用，清除该 trader 上残留的取消标志（如果是匹配该 decision 的）。 */
    public void clear(long traderId, long decisionId) {
        cancelledDecisionByTrader.remove(traderId, decisionId);
    }
}
