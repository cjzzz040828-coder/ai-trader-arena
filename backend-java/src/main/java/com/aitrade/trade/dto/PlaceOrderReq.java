package com.aitrade.trade.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PlaceOrderReq {
    @NotNull
    private Long traderId;

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "股票代码须为6位数字")
    private String stockCode;

    @NotBlank
    @Pattern(regexp = "BUY|SELL", message = "side 必须为 BUY 或 SELL")
    private String side;

    @NotNull
    @DecimalMin(value = "0.001", message = "价格必须大于 0")
    private BigDecimal price;

    @NotNull
    private Integer amount;

    @AssertTrue(message = "数量必须为 100 的整数倍且大于 0")
    public boolean isAmountValid() {
        return amount != null && amount > 0 && amount % 100 == 0;
    }
}
