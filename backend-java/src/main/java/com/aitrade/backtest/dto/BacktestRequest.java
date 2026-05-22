package com.aitrade.backtest.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class BacktestRequest {
    private Long traderId;
    private String startDate;
    private String endDate;
    private BigDecimal initialBalance;
}
