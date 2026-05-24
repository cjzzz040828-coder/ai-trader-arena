package com.aitrade.trade.strategy.indicator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * INDICATOR 策略的可序列化配置。落库在 ai_trader.indicator_config_json (TEXT)。
 *
 * 例：
 * {
 *   "indicators": [
 *     { "id": "rsi1", "type": "RSI",  "params": { "period": 14 } },
 *     { "id": "macd1","type": "MACD", "params": { "fast": 12, "slow": 26, "signal": 9 } }
 *   ],
 *   "buyRules":  [ { "left": "rsi1.value", "op": "<", "right": 30 } ],
 *   "sellRules": [ { "left": "macd1.macd", "op": "CROSS_DOWN", "right": "macd1.signal" } ],
 *   "logic": "AND"
 * }
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndicatorConfig {

    private List<IndicatorDef> indicators;
    private List<Rule> buyRules;
    private List<Rule> sellRules;
    /** AND / OR，控制 buyRules / sellRules 内部多条规则之间的关系。默认 AND。 */
    private String logic;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndicatorDef {
        /** 在规则里引用本指标输出字段的前缀，例如 "rsi1"。 */
        private String id;
        /** RSI / MACD / BOLL / KDJ */
        private String type;
        private Map<String, Number> params;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rule {
        /** "indicatorId.field"，例如 "rsi1.value"、"macd1.macd"。 */
        private String left;
        /** &lt; &gt; &lt;= &gt;= == CROSS_UP CROSS_DOWN */
        private String op;
        /** 数字常量（Number）或 "indicatorId.field" 引用（String）。 */
        private Object right;
    }
}
