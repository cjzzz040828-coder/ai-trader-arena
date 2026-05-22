package com.aitrade.trade;

import com.aitrade.common.ApiException;
import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.entity.TradeOrder;
import com.aitrade.mapper.AiTraderMapper;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.mapper.TradeOrderMapper;
import com.aitrade.trade.dto.OrderVO;
import com.aitrade.trade.dto.PlaceOrderReq;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final AiTraderMapper aiTraderMapper;
    private final PositionMapper positionMapper;
    private final TradeOrderMapper tradeOrderMapper;
    private final TraderService traderService;

    /**
     * 每个 trader 一把锁。
     * 防止用户手动下单和策略调度同时修改同一个 trader 的 balance/frozen，
     * 破坏资金守恒：balance + frozen_balance + Σ持仓市值 == 1_000_000 + total_profit。
     */
    private final ConcurrentHashMap<Long, Object> traderLocks = new ConcurrentHashMap<>();

    private <T> T withTraderLock(Long traderId, Supplier<T> action) {
        Object lock = traderLocks.computeIfAbsent(traderId, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }

    @Transactional
    public OrderVO place(PlaceOrderReq req, Long userId) {
        return withTraderLock(req.getTraderId(), () -> placeLocked(req, userId));
    }

    private OrderVO placeLocked(PlaceOrderReq req, Long userId) {
        AiTrader trader = traderService.getOwned(req.getTraderId(), userId);
        String side = req.getSide();

        if ("BUY".equals(side)) {
            BigDecimal need = req.getPrice().multiply(BigDecimal.valueOf(req.getAmount()));
            if (trader.getBalance().compareTo(need) < 0) {
                throw ApiException.badRequest("可用资金不足");
            }
            trader.setBalance(trader.getBalance().subtract(need));
            trader.setFrozenBalance(nz(trader.getFrozenBalance()).add(need));
        } else {
            Position pos = positionMapper.selectOne(new QueryWrapper<Position>()
                    .eq("trader_id", trader.getId()).eq("stock_code", req.getStockCode()));
            int available = pos == null ? 0 : (pos.getAmount() - nzi(pos.getFrozenAmount()));
            if (available < req.getAmount()) {
                throw ApiException.badRequest("可卖持仓不足");
            }
            pos.setFrozenAmount(nzi(pos.getFrozenAmount()) + req.getAmount());
            pos.setUpdatedAt(LocalDateTime.now());
            positionMapper.updateById(pos);
        }
        trader.setUpdatedAt(LocalDateTime.now());
        aiTraderMapper.updateById(trader);

        TradeOrder order = new TradeOrder();
        order.setTraderId(trader.getId());
        order.setStockCode(req.getStockCode());
        order.setSide(side);
        order.setPrice(req.getPrice());
        order.setAmount(req.getAmount());
        order.setStatus("PENDING");
        LocalDateTime now = LocalDateTime.now();
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        tradeOrderMapper.insert(order);
        return toVO(order);
    }

    @Transactional
    public OrderVO cancel(Long orderId, Long userId) {
        TradeOrder order = tradeOrderMapper.selectById(orderId);
        if (order == null) throw new ApiException(404, "订单不存在");
        return withTraderLock(order.getTraderId(), () -> cancelLocked(order, userId));
    }

    private OrderVO cancelLocked(TradeOrder order, Long userId) {
        AiTrader trader = traderService.getOwned(order.getTraderId(), userId);
        if (!"PENDING".equals(order.getStatus())) {
            throw ApiException.badRequest("仅 PENDING 订单可撤");
        }
        releaseFrozen(order, trader);
        order.setStatus("CANCELLED");
        order.setUpdatedAt(LocalDateTime.now());
        tradeOrderMapper.updateById(order);
        return toVO(order);
    }

    /**
     * 撤掉某 trader 下所有 PENDING 订单（释放冻结）。
     * 给 trader 删除/重置流程用。返回撤掉的订单数。
     */
    @Transactional
    public int cancelAllPending(Long traderId, Long userId) {
        return withTraderLock(traderId, () -> {
            AiTrader trader = traderService.getOwned(traderId, userId);
            List<TradeOrder> pending = tradeOrderMapper.selectList(new QueryWrapper<TradeOrder>()
                    .eq("trader_id", traderId).eq("status", "PENDING"));
            LocalDateTime now = LocalDateTime.now();
            for (TradeOrder o : pending) {
                releaseFrozen(o, trader);
                o.setStatus("CANCELLED");
                o.setUpdatedAt(now);
                tradeOrderMapper.updateById(o);
            }
            return pending.size();
        });
    }

    public List<OrderVO> listOrders(Long traderId, int limit) {
        List<TradeOrder> orders = tradeOrderMapper.selectList(new QueryWrapper<TradeOrder>()
                .eq("trader_id", traderId).orderByDesc("created_at").last("LIMIT " + Math.min(Math.max(limit, 1), 500)));
        List<OrderVO> result = new ArrayList<>(orders.size());
        for (TradeOrder o : orders) result.add(toVO(o));
        return result;
    }

    /** 释放挂单冻结的资金/股，写回 trader 和 position，但不更新订单状态本身。 */
    void releaseFrozen(TradeOrder order, AiTrader trader) {
        if ("BUY".equals(order.getSide())) {
            BigDecimal frozen = order.getPrice().multiply(BigDecimal.valueOf(order.getAmount()));
            trader.setBalance(nz(trader.getBalance()).add(frozen));
            trader.setFrozenBalance(nz(trader.getFrozenBalance()).subtract(frozen));
            trader.setUpdatedAt(LocalDateTime.now());
            aiTraderMapper.updateById(trader);
        } else {
            Position pos = positionMapper.selectOne(new QueryWrapper<Position>()
                    .eq("trader_id", trader.getId()).eq("stock_code", order.getStockCode()));
            if (pos != null) {
                pos.setFrozenAmount(Math.max(0, nzi(pos.getFrozenAmount()) - order.getAmount()));
                pos.setUpdatedAt(LocalDateTime.now());
                positionMapper.updateById(pos);
            }
        }
    }

    public static OrderVO toVO(TradeOrder o) {
        OrderVO vo = new OrderVO();
        vo.setId(o.getId());
        vo.setTraderId(o.getTraderId());
        vo.setStockCode(o.getStockCode());
        vo.setSide(o.getSide());
        vo.setPrice(o.getPrice());
        vo.setAmount(o.getAmount());
        vo.setStatus(o.getStatus());
        vo.setFilledPrice(o.getFilledPrice());
        vo.setFilledAt(o.getFilledAt());
        vo.setCreatedAt(o.getCreatedAt());
        return vo;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static Integer nzi(Integer v) { return v == null ? 0 : v; }

    /**
     * 暴露给策略层。先看 traderId+stockCode 是否有 PENDING 单——挡 MA 边沿抖动与 LLM 反复信号。
     */
    public boolean hasPending(Long traderId, String stockCode) {
        Long n = tradeOrderMapper.selectCount(new QueryWrapper<TradeOrder>()
                .eq("trader_id", traderId).eq("stock_code", stockCode).eq("status", "PENDING"));
        return n != null && n > 0;
    }

    /**
     * 暴露给策略层。检查同 trader+code 是否当日有 FILLED 的 BUY——A股 T+1 兜底。
     */
    public boolean boughtToday(Long traderId, String stockCode) {
        Long n = tradeOrderMapper.selectCount(new QueryWrapper<TradeOrder>()
                .eq("trader_id", traderId).eq("stock_code", stockCode)
                .eq("side", "BUY").eq("status", "FILLED")
                .apply("date(filled_at) = date('now', 'localtime')"));
        return n != null && n > 0;
    }
}
