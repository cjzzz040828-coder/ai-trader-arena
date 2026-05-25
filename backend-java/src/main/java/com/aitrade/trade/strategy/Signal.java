package com.aitrade.trade.strategy;

/**
 * 单条交易信号。reason 仅用于日志/审计，不持久化。
 *
 * targetAmount：可空。
 *   - null：BUY 走 Orchestrator 默认仓位规则（可用余额 × BUY_FRACTION）；SELL 走"全仓"
 *   - 非空：策略要求精确股数（必须为 100 的整数倍），跳过默认规则
 *
 * 多因子策略需要"等权 topN 再平衡"，每仓金额 = 总资产/topN，不能用固定 10%，故走 targetAmount。
 */
public record Signal(String stockCode, String side, String reason, Integer targetAmount) {
    public static Signal buy(String code, String reason) {
        return new Signal(code, "BUY", reason, null);
    }
    public static Signal sell(String code, String reason) {
        return new Signal(code, "SELL", reason, null);
    }
    public static Signal buyWithAmount(String code, int amount, String reason) {
        return new Signal(code, "BUY", reason, amount);
    }
    public static Signal sellWithAmount(String code, int amount, String reason) {
        return new Signal(code, "SELL", reason, amount);
    }
}
