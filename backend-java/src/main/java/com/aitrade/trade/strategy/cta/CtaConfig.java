package com.aitrade.trade.strategy.cta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * CTA 策略配置。落库在 ai_trader.cta_config_json (TEXT)。
 *
 * 例 1 — 双均线趋势：
 * {
 *   "entry": { "type": "DUAL_MA", "shortPeriod": 5, "longPeriod": 20 },
 *   "stopLoss": { "fixedPct": 8.0, "trailingPct": 5.0 },
 *   "exitOnReverseSignal": true
 * }
 *
 * 例 2 — N 日突破跟踪：
 * {
 *   "entry": { "type": "BREAKOUT", "lookback": 20 },
 *   "stopLoss": { "trailingPct": 5.0 },
 *   "exitOnReverseSignal": false
 * }
 *
 * 语义：
 *   - entry.type=DUAL_MA: 金叉买（不持仓时），死叉作为反向出场（exitOnReverseSignal=true 才触发）
 *   - entry.type=BREAKOUT: 今日价 > 过去 lookback 根 high 最大值 → 买；今日价 < 过去 lookback 根 low 最小值 → 反向出场
 *   - stopLoss.fixedPct: 价 ≤ cost_price × (1 - fixedPct%) 触发卖出
 *   - stopLoss.trailingPct: 价 ≤ high_since_entry × (1 - trailingPct%) 触发卖出
 *
 * 决策优先级（同一只股票同一 tick）：止损 &gt; 反向出场 &gt; 入场。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CtaConfig {

    private Entry entry;
    private StopLoss stopLoss;
    /** 反向信号是否触发卖出。null 视为 false。 */
    private Boolean exitOnReverseSignal;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        /** DUAL_MA / BREAKOUT */
        private String type;

        // ---- DUAL_MA ----
        private Integer shortPeriod;
        private Integer longPeriod;

        // ---- BREAKOUT ----
        private Integer lookback;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StopLoss {
        /** 固定止损百分比 (0, 50]。null/0 表示不启用。 */
        private Double fixedPct;
        /** 跟踪止损百分比 (0, 50]。null/0 表示不启用。 */
        private Double trailingPct;
    }
}
