package com.aitrade.trade.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 策略模板列表/详情 VO。永不暴露 api_key（模板本来就不存）。 */
@Data
public class StrategyTemplateVO {
    private Long id;
    private String code;
    private String name;
    private String description;
    private String strategyType;
    /** 反序列化后的 default_params_json；不含 api_key */
    private Map<String, Object> params;
    private List<String> tags;
    private Boolean isOfficial;
    private Integer sortOrder;
    /** 基于此模板创建的实例聚合指标 */
    private Metrics metrics;

    @Data
    public static class Metrics {
        private Integer instanceCount;
        private Double avgReturnPct;
        private Integer totalTrades;
        private Double winRate;
    }
}
