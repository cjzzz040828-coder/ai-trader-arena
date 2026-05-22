package com.aitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("llm_activity")
public class LlmActivity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long traderId;
    private Long userId;
    private Long decisionId;
    private Integer seq;
    private String phase;
    private Integer round;
    private String toolName;
    private String toolCallId;
    private String argsJson;
    private String resultJson;
    private String message;
    private LocalDateTime createdAt;
}
