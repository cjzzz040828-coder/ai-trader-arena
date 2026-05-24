package com.aitrade.trade.strategy;

import com.aitrade.entity.AiTrader;
import com.aitrade.mapper.PositionMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * MA 金叉/死叉计算回归测试。
 * 重点防 P1-1（avgTailWithToday 把今日 close 算两次）的回归。
 */
class MovingAverageExecutorTest {

    private final MovingAverageExecutor executor = new MovingAverageExecutor(mock(PositionMapper.class));

    /**
     * 构造的数据专门用来区分 P1-1 修复前后的行为：
     *   closes (bars 末尾) = [10, 10, 10, 8]，末尾 8 = 今日 partial close
     *   today snapshot price = 12
     *   maShort=2, maLong=3
     *
     * 数学推导：
     *   prevShort = avg(closes[1..2]) = (10+10)/2 = 10
     *   prevLong  = avg(closes[0..2]) = 30/3      = 10   ← prevShort <= prevLong ✓
     *
     *   修复后（替换末尾）：
     *     currShort = (closes[2] + 12) / 2     = 11
     *     currLong  = (closes[1]+closes[2]+12)/3 ≈ 10.67
     *     currShort > currLong → 触发金叉 BUY
     *
     *   修复前（追加末尾，今日双计）：
     *     currShort = (closes[3] + 12) / 2     = 10
     *     currLong  = (closes[2]+closes[3]+12)/3 = 10
     *     currShort > currLong = false → 不触发金叉
     *
     * 所以这条用例只在 avgTailWithToday 改成"替换末尾"后才会 BUY。
     */
    @Test
    void goldCrossDetected_afterTodayDuplicationFix() {
        AiTrader trader = buildTrader(2, 3);
        MarketContext ctx = buildCtxWithBars("000001",
                new double[]{10, 10, 10, 8}, new BigDecimal("12"));

        List<Signal> signals = executor.decideWith(trader, ctx, Collections.emptyMap());

        assertEquals(1, signals.size(), "应触发一个金叉 BUY 信号（若回归到今日双计 bug 则为 0）");
        assertEquals("BUY", signals.get(0).side());
        assertEquals("000001", signals.get(0).stockCode());
    }

    /**
     * 反向用例：平稳行情下不应触发任何信号。
     *   closes = [10, 10, 10, 10], today = 10
     *   修复后 currShort = currLong = 10 ⇒ 无信号
     */
    @Test
    void flatMarket_noSignal() {
        AiTrader trader = buildTrader(2, 3);
        MarketContext ctx = buildCtxWithBars("000001",
                new double[]{10, 10, 10, 10}, new BigDecimal("10"));

        List<Signal> signals = executor.decideWith(trader, ctx, Collections.emptyMap());

        assertTrue(signals.isEmpty(), "平稳行情不应触发信号");
    }

    /** 数据不足时直接跳过该 code，不抛异常。 */
    @Test
    void insufficientBars_skip() {
        AiTrader trader = buildTrader(2, 5);  // 需要 bars.size >= 6
        MarketContext ctx = buildCtxWithBars("000001",
                new double[]{10, 10}, new BigDecimal("12"));

        List<Signal> signals = executor.decideWith(trader, ctx, Collections.emptyMap());
        assertTrue(signals.isEmpty(), "K 线不足不应触发信号");
    }

    // ----------------------------- 测试脚手架 -----------------------------

    private AiTrader buildTrader(int sh, int lo) {
        AiTrader t = new AiTrader();
        t.setId(1L);
        t.setName("test-ma");
        t.setMaShort(sh);
        t.setMaLong(lo);
        return t;
    }

    /** 构造一个最小的 MarketContext：单只 code，预设 bars 与 snapshot 价格。 */
    private MarketContext buildCtxWithBars(String code, double[] closes, BigDecimal todayPrice) {
        List<String> codes = List.of(code);
        Map<String, Map<String, Object>> snapshots = new HashMap<>();
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        row.put("price", todayPrice.toPlainString());
        snapshots.put(code, row);

        List<Map<String, Object>> bars = new ArrayList<>();
        for (double c : closes) {
            Map<String, Object> bar = new HashMap<>();
            bar.put("close", c);
            bars.add(bar);
        }

        return new MarketContext(codes, snapshots, true) {
            @Override
            public List<Map<String, Object>> bars(String c, int frequency, int count) {
                return code.equals(c) ? bars : Collections.emptyList();
            }
        };
    }
}
