package com.aitrade.analyst;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Dashboard 分析助手的全局 LLM 配置（analyst.llm.*）。与交易 trader 的 LLM 配置完全解耦。 */
@Data
@Component
@ConfigurationProperties(prefix = "analyst.llm")
public class AnalystProperties {
    private String baseUrl;
    private String apiKey;
    private String model;

    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank();
    }
}
