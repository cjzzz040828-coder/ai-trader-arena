package com.aitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("llm_decision_memory")
public class LlmDecisionMemory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long traderId;
    private Long userId;
    private Long decisionId;
    private Long orderId;
    private String stockCode;
    private String side;
    private Integer amount;
    private BigDecimal priceAtDecision;
    private String indicatorsSnapshot;
    private String reason;
    private LocalDateTime createdAt;

    private LocalDateTime verifiedAt;
    private Integer verifyHorizonDays;
    private BigDecimal priceAfterHorizon;
    private BigDecimal actualReturnPct;
    private Integer wasCorrect;
}
