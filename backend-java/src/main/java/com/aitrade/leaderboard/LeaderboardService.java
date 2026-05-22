package com.aitrade.leaderboard;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.entity.User;
import com.aitrade.leaderboard.dto.LeaderboardItem;
import com.aitrade.mapper.AiTraderMapper;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.mapper.UserMapper;
import com.aitrade.trade.TraderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全站排行榜：所有非软删 trader 按 total_profit DESC 排序。
 *
 *   - trader 列表一次 SQL；
 *   - 关联的 user 一次 IN 查询；
 *   - 各 trader 持仓市值一次性 SUM 聚合（避免 N+1）。
 */
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final AiTraderMapper aiTraderMapper;
    private final UserMapper userMapper;
    private final PositionMapper positionMapper;

    public List<LeaderboardItem> top(int limit) {
        int clamped = Math.min(Math.max(limit, 1), 500);
        List<AiTrader> traders = aiTraderMapper.selectList(new QueryWrapper<AiTrader>()
                .eq("deleted", 0)
                .orderByDesc("total_profit")
                .last("LIMIT " + clamped));
        if (traders.isEmpty()) return List.of();

        Set<Long> userIds = new HashSet<>();
        Set<Long> traderIds = new HashSet<>();
        for (AiTrader t : traders) {
            userIds.add(t.getUserId());
            traderIds.add(t.getId());
        }
        Map<Long, User> userById = loadUsers(userIds);
        Map<Long, BigDecimal> mvByTrader = sumMarketValue(traderIds);

        List<LeaderboardItem> result = new ArrayList<>(traders.size());
        int rank = 1;
        for (AiTrader t : traders) {
            LeaderboardItem item = new LeaderboardItem();
            item.setRank(rank++);
            item.setTraderId(t.getId());
            item.setTraderName(t.getName());
            item.setUserId(t.getUserId());
            User u = userById.get(t.getUserId());
            item.setUserNickname(u == null ? "?" : (u.getNickname() == null || u.getNickname().isBlank() ? u.getUsername() : u.getNickname()));
            item.setStrategyType(t.getStrategyType());

            BigDecimal balance = nz(t.getBalance());
            BigDecimal frozen = nz(t.getFrozenBalance());
            BigDecimal mv = mvByTrader.getOrDefault(t.getId(), BigDecimal.ZERO);
            BigDecimal totalAsset = balance.add(frozen).add(mv);
            BigDecimal initial = nz(t.getInitialBalance());
            if (initial.signum() <= 0) initial = TraderService.DEFAULT_INITIAL_BALANCE;
            BigDecimal profit = totalAsset.subtract(initial);
            BigDecimal pct = profit.multiply(BigDecimal.valueOf(100))
                    .divide(initial, 4, RoundingMode.HALF_UP);
            item.setBalance(balance);
            item.setFrozenBalance(frozen);
            item.setMarketValue(mv);
            item.setTotalAsset(totalAsset);
            item.setTotalProfit(profit);
            item.setProfitPct(pct);
            item.setUpdatedAt(t.getUpdatedAt() == null ? LocalDateTime.now() : t.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    private Map<Long, User> loadUsers(Collection<Long> ids) {
        Map<Long, User> map = new HashMap<>();
        if (ids.isEmpty()) return map;
        for (User u : userMapper.selectList(new QueryWrapper<User>().in("id", ids))) {
            map.put(u.getId(), u);
        }
        return map;
    }

    private Map<Long, BigDecimal> sumMarketValue(Collection<Long> traderIds) {
        Map<Long, BigDecimal> map = new HashMap<>();
        if (traderIds.isEmpty()) return map;
        List<Position> ps = positionMapper.selectList(new QueryWrapper<Position>()
                .in("trader_id", traderIds).gt("amount", 0));
        for (Position p : ps) {
            BigDecimal price = nz(p.getCurrentPrice());
            if (price.signum() == 0) price = nz(p.getCostPrice());
            BigDecimal mv = price.multiply(BigDecimal.valueOf(p.getAmount()));
            map.merge(p.getTraderId(), mv, BigDecimal::add);
        }
        return map;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
