package com.aitrade.trade.strategy;

import com.aitrade.entity.AiTrader;

import java.util.List;

/**
 * 一个 Executor 处理一个 strategy_type（MA / LLM）。
 * 实现要做到：
 *   - 只读 MarketContext，不直接访问数据库（除了自己的 trader 持仓状态）
 *   - 内部抛出的任何异常由 StrategyScheduler 隔离，不影响其它 trader
 *   - 返回的信号交给 StrategyOrchestrator 落单（含重复信号 / T+1 兜底）
 */
public interface StrategyExecutor {
    String strategyType();
    List<Signal> decide(AiTrader trader, MarketContext ctx);
}
