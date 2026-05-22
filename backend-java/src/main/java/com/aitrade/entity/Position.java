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
    private LocalDateTime updatedAt;
}
