package com.aitrade.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /** LLM 配置。updateStrategy=IGNORED：允许用户编辑时清空字段（NOT_NULL 默认策略会让 setXxx(null) 被跳过）。
     *  llmApiKey 例外——它的 update 语义是"留空保留旧值"，要明确避免被误清，所以维持默认策略。 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String llmBaseUrl;
    private String llmApiKey;
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String llmModel;
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String llmPrompt;

    /** INDICATOR 策略的可序列化配置（JSON）。 schema: ai_trader.indicator_config_json TEXT。
     *  updateStrategy=IGNORED：允许从 INDICATOR 切到其它策略时把配置清空。 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String indicatorConfigJson;

    /** CTA 策略配置 JSON。schema: ai_trader.cta_config_json TEXT。
     *  结构：entry(DUAL_MA/BREAKOUT 信号) + stopLoss(固定% + 跟踪%) + exitOnReverseSignal。
     *  updateStrategy=IGNORED：同上，允许切策略时清空。 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String ctaConfigJson;

    /** 多因子策略配置 JSON。schema: ai_trader.factor_config_json TEXT。
     *  结构：factors[] (MOMENTUM/VOLATILITY/PE_TTM/PB/ROE + weight) + topN + rebalanceFreq。
     *  updateStrategy=IGNORED：同上。 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String factorConfigJson;

    /** SCRIPT 策略的 JS 源码。schema: ai_trader.script_code TEXT。
     *  updateStrategy=IGNORED：同上。 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String scriptCode;

    /** 选股池名（pool_registry 里的 slug）。null 表示沿用默认 watchlist。
     *  updateStrategy=IGNORED：允许 updateById 把字段写为 null（用户从某个池切回默认 watchlist 时必需）。 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String poolName;

    private Long templateId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
