package com.aitrade.trade.strategy;

import com.aitrade.entity.AiTrader;
import com.aitrade.gateway.PythonGatewayClient;
import com.aitrade.trade.TraderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每分钟跑一次：拉一次市场视图，串行喂给所有 enabled+!deleted+strategy_type∈(MA,LLM) 的 trader。
 *
 * - 一个 trader 失败不影响其它（异常都被 Orchestrator 吃掉）
 * - 非交易时段（snapshot.market_status != OPEN）整轮跳过
 * - 串行的另一个目的：避免多个 trader 并发触发 OrderService 的同一把 traderId 锁（其实锁是按 trader 隔离的，
 *   但 SQLite 写入串行更稳，省去并发写带来的"database is locked"）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyScheduler {

    private final TraderService traderService;
    private final PythonGatewayClient gateway;
    private final StrategyOrchestrator orchestrator;

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void run() {
        List<AiTrader> traders;
        try {
            traders = traderService.listForStrategy();
        } catch (Exception e) {
            log.error("[strategy-sched] load traders failed: {}", e.getMessage(), e);
            return;
        }
        if (traders.isEmpty()) {
            log.debug("[strategy-sched] no enabled trader, skip");
            return;
        }

        MarketContext ctx;
        try {
            ctx = new MarketContext(gateway);
        } catch (Exception e) {
            log.error("[strategy-sched] build market context failed: {}", e.getMessage());
            return;
        }
        if (!ctx.isMarketOpen()) {
            log.info("[strategy-sched] market closed, skip ({} trader candidates)", traders.size());
            return;
        }
        if (ctx.watchlist().isEmpty()) {
            log.warn("[strategy-sched] watchlist empty, skip");
            return;
        }

        log.info("[strategy-sched] tick start: {} traders, {} watchlist codes",
                traders.size(), ctx.watchlist().size());
        long t0 = System.currentTimeMillis();
        for (AiTrader t : traders) {
            try {
                orchestrator.runOnce(t, ctx);
            } catch (Exception e) {
                log.error("[strategy-sched] trader {} unhandled: {}", t.getId(), e.getMessage(), e);
            }
        }
        log.info("[strategy-sched] tick done in {}ms", System.currentTimeMillis() - t0);
    }
}
