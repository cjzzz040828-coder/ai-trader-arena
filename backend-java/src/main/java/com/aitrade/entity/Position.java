package com.aitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("position")
public class Position {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long traderId;
    private String stockCode;
    private Integer amount;
    private Integer frozenAmount;
    private BigDecimal costPrice;
    private BigDecimal currentPrice;
    /** 持仓期间最高价。BUY 成交时 = filled_price；revalue 时取 max(原值, current_price)。
     *  CTA 跟踪止损用：trigger = high_since_entry × (1 - trailingPct%) */
    private BigDecimal highSinceEntry;
    private LocalDateTime updatedAt;
}
