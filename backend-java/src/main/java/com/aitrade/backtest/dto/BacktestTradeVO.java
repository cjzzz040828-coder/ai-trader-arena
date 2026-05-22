package com.aitrade.backtest.dto;

import com.aitrade.entity.BacktestTrade;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BacktestTradeVO {
    private Long id;
    private String tradeDate;
    private String stockCode;
    private String side;
    private Integer amount;
    private BigDecimal price;
    private BigDecimal balanceAfter;
    private String reason;

    public static BacktestTradeVO from(BacktestTrade t) {
        BacktestTradeVO v = new BacktestTradeVO();
        v.id = t.getId();
        v.tradeDate = t.getTradeDate();
        v.stockCode = t.getStockCode();
        v.side = t.getSide();
        v.amount = t.getAmount();
        v.price = t.getPrice();
        v.balanceAfter = t.getBalanceAfter();
        v.reason = t.getReason();
        return v;
    }
}
