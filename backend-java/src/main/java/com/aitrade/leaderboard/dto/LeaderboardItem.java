package com.aitrade.leaderboard.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class LeaderboardItem {
    private Integer rank;
    private Long traderId;
    private String traderName;
    private Long userId;
    private String userNickname;
    private String strategyType;
    private BigDecimal balance;
    private BigDecimal frozenBalance;
    private BigDecimal marketValue;
    private BigDecimal totalAsset;
    private BigDecimal totalProfit;
    private BigDecimal profitPct;
    private LocalDateTime updatedAt;
}
