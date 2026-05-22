package com.aitrade.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TestLlmResult {
    private boolean ok;
    private String message;
    /** 成功时模型回复（截断到 500 字）；失败时 null */
    private String reply;
    /** 失败时的技术细节；成功时 null */
    private String error;
}
