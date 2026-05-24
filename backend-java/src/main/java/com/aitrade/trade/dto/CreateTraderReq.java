package com.aitrade.trade.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateTraderReq {
    @NotBlank(message = "name 不能为空")
    @Size(max = 64, message = "name 长度 1-64")
    private String name;

    /** MANUAL / MA / LLM。null 视为 MANUAL */
    private String strategyType;

    private Boolean enabled;

    /** 初始虚拟账户资产；不传默认 100 万。范围 1000 ~ 1 亿。 */
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

    /** INDICATOR 策略的 JSON 配置。前端组件序列化后透传，后端做语法+白名单校验。 */
    @Size(max = 16000, message = "指标策略配置不能超过 16000 字")
    private String indicatorConfigJson;

    /** SCRIPT 策略的 JavaScript 源码。 */
    @Size(max = 32000, message = "脚本源码不能超过 32000 字")
    private String scriptCode;

    /** 选股池名，对应 gateway pool_registry 里的 slug；null 表示沿用默认 watchlist。 */
    @Size(max = 32, message = "poolName 长度不能超过 32")
    private String poolName;
}
