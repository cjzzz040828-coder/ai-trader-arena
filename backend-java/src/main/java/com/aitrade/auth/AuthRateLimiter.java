package com.aitrade.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极简内存级 IP 限频：滑动窗口。给 /api/auth/* 用，挡字典攻击 + 注册爆刷。
 * 单实例部署够用；集群部署需要换 Redis。
 */
@Component
public class AuthRateLimiter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_PER_WINDOW = 10;

    private final ConcurrentHashMap<String, Deque<Long>> hitsByKey = new ConcurrentHashMap<>();

    /** 返回 true 表示允许；false 表示已超频。 */
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> hits = hitsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() > WINDOW_MS) {
                hits.pollFirst();
            }
            if (hits.size() >= MAX_PER_WINDOW) return false;
            hits.addLast(now);
            return true;
        }
    }
}
