package com.aitrade.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回测撮合沙盒回归测试。
 * 重点防 P1-2（限价 BUY 单 open > limit 强行成交）的回归。
 *
 * 交易成本：BUY +0.125%（滑点 + 佣金），SELL -0.175%（滑点 + 佣金 + 印花税）。
 * 因此 BUY 后 equity 会略低于 INIT（建仓即"亏"成本），SELL 实现盈亏含费用。
 */
class BacktestSandboxTest {

    private static final BigDecimal INIT = new BigDecimal("1000000");

    @Test
    void buy_openAboveLimit_cancelled_balanceRestored() {
        // limit=10, open=11（跳空高开 10%），按限价单语义应不成交，frozen 全额退回
        BacktestSandbox sb = new BacktestSandbox(INIT);
        assertTrue(sb.enqueueBuy("000001", 100, new BigDecimal("10")));
        // 入队后 frozen=1000
        assertEquals(0, sb.getFrozenBalance().compareTo(new BigDecimal("1000")));
        assertEquals(0, sb.getBalance().compareTo(new BigDecimal("999000")));

        List<BacktestSandbox.Fill> fills = sb.settleAtOpen(Map.of("000001", new BigDecimal("11")));

        assertTrue(fills.isEmpty(), "open > limit 时不应有成交");
        assertEquals(0, sb.getFrozenBalance().compareTo(BigDecimal.ZERO), "frozen 应清零");
        assertEquals(0, sb.getBalance().compareTo(INIT), "balance 应完全还原");
        assertNull(sb.position("000001"), "不应建仓");
        assertEquals(0, sb.equity(Map.of("000001", new BigDecimal("11"))).compareTo(INIT));
    }

    @Test
    void buy_openBelowLimit_filledAndRefundExtra() {
        // limit=10, open=9（跳空低开），按 open=9 成交
        // grossCost = 900, BUY 成本 0.125% → actualCost = 901.13, diff = 1000 - 901.13 = 98.87 退回
        BacktestSandbox sb = new BacktestSandbox(INIT);
        sb.enqueueBuy("000001", 100, new BigDecimal("10"));

        List<BacktestSandbox.Fill> fills = sb.settleAtOpen(Map.of("000001", new BigDecimal("9")));

        assertEquals(1, fills.size());
        assertEquals("BUY", fills.get(0).side());
        assertEquals(0, fills.get(0).price().compareTo(new BigDecimal("9")));
        assertEquals(0, sb.getFrozenBalance().compareTo(BigDecimal.ZERO));
        // 999000（冻结后） + 98.87（多冻退回）= 999098.87
        assertEquals(0, sb.getBalance().compareTo(new BigDecimal("999098.87")));
        assertNotNull(sb.position("000001"));
        assertEquals(100, sb.position("000001").amount);
        // equity at 9: 999098.87 + 100*9 = 999998.87 （比 INIT 少 1.13 元 = 成本）
        assertEquals(0, sb.equity(Map.of("000001", new BigDecimal("9")))
                .compareTo(new BigDecimal("999998.87")));
    }

    @Test
    void buy_openEqualsLimit_filledNoRefund() {
        // grossCost = 1000, actualCost = 1001.25, frozen 不足补差 1.25
        BacktestSandbox sb = new BacktestSandbox(INIT);
        sb.enqueueBuy("000001", 100, new BigDecimal("10"));

        List<BacktestSandbox.Fill> fills = sb.settleAtOpen(Map.of("000001", new BigDecimal("10")));

        assertEquals(1, fills.size());
        assertEquals(0, sb.getFrozenBalance().compareTo(BigDecimal.ZERO));
        // 999000 + (-1.25 补差) = 998998.75
        assertEquals(0, sb.getBalance().compareTo(new BigDecimal("998998.75")));
        assertEquals(0, sb.equity(Map.of("000001", new BigDecimal("10")))
                .compareTo(new BigDecimal("999998.75")));
    }

    @Test
    void buy_stockHalted_cancelled() {
        // 次日无数据 = 停牌，撤单返还
        BacktestSandbox sb = new BacktestSandbox(INIT);
        sb.enqueueBuy("000001", 100, new BigDecimal("10"));

        List<BacktestSandbox.Fill> fills = sb.settleAtOpen(new HashMap<>()); // 没有 000001 的 open

        assertTrue(fills.isEmpty());
        assertEquals(0, sb.getBalance().compareTo(INIT), "停牌 BUY 应全额返还");
        assertEquals(0, sb.getFrozenBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void sell_filled_balanceIncreased() {
        // 先建仓 100 @ open=10 → balance = 998998.75
        BacktestSandbox sb = new BacktestSandbox(INIT);
        sb.enqueueBuy("000001", 100, new BigDecimal("10"));
        sb.settleAtOpen(Map.of("000001", new BigDecimal("10")));
        sb.clearTodayBuys();

        // 次日卖出 100 @ open=11
        // gross = 1100, SELL 成本 0.175% → income = 1098.08
        assertTrue(sb.enqueueSell("000001", 100, new BigDecimal("10")));
        List<BacktestSandbox.Fill> fills = sb.settleAtOpen(Map.of("000001", new BigDecimal("11")));

        assertEquals(1, fills.size());
        assertEquals("SELL", fills.get(0).side());
        // 998998.75 + 1098.08 = 1000096.83
        assertEquals(0, sb.getBalance().compareTo(new BigDecimal("1000096.83")));
        assertNull(sb.position("000001"));
        assertEquals(0, sb.equity(Map.of("000001", new BigDecimal("11")))
                .compareTo(new BigDecimal("1000096.83")));
    }

    @Test
    void buy_insufficientBalance_rejectedAtEnqueue() {
        BacktestSandbox sb = new BacktestSandbox(new BigDecimal("500"));
        boolean ok = sb.enqueueBuy("000001", 100, new BigDecimal("10")); // 需要 1000，只有 500
        assertEquals(false, ok);
        assertEquals(0, sb.getFrozenBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, sb.getBalance().compareTo(new BigDecimal("500")));
    }

    @Test
    void buy_limitUp_cancelled() {
        // open=11 (>= 涨停 11.0)，BUY 应被涨跌停校验拒绝
        BacktestSandbox sb = new BacktestSandbox(INIT);
        sb.enqueueBuy("000001", 100, new BigDecimal("11"));

        // prevClose=10, 涨停=11（主板 ±10%）；open=11 等于涨停
        List<BacktestSandbox.Fill> fills = sb.settleAtOpen(
                Map.of("000001", new BigDecimal("11")),
                Map.of("000001", new BigDecimal("10")));

        assertTrue(fills.isEmpty(), "涨停板 BUY 应拒绝");
        assertEquals(0, sb.getBalance().compareTo(INIT));
        assertEquals(0, sb.getFrozenBalance().compareTo(BigDecimal.ZERO));
    }
}
