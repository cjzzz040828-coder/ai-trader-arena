package com.aitrade.backtest.dto;

import com.aitrade.entity.BacktestTask;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class BacktestTaskVO {
    private Long id;
    private Long traderId;
    private String traderName;
    private String strategyType;
    private String strategyParams;
    private String startDate;
    private String endDate;
    private BigDecimal initialBalance;
    private String status;
    private Integer progress;
    private String error;

    private BigDecimal finalEquity;
    private BigDecimal totalReturnPct;
    private Integer totalTrades;
    private BigDecimal maxDrawdownPct;
    private String equityCurveJson;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static BacktestTaskVO from(BacktestTask t) {
        BacktestTaskVO v = new BacktestTaskVO();
        v.id = t.getId();
        v.traderId = t.getTraderId();
        v.traderName = t.getTraderName();
        v.strategyType = t.getStrategyType();
        v.strategyParams = t.getStrategyParams();
        v.startDate = t.getStartDate();
        v.endDate = t.getEndDate();
        v.initialBalance = t.getInitialBalance();
        v.status = t.getStatus();
        v.progress = t.getProgress();
        v.error = t.getError();
        v.finalEquity = t.getFinalEquity();
        v.totalReturnPct = t.getTotalReturnPct();
        v.totalTrades = t.getTotalTrades();
        v.maxDrawdownPct = t.getMaxDrawdownPct();
        v.equityCurveJson = t.getEquityCurveJson();
        v.createdAt = t.getCreatedAt();
        v.startedAt = t.getStartedAt();
        v.finishedAt = t.getFinishedAt();
        return v;
    }
}
