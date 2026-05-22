package com.aitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("trade_order")
public class TradeOrder {
    @TableId(type = IdType.AUTO)
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
    private LocalDateTime updatedAt;
}
