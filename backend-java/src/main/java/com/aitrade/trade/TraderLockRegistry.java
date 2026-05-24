package com.aitrade.trade;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 按 traderId 隔离的全局锁池。OrderService 与 MatchEngine 共用，
 * 保证"下单/撤单"与"撮合成交"对同一 trader 不会并发改 balance/frozen/持仓，
 * 维护资金守恒：balance + frozen_balance + Σ持仓市值 == initial_balance + total_profit。
 */
@Component
public class TraderLockRegistry {

    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

    public <T> T withLock(Long traderId, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(traderId, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }

    public void withLockVoid(Long traderId, Runnable action) {
        Object lock = locks.computeIfAbsent(traderId, k -> new Object());
        synchronized (lock) {
            action.run();
        }
    }
}
