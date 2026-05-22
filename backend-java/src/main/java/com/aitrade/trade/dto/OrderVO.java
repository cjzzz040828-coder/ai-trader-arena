package com.aitrade.trade.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderVO {
    private Long id;
    private Long traderId;
    private String stockCode;
    private String side;
    private BigDecimal price;
    private Integer amount;
    private String status;
    private BigDecimal filledPrice;
    private LocalDateTime filledAt;
    private LocalDateTime createdAt;
}
