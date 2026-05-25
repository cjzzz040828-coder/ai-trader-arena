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
import java.util.concurrent.ThreadLocalRandom;

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

        Map<String, MarketRow> marketMap = new HashMap<>();
        boolean marketOpen = false;
        if (!codes.isEmpty()) {
            try {
                SnapshotResponse snap = gateway.snapshot(String.join(",", codes));
                marketOpen = "OPEN".equalsIgnoreCase(snap.getMarketStatus());
                marketMap = extractMarketData(snap);
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
            MarketRow row = marketMap.get(o.getStockCode());
            if (row == null || row.price == null || row.price.signum() <= 0) continue;
            try {
                final Long orderId = o.getId();
                final MarketRow rowFinal = row;
                // 共享 TraderLockRegistry：与 OrderService.place/cancel 互斥，
                // 防止"撮合 fillOrder"和"用户/LLM 撤单"并发改 trader.balance/frozen，
                // 破坏资金守恒不变量。
                lockRegistry.withLockVoid(o.getTraderId(), () -> fillOrderInNewTx(orderId, rowFinal));
            } catch (Exception e) {
                log.error("[match] fill order {} failed: {}", o.getId(), e.getMessage(), e);
            }
        }

        // revalue 只需要 price，把 marketMap 抽成纯 priceMap
        Map<String, BigDecimal> priceMap = new HashMap<>();
        for (Map.Entry<String, MarketRow> e : marketMap.entrySet()) {
            priceMap.put(e.getKey(), e.getValue().price);
        }
        revalueInNewTx(priceMap);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fillOrderInNewTx(Long orderId, MarketRow row) {
        TradeOrder o = tradeOrderMapper.selectById(orderId);
        if (o == null || !"PENDING".equals(o.getStatus())) return;
        AiTrader trader = aiTraderMapper.selectById(o.getTraderId());
        if (trader == null) return;

        BigDecimal latest = row.price;
        boolean isBuy = "BUY".equals(o.getSide());
        if (isBuy && o.getPrice().compareTo(latest) < 0) return;
        if (!isBuy && o.getPrice().compareTo(latest) > 0) return;

        // ===== 涨跌停约束：模拟真实排队，不再无脑成交 =====
        // 真实 A 股涨停时只有少量卖单（甚至零卖单 = 一字板），买单排队，绝大部分挂不上。
        // 简化模型：
        //   - 一字板（涨停且 ask_vol1==0 / 跌停且 bid_vol1==0）→ 全拒，保留 PENDING
        //   - 触板但有少量盘口（≥0.99 × 涨跌停幅度）→ 30% 概率成交，其余保留 PENDING
        // 拒单不改 status（仍 PENDING），下个 tick 重试，资金保持冻结，符合真实排队语义。
        if (!canFillUnderLimit(isBuy, row, o.getStockCode())) {
            log.info("[match] order {} skipped by limit-board rule: {} {} {} change_pct={} ask_vol1={} bid_vol1={} (still PENDING, will retry)",
                    o.getId(), o.getSide(), o.getStockCode(),
                    row.name == null ? "" : row.name,
                    row.changePct, row.askVol1, row.bidVol1);
            return;
        }

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
                pos.setHighSinceEntry(filledPrice);  // 新仓：起点即成交价
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
                // 加仓：高水位 = max(原值, 当前成交价)。原值若为空就用成交价兜底。
                BigDecimal prevHigh = pos.getHighSinceEntry();
                if (prevHigh == null || filledPrice.compareTo(prevHigh) > 0) {
                    pos.setHighSinceEntry(filledPrice);
                }
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
        //    同时 bump high_since_entry：current_price > 原 high 则刷新（CTA 跟踪止损用）
        List<Position> positions = positionMapper.selectList(
                new QueryWrapper<Position>().gt("amount", 0));
        for (Position p : positions) {
            BigDecimal latest = priceMap.get(p.getStockCode());
            if (latest == null || latest.signum() <= 0) continue;
            p.setCurrentPrice(latest);
            BigDecimal prevHigh = p.getHighSinceEntry();
            if (prevHigh == null || latest.compareTo(prevHigh) > 0) {
                p.setHighSinceEntry(latest);
            }
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

    private Map<String, MarketRow> extractMarketData(SnapshotResponse snap) {
        Map<String, MarketRow> out = new HashMap<>();
        if (snap == null || snap.getData() == null) return out;
        for (Map<String, Object> row : snap.getData()) {
            Object codeObj = row.get("code");
            Object priceObj = row.get("price");
            if (codeObj == null || priceObj == null) continue;
            try {
                MarketRow m = new MarketRow();
                m.price = new BigDecimal(String.valueOf(priceObj));
                m.changePct = row.get("change_pct") == null
                        ? BigDecimal.ZERO
                        : new BigDecimal(String.valueOf(row.get("change_pct")));
                m.name = row.get("name") == null ? "" : String.valueOf(row.get("name"));
                m.askVol1 = parseLongLoose(row.get("ask_vol1"));
                m.bidVol1 = parseLongLoose(row.get("bid_vol1"));
                out.put(String.valueOf(codeObj), m);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** 按代码段 / ST 判断涨跌停幅度（百分点）。
     *   - name 含 ST / *ST → 5
     *   - code 300 / 688 开头（创业板 / 科创板）→ 20
     *   - 其他主板 → 10
     *  没覆盖：北交所 30%、新股前 5 天无限制 —— 简单版省略。 */
    private static double limitPct(String code, String name) {
        if (name != null && name.toUpperCase().contains("ST")) return 5.0;
        if (code != null && code.length() >= 3) {
            String p3 = code.substring(0, 3);
            if ("300".equals(p3) || "688".equals(p3)) return 20.0;
        }
        return 10.0;
    }

    /** 决定本笔是否能在当前盘口成交。false=保留 PENDING 下 tick 重试。 */
    private static boolean canFillUnderLimit(boolean isBuy, MarketRow m, String code) {
        if (m.changePct == null) return true;
        double cap = limitPct(code, m.name);
        double pct = m.changePct.doubleValue();
        // 触及涨停门槛（接近或达到）
        if (isBuy && pct >= cap * 0.99) {
            if (m.askVol1 <= 0) return false;          // 一字板：全拒
            return ThreadLocalRandom.current().nextDouble() < 0.30;  // 否则 30% 概率成交
        }
        if (!isBuy && pct <= -cap * 0.99) {
            if (m.bidVol1 <= 0) return false;
            return ThreadLocalRandom.current().nextDouble() < 0.30;
        }
        return true;
    }

    private static long parseLongLoose(Object o) {
        if (o == null) return 0;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return 0;
        int dot = s.indexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    /** 撮合用的市场行情快照（比纯 price 多了涨跌幅 / 盘口 / 名称）。 */
    static class MarketRow {
        BigDecimal price;
        BigDecimal changePct;
        String name;
        long askVol1;
        long bidVol1;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static Integer nzi(Integer v) { return v == null ? 0 : v; }
}
