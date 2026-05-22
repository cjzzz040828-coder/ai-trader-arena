package com.aitrade.trade.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TraderVO {
    private Long id;
    private String name;
    private String strategyType;
    private Boolean enabled;

    private BigDecimal initialBalance;
    private BigDecimal balance;
    private BigDecimal frozenBalance;
    private BigDecimal marketValue;
    private BigDecimal totalAsset;
    private BigDecimal totalProfit;
    private BigDecimal profitPct;

    private Integer maShort;
    private Integer maLong;

    private String llmBaseUrl;
    private String llmModel;
    private String llmPrompt;
    private Boolean llmApiKeySet;
}
