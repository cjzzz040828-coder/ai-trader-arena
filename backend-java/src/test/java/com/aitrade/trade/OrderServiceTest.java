package com.aitrade.trade;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.entity.TradeOrder;
import com.aitrade.mapper.AiTraderMapper;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.mapper.TradeOrderMapper;
import com.aitrade.trade.dto.OrderVO;
import com.aitrade.trade.dto.PlaceOrderReq;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderService 资金守恒回归测试。
 * 关键不变量：BUY 下单后 balance + frozen 不变；撤单后还原。
 * 同时验证 P0-1 的修复：place/cancel 都通过 TraderLockRegistry 加锁。
 */
class OrderServiceTest {

    private AiTraderMapper aiTraderMapper;
    private PositionMapper positionMapper;
    private TradeOrderMapper tradeOrderMapper;
    private TraderService traderService;
    private TraderLockRegistry lockRegistry;
    private OrderService orderService;

    private AiTrader trader;

    @BeforeEach
    void setUp() {
        aiTraderMapper = mock(AiTraderMapper.class);
        positionMapper = mock(PositionMapper.class);
        tradeOrderMapper = mock(TradeOrderMapper.class);
        traderService = mock(TraderService.class);
        lockRegistry = mock(TraderLockRegistry.class);

        // 让 withLock 直接执行 supplier（测试里不需要真锁）
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get())
                .when(lockRegistry).withLock(anyLong(), any());

        orderService = new OrderService(
                aiTraderMapper, positionMapper, tradeOrderMapper, traderService, lockRegistry);

        trader = new AiTrader();
        trader.setId(42L);
        trader.setUserId(7L);
        trader.setName("test");
        trader.setBalance(new BigDecimal("100000"));
        trader.setFrozenBalance(BigDecimal.ZERO);
        trader.setInitialBalance(new BigDecimal("100000"));
        trader.setTotalProfit(BigDecimal.ZERO);
        when(traderService.getOwned(eq(42L), eq(7L))).thenReturn(trader);
    }

    @Test
    void buyOrder_freezesFunds_balancePlusFrozenInvariant() {
        BigDecimal initialBalance = trader.getBalance();
        BigDecimal initialFrozen = trader.getFrozenBalance();
        BigDecimal initialSum = initialBalance.add(initialFrozen);

        PlaceOrderReq req = req(42L, "000001", "BUY", new BigDecimal("10.00"), 1000);
        OrderVO vo = orderService.place(req, 7L);

        assertNotNull(vo);
        // 资金守恒：balance + frozen 不变
        BigDecimal sumAfter = trader.getBalance().add(trader.getFrozenBalance());
        assertEquals(0, sumAfter.compareTo(initialSum),
                "BUY 下单后 balance + frozen 必须与下单前相等（资金守恒）");
        // 冻结应等于 price × amount
        BigDecimal expectedFrozen = new BigDecimal("10.00").multiply(new BigDecimal("1000"));
        assertEquals(0, trader.getFrozenBalance().compareTo(expectedFrozen),
                "frozen 必须 = price × amount");
        assertEquals(0, trader.getBalance().compareTo(initialBalance.subtract(expectedFrozen)),
                "balance 必须减去冻结额");

        // 必须通过 lockRegistry 加锁
        verify(lockRegistry, times(1)).withLock(eq(42L), any());
        // 应该 insert order 一次
        verify(tradeOrderMapper).insert(any(TradeOrder.class));
        // 应该 update trader 一次（不应漏写）
        verify(aiTraderMapper).updateById(any(AiTrader.class));
    }

    @Test
    void buyOrder_insufficientBalance_rejected_noStateChange() {
        BigDecimal initialBalance = trader.getBalance();
        BigDecimal initialFrozen = trader.getFrozenBalance();

        // 需要 200_000，余额只有 100_000
        PlaceOrderReq req = req(42L, "000001", "BUY", new BigDecimal("20.00"), 10000);

        try {
            orderService.place(req, 7L);
            throw new AssertionError("应抛 ApiException(可用资金不足)");
        } catch (Exception expected) {
            // ok
        }
        // 拒单后 trader 状态不应被修改
        assertEquals(0, trader.getBalance().compareTo(initialBalance), "拒单后 balance 不应变化");
        assertEquals(0, trader.getFrozenBalance().compareTo(initialFrozen), "拒单后 frozen 不应变化");
        verify(tradeOrderMapper, times(0)).insert(any(TradeOrder.class));
    }

    @Test
    void cancelBuyOrder_releasesFrozen_balancePlusFrozenInvariant() {
        // 先伪造一笔 PENDING 的 BUY order
        TradeOrder order = new TradeOrder();
        order.setId(100L);
        order.setTraderId(42L);
        order.setStockCode("000001");
        order.setSide("BUY");
        order.setPrice(new BigDecimal("10.00"));
        order.setAmount(1000);
        order.setStatus("PENDING");
        when(tradeOrderMapper.selectById(100L)).thenReturn(order);

        // trader 当前已冻结 10_000（模拟挂单后的状态）
        BigDecimal originalFrozen = new BigDecimal("10000.00");
        trader.setBalance(new BigDecimal("90000"));
        trader.setFrozenBalance(originalFrozen);
        BigDecimal sumBefore = trader.getBalance().add(trader.getFrozenBalance());

        OrderVO vo = orderService.cancel(100L, 7L);

        assertNotNull(vo);
        assertEquals("CANCELLED", vo.getStatus(), "撤单后状态应为 CANCELLED");
        BigDecimal sumAfter = trader.getBalance().add(trader.getFrozenBalance());
        assertEquals(0, sumAfter.compareTo(sumBefore),
                "撤单后 balance + frozen 必须保持守恒");
        // 冻结全部释放
        assertEquals(0, trader.getFrozenBalance().compareTo(BigDecimal.ZERO),
                "BUY 撤单后 frozen 应清零");
        assertEquals(0, trader.getBalance().compareTo(new BigDecimal("100000.00")),
                "撤单后 balance 应恢复为下单前数额");
        // 必须通过 lockRegistry 加锁（与 MatchEngine.fillOrderInNewTx 互斥的关键）
        verify(lockRegistry, times(1)).withLock(eq(42L), any());
    }

    @Test
    void sellOrder_freezesPosition_doesNotTouchBalance() {
        // 仓位 1000 股，frozen 0
        Position pos = new Position();
        pos.setId(1L);
        pos.setTraderId(42L);
        pos.setStockCode("000001");
        pos.setAmount(1000);
        pos.setFrozenAmount(0);
        when(positionMapper.selectOne(any(QueryWrapper.class))).thenReturn(pos);

        BigDecimal initialBalance = trader.getBalance();
        BigDecimal initialFrozen = trader.getFrozenBalance();

        PlaceOrderReq req = req(42L, "000001", "SELL", new BigDecimal("10.00"), 500);
        orderService.place(req, 7L);

        // SELL 不冻结资金
        assertEquals(0, trader.getBalance().compareTo(initialBalance),
                "SELL 下单不应改变 balance");
        assertEquals(0, trader.getFrozenBalance().compareTo(initialFrozen),
                "SELL 下单不应改变 frozen_balance");
        // 持仓应被冻结
        ArgumentCaptor<Position> posCap = ArgumentCaptor.forClass(Position.class);
        verify(positionMapper).updateById(posCap.capture());
        assertEquals(500, posCap.getValue().getFrozenAmount(), "frozen_amount 应 = SELL 数量");
    }

    @Test
    void sellOrder_insufficientPosition_rejected() {
        Position pos = new Position();
        pos.setTraderId(42L);
        pos.setStockCode("000001");
        pos.setAmount(100);
        pos.setFrozenAmount(0);
        when(positionMapper.selectOne(any(QueryWrapper.class))).thenReturn(pos);

        PlaceOrderReq req = req(42L, "000001", "SELL", new BigDecimal("10.00"), 1000);
        try {
            orderService.place(req, 7L);
            throw new AssertionError("应抛 ApiException(可卖持仓不足)");
        } catch (Exception expected) {
            // ok
        }
        verify(tradeOrderMapper, times(0)).insert(any(TradeOrder.class));
    }

    private static PlaceOrderReq req(long traderId, String code, String side,
                                     BigDecimal price, int amount) {
        PlaceOrderReq r = new PlaceOrderReq();
        r.setTraderId(traderId);
        r.setStockCode(code);
        r.setSide(side);
        r.setPrice(price);
        r.setAmount(amount);
        return r;
    }
}
