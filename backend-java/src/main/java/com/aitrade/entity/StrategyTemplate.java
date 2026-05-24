package com.aitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("strategy_template")
public class StrategyTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String description;
    private String strategyType;
    /** JSON 字符串。MA: {maShort,maLong}; LLM: {llmBaseUrl,llmModel,llmPrompt}。绝不存 api_key。 */
    private String defaultParamsJson;
    private String tags;
    private Integer isOfficial;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
