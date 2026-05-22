package com.aitrade.trade.strategy;

/**
 * 单条交易信号。reason 仅用于日志/审计，不持久化。
 */
public record Signal(String stockCode, String side, String reason) {
    public static Signal buy(String code, String reason) { return new Signal(code, "BUY", reason); }
    public static Signal sell(String code, String reason) { return new Signal(code, "SELL", reason); }
}
