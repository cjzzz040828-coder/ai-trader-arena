package com.aitrade.trade.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PositionVO {
    private Long id;
    private String stockCode;
    private Integer amount;
    private Integer frozenAmount;
    private BigDecimal costPrice;
    private BigDecimal currentPrice;
    private BigDecimal marketValue;
    private BigDecimal profitPct;
    private LocalDateTime updatedAt;
}
