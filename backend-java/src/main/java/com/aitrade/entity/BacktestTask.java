package com.aitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("backtest_task")
public class BacktestTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
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
}
