package com.aitrade.trade.strategy.llm;

/**
 * LLM 决策过程中的一个事件，会被同时推 SSE 和落 DB。
 * phase 取值约定（前后端共识）：
 *   started        — 决策开始（含 trader name / model 等元信息塞 message）
 *   tool_call      — 即将调用一个工具（toolName + argsJson）
 *   tool_result    — 工具调用返回（toolName + resultJson，可能巨大）
 *   final          — LLM 给出最终总结，决策结束（message = 总结文本）
 *   failed         — 异常退出（message = 错误描述）
 *   cancel_requested — 用户点了"停手"，标志已设但尚未生效
 *   cancelled      — 在下一轮工具调用前真正生效，决策已停
 */
public record LlmActivityEvent(
        Long traderId,
        Long userId,
        long decisionId,
        int seq,
        String phase,
        Integer round,
        String toolName,
        String toolCallId,
        String argsJson,
        String resultJson,
        String message,
        String promptJson,
        String createdAt
) {}
