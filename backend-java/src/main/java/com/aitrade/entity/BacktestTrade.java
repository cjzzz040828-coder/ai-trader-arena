package com.aitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("backtest_trade")
public class BacktestTrade {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String tradeDate;
    private String stockCode;
    private String stockName;
    private String side;
    private Integer amount;
    private BigDecimal price;
    private BigDecimal balanceAfter;
    private BigDecimal costPrice;
    private String reason;
}
