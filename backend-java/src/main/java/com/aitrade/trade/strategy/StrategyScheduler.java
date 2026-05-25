package com.aitrade.trade.strategy;

import com.aitrade.entity.AiTrader;
import com.aitrade.gateway.PythonGatewayClient;
import com.aitrade.trade.TraderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 周期触发 + 开盘边沿补一次 tick。
 *
 * - 主调度：每 ${strategy.interval-ms:60000}ms 拉市场视图、串行喂所有 enabled+!deleted+strategy_type∈(MA,LLM,INDICATOR) 的 trader
 * - 边沿监听：每 5s 轻量 health 探一次 market_status；只要 status 变化且新状态 tradable（OPEN/PREMARKET）就补一次 tick
 *   ↳ 覆盖 9:15 集合竞价、9:30 真开盘、12:57 午后预热、13:00 下午开盘四个边沿，避免最长 60s 的调度延迟。
 * - 非交易时段（market_status 既不是 OPEN 也不是 PREMARKET）整轮跳过
 * - 主调度和 edge watcher 共享 running 锁，避免并发执行 tick
 * - PREMARKET（9:15-9:30 / 12:57-13:00）也允许决策，单子挂 PENDING 等 MatchEngine 在 OPEN 后撮合
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyScheduler {

    private final TraderService traderService;
    private final PythonGatewayClient gateway;
    private final StrategyOrchestrator orchestrator;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> lastObservedStatus = new AtomicReference<>("INIT");

    @Scheduled(fixedDelayString = "${strategy.interval-ms:60000}",
            initialDelayString = "${strategy.initial-delay-ms:15000}")
    public void run() {
        executeTick("periodic");
    }

    /**
     * 每 5 秒轻量探一次 gateway health 的 market_status，边沿检测：status 变化且新状态 tradable 就补 tick。
     * 覆盖 CLOSED→PREMARKET（9:15）、PREMARKET→OPEN（9:30）、BREAK→PREMARKET（12:57）、PREMARKET→OPEN（13:00）。
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 20_000)
    public void edgeWatcher() {
        String status;
        try {
            Map<String, Object> health = gateway.health();
            Object ms = health == null ? null : health.get("market_status");
            status = ms == null ? "UNKNOWN" : String.valueOf(ms).toUpperCase();
        } catch (Exception e) {
            log.debug("[strategy-sched] edge watcher health failed: {}", e.getMessage());
            return;
        }
        String prev = lastObservedStatus.getAndSet(status);
        if (prev.equals(status)) return;
        if (isTradable(status)) {
            log.info("[strategy-sched] market edge {} → {}, firing extra tick", prev, status);
            executeTick("edge:" + prev + "→" + status);
        } else {
            log.debug("[strategy-sched] market status {} → {} (no tick)", prev, status);
        }
    }

    private static boolean isTradable(String s) {
        return "OPEN".equals(s) || "PREMARKET".equals(s);
    }

    /** 主 tick 实现。同时只允许一个 caller 执行。 */
    private void executeTick(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.debug("[strategy-sched] tick already running, skip trigger={}", trigger);
            return;
        }
        try {
            doTick(trigger);
        } finally {
            running.set(false);
        }
    }

    private void doTick(String trigger) {
        List<AiTrader> traders;
        try {
            traders = traderService.listForStrategy();
        } catch (Exception e) {
            log.error("[strategy-sched] load traders failed: {}", e.getMessage(), e);
            return;
        }
        if (traders.isEmpty()) {
            log.debug("[strategy-sched] no enabled trader, skip ({})", trigger);
            return;
        }

        // 按 poolName 分组（null/blank 归到 __default__）。每组共享一个 MarketContext 减少 watchlist/snapshot 重复请求。
        Map<String, List<AiTrader>> byPool = new LinkedHashMap<>();
        for (AiTrader t : traders) {
            String key = (t.getPoolName() == null || t.getPoolName().isBlank()) ? "__default__" : t.getPoolName();
            byPool.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(t);
        }

        log.info("[strategy-sched] tick start [{}]: {} traders across {} pool(s) {}",
                trigger, traders.size(), byPool.size(), byPool.keySet());
        long t0 = System.currentTimeMillis();
        boolean anyOpen = false;

        for (Map.Entry<String, List<AiTrader>> grp : byPool.entrySet()) {
            String poolKey = grp.getKey();
            List<AiTrader> group = grp.getValue();
            String poolName = "__default__".equals(poolKey) ? null : poolKey;
            MarketContext ctx;
            try {
                ctx = new MarketContext(gateway, false, poolName);
            } catch (Exception e) {
                log.error("[strategy-sched] build market context pool={} failed: {}", poolKey, e.getMessage());
                continue;
            }
            if (!ctx.isMarketOpen()) {
                // 多池子时 marketStatus 应一致（来自 snapshot），只 log 一次足够，但保留 per-pool 跳过提示
                log.info("[strategy-sched] market status={} pool={} not tradable, skip {} trader(s)",
                        ctx.marketStatus(), poolKey, group.size());
                continue;
            }
            if (ctx.watchlist().isEmpty()) {
                log.warn("[strategy-sched] pool={} watchlist empty, skip {} trader(s)", poolKey, group.size());
                continue;
            }
            anyOpen = true;
            log.info("[strategy-sched] pool={} status={} {} traders, {} codes",
                    poolKey, ctx.marketStatus(), group.size(), ctx.watchlist().size());
            for (AiTrader t : group) {
                try {
                    orchestrator.runOnce(t, ctx);
                } catch (Exception e) {
                    log.error("[strategy-sched] trader {} unhandled: {}", t.getId(), e.getMessage(), e);
                }
            }
        }
        if (!anyOpen) {
            log.info("[strategy-sched] no pool tradable, tick done [{}] in {}ms", trigger, System.currentTimeMillis() - t0);
        } else {
            log.info("[strategy-sched] tick done [{}] in {}ms", trigger, System.currentTimeMillis() - t0);
        }
    }
}
