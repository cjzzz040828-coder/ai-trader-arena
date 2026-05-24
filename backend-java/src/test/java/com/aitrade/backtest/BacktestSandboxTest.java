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
 * 资金守恒不变量：任意时刻 sandbox.equity(prices) ≈ initialBalance + 已实现盈亏
 * 这里所有用例都从 1_000_000 开始，没有跨日浮盈，所以 equity == 1_000_000。
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
        // limit=10, open=9（跳空低开），按 open=9 成交，多冻结的 100 元退回 balance
        BacktestSandbox sb = new BacktestSandbox(INIT);
        sb.enqueueBuy("000001", 100, new BigDecimal("10"));

        List<BacktestSandbox.Fill> fills = sb.settleAtOpen(Map.of("000001", new BigDecimal("9")));

        assertEquals(1, fills.size());
        assertEquals("BUY", fills.get(0).side());
        assertEquals(0, fills.get(0).price().compareTo(new BigDecimal("9")));
        assertEquals(0, sb.getFrozenBalance().compareTo(BigDecimal.ZERO));
        // 实际花 900，初始 1_000_000 → balance=999_100
        assertEquals(0, sb.getBalance().compareTo(new BigDecimal("999100")));
        assertNotNull(sb.position("000001"));
        assertEquals(100, sb.position("000001").amount);
        // 守恒：999_100 + 100×9 = 1_000_000
        assertEquals(0, sb.equity(Map.of("000001", new BigDecimal("9"))).compareTo(INIT));
    }

    @Test
    void buy_openEqualsLimit_filledNoRefund() {
        BacktestSandbox sb = new BacktestSandbox(INIT);
        sb.enqueueBuy("000001", 100, new BigDecimal("10"));

        List<BacktestSandbox.Fill> fills = sb.settleAtOpen(Map.of("000001", new BigDecimal("10")));

        assertEquals(1, fills.size());
        assertEquals(0, sb.getFrozenBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, sb.getBalance().compareTo(new BigDecimal("999000")));
        assertEquals(0, sb.equity(Map.of("000001", new BigDecimal("10"))).compareTo(INIT));
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
        // 先植入一个持仓：手工建仓 100@10
        BacktestSandbox sb = new BacktestSandbox(INIT);
        sb.enqueueBuy("000001", 100, new BigDecimal("10"));
        sb.settleAtOpen(Map.of("000001", new BigDecimal("10")));
        sb.clearTodayBuys();

        // 次日卖出 @11
        assertTrue(sb.enqueueSell("000001", 100, new BigDecimal("10")));
        List<BacktestSandbox.Fill> fills = sb.settleAtOpen(Map.of("000001", new BigDecimal("11")));

        assertEquals(1, fills.size());
        assertEquals("SELL", fills.get(0).side());
        // 100×11=1100 进账，持仓清零
        assertEquals(0, sb.getBalance().compareTo(new BigDecimal("1000100")));
        assertNull(sb.position("000001"));
        // 实现盈利 +100
        assertEquals(0, sb.equity(Map.of("000001", new BigDecimal("11")))
                .compareTo(new BigDecimal("1000100")));
    }

    @Test
    void buy_insufficientBalance_rejectedAtEnqueue() {
        BacktestSandbox sb = new BacktestSandbox(new BigDecimal("500"));
        boolean ok = sb.enqueueBuy("000001", 100, new BigDecimal("10")); // 需要 1000，只有 500
        assertEquals(false, ok);
        assertEquals(0, sb.getFrozenBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, sb.getBalance().compareTo(new BigDecimal("500")));
    }
}
