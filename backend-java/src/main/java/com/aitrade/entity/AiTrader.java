package com.aitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_trader")
public class AiTrader {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String strategyType;
    private BigDecimal initialBalance;
    private BigDecimal balance;
    private BigDecimal frozenBalance;
    private BigDecimal totalProfit;

    private Integer enabled;
    private Integer deleted;

    private Integer maShort;
    private Integer maLong;

    private String llmBaseUrl;
    private String llmApiKey;
    private String llmModel;
    private String llmPrompt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
