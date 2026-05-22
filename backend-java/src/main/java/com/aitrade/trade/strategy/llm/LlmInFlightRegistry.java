package com.aitrade.trade.strategy.llm;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 防止同一个 trader 被两个决策并发跑（scheduler + 手动 decide-now 同时触发）。
 * 进入 decide() 时 tryAcquire；失败方直接放弃，发 failed 事件。
 */
@Component
public class LlmInFlightRegistry {

    private final ConcurrentHashMap<Long, Long> currentDecisionByTrader = new ConcurrentHashMap<>();

    public boolean tryAcquire(long traderId, long decisionId) {
        return currentDecisionByTrader.putIfAbsent(traderId, decisionId) == null;
    }

    public void release(long traderId) {
        currentDecisionByTrader.remove(traderId);
    }

    /** 给 cancel endpoint 用：当前 trader 正在跑哪个 decision，没有返回 null。 */
    public Long current(long traderId) {
        return currentDecisionByTrader.get(traderId);
    }
}
