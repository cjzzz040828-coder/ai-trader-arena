package com.aitrade.trade.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 编辑 trader。所有字段可空——只更新非 null 字段。
 * llmApiKey 留空表示保留旧 key；显式传空字符串视为清空。
 * initialBalance：仅在 trader 持仓为空且无 PENDING 单时才能修改（同时会把 balance 设为新值）。
 */
@Data
public class UpdateTraderReq {
    @Size(max = 64)
    private String name;

    private String strategyType;
    private Boolean enabled;

    @DecimalMin(value = "1000", message = "初始资产不能小于 1000")
    @DecimalMax(value = "100000000", message = "初始资产不能超过 1 亿")
    private BigDecimal initialBalance;

    private Integer maShort;
    private Integer maLong;

    @Size(max = 256)
    private String llmBaseUrl;
    @Size(max = 256)
    private String llmApiKey;
    @Size(max = 64)
    private String llmModel;
    @Size(max = 8000, message = "投资策略 prompt 不能超过 8000 字")
    private String llmPrompt;
}
