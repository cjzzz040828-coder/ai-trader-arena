package com.aitrade.trade;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.entity.TradeOrder;
import com.aitrade.gateway.PythonGatewayClient;
import com.aitrade.gateway.dto.SnapshotResponse;
import com.aitrade.mapper.AiTraderMapper;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.mapper.TradeOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 虚拟撮合引擎。每次 tick：
 *   1. 收集 PENDING 订单 + 现有持仓的所有 stockCode
 *   2. 调 Python 网关取最新行情
 *   3. 交易时段才撮合 PENDING；非交易时段也跳过估值（避免靠陈旧数据刷 total_profit）
 *   4. 更新 position.current_price + trader.total_profit
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchEngine {

    private final TradeOrderMapper tradeOrderMapper;
    private final PositionMapper positionMapper;
    private final AiTraderMapper aiTraderMapper;
    private final PythonGatewayClient gateway;
    private final TraderLockRegistry lockRegistry;

    public void tick() {
        List<TradeOrder> pending = tradeOrderMapper.selectList(
                new QueryWrapper<TradeOrder>().eq("status", "PENDING"));
        List<Position> allPositions = positionMapper.selectList(
                new QueryWrapper<Position>().gt("amount", 0));

        Set<String> codes = new HashSet<>();
        for (TradeOrder o : pending) codes.add(o.getStockCode());
        for (Position p : allPositions) codes.add(p.getStockCode());

        Map<String, BigDecimal> priceMap = new HashMap<>();
        boolean marketOpen = false;
        if (!codes.isEmpty()) {
            try {
                SnapshotResponse snap = gateway.snapshot(String.join(",", codes));
                marketOpen = "OPEN".equalsIgnoreCase(snap.getMarketStatus());
                priceMap = extractPrices(snap);
            } catch (Exception e) {
                log.warn("[match] snapshot failed: {}", e.getMessage());
                return;
            }
        }
        if (!codes.isEmpty() && !marketOpen) {
            log.debug("[match] market closed, skip tick");
            return;
        }

        for (TradeOrder o : pending) {
            BigDecimal latest = priceMap.get(o.getStockCode());
            if (latest == null || latest.signum() <= 0) continue;
            try {
                final Long orderId = o.getId();
                final BigDecimal price = latest;
                // 共享 TraderLockRegistry：与 OrderService.place/cancel 互斥，
                // 防止"撮合 fillOrder"和"用户/LLM 撤单"并发改 trader.balance/frozen，
                // 破坏资金守恒不变量。
                lockRegistry.withLockVoid(o.getTraderId(), () -> fillOrderInNewTx(orderId, price));
            } catch (Exception e) {
                log.error("[match] fill order {} failed: {}", o.getId(), e.getMessage(), e);
            }
        }

        revalueInNewTx(priceMap);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fillOrderInNewTx(Long orderId, BigDecimal latest) {
        TradeOrder o = tradeOrderMapper.selectById(orderId);
        if (o == null || !"PENDING".equals(o.getStatus())) return;
        AiTrader trader = aiTraderMapper.selectById(o.getTraderId());
        if (trader == null) return;

        boolean isBuy = "BUY".equals(o.getSide());
        if (isBuy && o.getPrice().compareTo(latest) < 0) return;
        if (!isBuy && o.getPrice().compareTo(latest) > 0) return;

        BigDecimal filledPrice = latest;
        BigDecimal qty = BigDecimal.valueOf(o.getAmount());
        LocalDateTime now = LocalDateTime.now();

        if (isBuy) {
            BigDecimal frozen = o.getPrice().multiply(qty);
            BigDecimal actualCost = filledPrice.multiply(qty);
            trader.setFrozenBalance(nz(trader.getFrozenBalance()).subtract(frozen));
            trader.setBalance(nz(trader.getBalance()).add(frozen.subtract(actualCost)));
            trader.setUpdatedAt(now);
            aiTraderMapper.updateById(trader);

            Position pos = positionMapper.selectOne(new QueryWrapper<Position>()
                    .eq("trader_id", trader.getId()).eq("stock_code", o.getStockCode()));
            if (pos == null) {
                pos = new Position();
                pos.setTraderId(trader.getId());
                pos.setStockCode(o.getStockCode());
                pos.setAmount(o.getAmount());
                pos.setFrozenAmount(0);
                pos.setCostPrice(filledPrice);
                pos.setCurrentPrice(filledPrice);
                pos.setUpdatedAt(now);
                positionMapper.insert(pos);
            } else {
                int newAmt = pos.getAmount() + o.getAmount();
                BigDecimal oldCost = pos.getCostPrice().multiply(BigDecimal.valueOf(pos.getAmount()));
                BigDecimal addCost = filledPrice.multiply(qty);
                BigDecimal avg = oldCost.add(addCost).divide(BigDecimal.valueOf(newAmt), 3, RoundingMode.HALF_UP);
                pos.setAmount(newAmt);
                pos.setCostPrice(avg);
                pos.setCurrentPrice(filledPrice);
                pos.setUpdatedAt(now);
                positionMapper.updateById(pos);
            }
        } else {
            BigDecimal proceed = filledPrice.multiply(qty);
            trader.setBalance(nz(trader.getBalance()).add(proceed));
            trader.setUpdatedAt(now);
            aiTraderMapper.updateById(trader);

            Position pos = positionMapper.selectOne(new QueryWrapper<Position>()
                    .eq("trader_id", trader.getId()).eq("stock_code", o.getStockCode()));
            if (pos != null) {
                pos.setAmount(pos.getAmount() - o.getAmount());
                pos.setFrozenAmount(Math.max(0, nzi(pos.getFrozenAmount()) - o.getAmount()));
                pos.setCurrentPrice(filledPrice);
                pos.setUpdatedAt(now);
                positionMapper.updateById(pos);
            }
        }

        o.setStatus("FILLED");
        o.setFilledPrice(filledPrice);
        o.setFilledAt(now);
        o.setUpdatedAt(now);
        tradeOrderMapper.updateById(o);
        log.info("[match] order {} FILLED @ {} ({} {} {} x {})", o.getId(), filledPrice,
                trader.getName(), o.getSide(), o.getStockCode(), o.getAmount());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revalueInNewTx(Map<String, BigDecimal> priceMap) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 更新所有有持仓的 position.current_price（用新行情）
        List<Position> positions = positionMapper.selectList(
                new QueryWrapper<Position>().gt("amount", 0));
        for (Position p : positions) {
            BigDecimal latest = priceMap.get(p.getStockCode());
            if (latest == null || latest.signum() <= 0) continue;
            p.setCurrentPrice(latest);
            p.setUpdatedAt(now);
            positionMapper.updateById(p);
        }

        // 2. 计算每个有持仓 trader 的市值
        Map<Long, BigDecimal> mvByTrader = new HashMap<>();
        for (Position p : positionMapper.selectList(
                new QueryWrapper<Position>().gt("amount", 0))) {
            BigDecimal price = nz(p.getCurrentPrice());
            BigDecimal mv = price.multiply(BigDecimal.valueOf(p.getAmount()));
            mvByTrader.merge(p.getTraderId(), mv, BigDecimal::add);
        }

        // 3. 对所有非软删的 trader 都刷新 total_profit（包括已清仓的）
        //    保证排行榜不会卡在最后一次有持仓时的快照。
        //    total_profit = total_asset - initial_balance（每个 trader 自带初始资产）
        List<AiTrader> traders = aiTraderMapper.selectList(
                new QueryWrapper<AiTrader>().eq("deleted", 0));
        for (AiTrader t : traders) {
            BigDecimal mv = mvByTrader.getOrDefault(t.getId(), BigDecimal.ZERO);
            BigDecimal totalAsset = nz(t.getBalance()).add(nz(t.getFrozenBalance())).add(mv);
            BigDecimal initial = nz(t.getInitialBalance());
            if (initial.signum() <= 0) initial = TraderService.DEFAULT_INITIAL_BALANCE;
            BigDecimal newProfit = totalAsset.subtract(initial);
            // 仅在变化时写库，省点 IO
            if (t.getTotalProfit() == null || t.getTotalProfit().compareTo(newProfit) != 0) {
                t.setTotalProfit(newProfit);
                t.setUpdatedAt(now);
                aiTraderMapper.updateById(t);
            }
        }
    }

    private Map<String, BigDecimal> extractPrices(SnapshotResponse snap) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (snap == null || snap.getData() == null) return out;
        for (Map<String, Object> row : snap.getData()) {
            Object codeObj = row.get("code");
            Object priceObj = row.get("price");
            if (codeObj == null || priceObj == null) continue;
            try {
                out.put(String.valueOf(codeObj), new BigDecimal(String.valueOf(priceObj)));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static Integer nzi(Integer v) { return v == null ? 0 : v; }
}
