package com.aitrade.trade.strategy;

import com.aitrade.entity.AiTrader;

import java.util.Map;

/**
 * LLM Executor 使用的工具抽象。线上有 LlmTools（连真 OrderService）、回测有 BacktestTools（操作内存沙箱）。
 * LlmStrategyExecutor 可以接受任意 ToolBox 实现完成同一份 prompt 的决策循环。
 */
public interface ToolBox {
    Map<String, Object> getStockAnalysis(AiTrader trader, MarketContext ctx, Map<String, Object> args);
    Map<String, Object> getMinuteChart(AiTrader trader, MarketContext ctx, Map<String, Object> args);
    Map<String, Object> getRecentTrades(AiTrader trader, MarketContext ctx, Map<String, Object> args);
    Map<String, Object> placeOrder(AiTrader trader, MarketContext ctx, Map<String, Object> args);
}
