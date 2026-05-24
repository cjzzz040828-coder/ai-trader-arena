package com.aitrade.trade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InstantiateTemplateReq {
    @NotBlank(message = "traderName 不能为空")
    @Size(max = 64)
    private String traderName;

    /** 仅 LLM 模板必填；MA 模板传了也会被忽略。 */
    @Size(max = 256)
    private String llmApiKey;
}
