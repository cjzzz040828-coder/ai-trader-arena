package com.aitrade.backtest;

import com.aitrade.entity.AiTrader;
import com.aitrade.entity.LlmDecisionMemory;
import com.aitrade.mapper.LlmDecisionMemoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntConsumer;

/**
 * LLM 决策回放回测引擎。
 *
 * 跟 BacktestEngine 的区别：
 *   - BacktestEngine 是"在历史 K 线上重新跑策略 executor"，每日 close 由策略产生信号 → 落单
 *   - LlmReplayEngine 是"读 llm_decision_memory 里 LLM 已经下过的真实决策，按时间顺序重演"
 *
 * 为什么这样设计：LLM 决策本身不可复现（每次调用 LLM 都可能给出不同答案），硬接 LLM 回测
 * 既贵又有"事后诸葛亮"风险（GPT 训练数据里知道历史走势）。决策回放则用 trader 在模拟盘里
 * 真实落库的决策作为唯一真相，回测只算"如果这些决策都按 LLM 给的 price_at_decision 成交，
 * 资金曲线会怎样"。
 *
 * 撮合规则（**跟 BacktestEngine 不同**）：LLM 实盘是盘中分钟级实时撮合，price_at_decision 是
 * 决策当时的真实成交价。回放时直接按该价格在**当日**即时成交（sandbox.executeImmediate），
 * 不走 T+1 next-day open 那条路径。理由：
 *   1. 这样回放出来的指标 = LLM 实盘真实指标（用统一框架算夏普/回撤/胜率/盈亏比）
 *   2. T+1 next-day open 限价撮合会在跳空时产生明显偏差，违背"忠实重现"目的
 *   3. T+1 **持仓规则**（A 股法定）仍守：当日 BUY 进 todayBuys，同日 SELL 被拒
 *
 * 数据要求：trader 必须在 [startDate, endDate] 窗口内有至少一条 llm_decision_memory，
 * 否则抛清晰错误（前端引导用户去让 trader 跑一段时间再来）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmReplayEngine {

    private final BacktestEngine engine;
    private final LlmDecisionMemoryMapper decisionMapper;

    public BacktestEngine.BacktestResult run(AiTrader trader,
                                              LocalDate startDate,
                                              LocalDate endDate,
                                              BigDecimal initialBalance,
                                              IntConsumer progressCallback) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate 必须不晚于 endDate");
        }

        // 1. 拉决策记忆：[startDate 00:00, endDate 23:59:59] 的所有该 trader 的决策
        LocalDateTime fromTs = startDate.atStartOfDay();
        LocalDateTime toTs = endDate.atTime(23, 59, 59);
        List<LlmDecisionMemory> decisions = decisionMapper.selectList(new QueryWrapper<LlmDecisionMemory>()
                .eq("trader_id", trader.getId())
                .ge("created_at", fromTs)
                .le("created_at", toTs)
                .orderByAsc("created_at"));
        if (decisions.isEmpty()) {
            throw new IllegalStateException("LLM trader '" + trader.getName() + "' 在 "
                    + startDate + " ~ " + endDate + " 窗口内没有任何决策记录。"
                    + "请先让 trader 在开市时段运行一段时间（每个交易日会产生 1-N 条决策），"
                    + "或缩小回测窗口到 trader 实际跑过的日期。");
        }
        log.info("[llm-replay] trader {} replay {} decisions in window {} ~ {}",
                trader.getId(), decisions.size(), startDate, endDate);

        // 2. 提取所有出现过的股票代码 + 决策时的 name（兜底用 indicatorsSnapshot 没存 name，故先记 null）
        Set<String> codeSet = new HashSet<>();
        Map<String, String> namesByCode = new HashMap<>();
        for (LlmDecisionMemory d : decisions) {
            if (d.getStockCode() != null && !d.getStockCode().isBlank()) {
                codeSet.add(d.getStockCode());
            }
        }
        List<String> codes = new ArrayList<>(codeSet);

        // 3. 预拉 K 线：复用 BacktestEngine.fetchBars
        Map<String, List<Map<String, Object>>> fullBars = new HashMap<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            List<Map<String, Object>> bars = engine.fetchBars(code);
            if (!bars.isEmpty()) fullBars.put(code, bars);
            if ((i + 1) % 20 == 0 || i + 1 == codes.size()) {
                long elapsed = System.currentTimeMillis() - t0;
                log.info("[llm-replay] trader {} bars progress: {}/{} ({}ms, {} hit)",
                        trader.getId(), i + 1, codes.size(), elapsed, fullBars.size());
                if (progressCallback != null) {
                    int prog = Math.min(14, (i + 1) * 15 / codes.size());
                    progressCallback.accept(prog);
                }
            }
        }
        if (fullBars.isEmpty()) {
            throw new IllegalStateException("所有决策涉及的股票都拉不到历史 K 线，无法回放");
        }

        // 3.5 顺手补股票名（namesByCode 此前是空 map，fills 显示成 6 位代码不友好）。
        //     复用 BacktestEngine 的 snapshot 合并逻辑；snapshot 拉不到就接受代码当名字。
        engine.mergeNamesFromSnapshot(codes, namesByCode);

        // 4. 基准 K 线（510300）
        List<Map<String, Object>> benchmarkBars = engine.fetchBars("510300");
        if (benchmarkBars.isEmpty()) {
            log.info("[llm-replay] benchmark 510300 拉取为空，报告无基准对比");
        }

        // 5. 交易日序列：用 fullBars 全集的日期并集，落在窗口内
        List<LocalDate> tradingDays = engine.computeTradingDays(fullBars, startDate, endDate);
        if (tradingDays.size() < 2) {
            throw new IllegalStateException("回测窗口内交易日不足（need ≥ 2）：" + tradingDays.size());
        }

        // 6. 决策按日期 group（用 created_at 的本地日期）
        TreeMap<LocalDate, List<LlmDecisionMemory>> decisionsByDay = new TreeMap<>();
        ZoneId zone = ZoneId.systemDefault();
        for (LlmDecisionMemory d : decisions) {
            LocalDate day = d.getCreatedAt() == null
                    ? null
                    : d.getCreatedAt().atZone(zone).toLocalDate();
            if (day == null) continue;
            decisionsByDay.computeIfAbsent(day, k -> new ArrayList<>()).add(d);
        }

        // 7. 主循环
        BacktestSandbox sandbox = new BacktestSandbox(initialBalance);
        List<BacktestEngine.EquityPoint> equityCurve = new ArrayList<>();
        List<BacktestEngine.EngineTrade> trades = new ArrayList<>();

        log.info("[llm-replay] trader {} window {}~{} 交易日 {} 天, 决策日 {} 天, codes {} 只",
                trader.getId(), startDate, endDate, tradingDays.size(),
                decisionsByDay.size(), codes.size());

        for (int i = 0; i < tradingDays.size(); i++) {
            LocalDate today = tradingDays.get(i);

            // A. 当日所有 LLM 决策按 created_at 顺序即时成交（price_at_decision 当日落账）。
            //    sandbox.executeImmediate 内部会守 T+1：BUY 当日入 todayBuys，同日 SELL 被拒。
            List<LlmDecisionMemory> todays = decisionsByDay.getOrDefault(today, Collections.emptyList());
            int filled = 0;
            for (LlmDecisionMemory d : todays) {
                BacktestSandbox.Fill fill = executeDecision(d, sandbox);
                if (fill == null) continue;
                filled++;
                trades.add(new BacktestEngine.EngineTrade(
                        today.toString(), fill.code(),
                        namesByCode.getOrDefault(fill.code(), fill.code()),
                        fill.side(),
                        fill.amount(), fill.price(), fill.balanceAfter(), fill.costPriceAtSell(),
                        d.getReason() == null ? "" : d.getReason()));
            }
            if (!todays.isEmpty()) {
                log.debug("[llm-replay] {} executed {}/{} decisions", today, filled, todays.size());
            }

            // B. 记 today close 净值
            Map<String, BigDecimal> closeByCode = engine.collectPricesAt(today, fullBars, codes, true);
            BigDecimal equity = sandbox.equity(closeByCode);
            equityCurve.add(new BacktestEngine.EquityPoint(today.toString(), equity));

            // C. 切到下一交易日：清 T+1 标志（当日 BUY 的票次日才能 SELL）
            if (i + 1 < tradingDays.size()) {
                sandbox.clearTodayBuys();
            }

            if (progressCallback != null) {
                int prog = Math.min(99, 15 + (i + 1) * 85 / tradingDays.size());
                if (i == tradingDays.size() - 1) prog = 100;
                progressCallback.accept(prog);
            }
        }

        return engine.summarize(initialBalance, equityCurve, trades, benchmarkBars, tradingDays);
    }

    /**
     * 把一条 LLM 决策按 price_at_decision 当日即时成交。失败返回 null。
     *
     * SELL 容错：LLM 实盘里 SELL 的前置 BUY 一定成交了，但回测 sandbox 可能因为前面某些
     * BUY 余额不足而持仓不足。这种情况下退化成"能卖多少卖多少"（取 min(amt, pos.amount)
     * 向下整百），避免一笔卡死后续 SELL 链全断；持仓 < 100 直接放弃这条 SELL。
     */
    private BacktestSandbox.Fill executeDecision(LlmDecisionMemory d, BacktestSandbox sandbox) {
        if (d.getStockCode() == null || d.getSide() == null || d.getAmount() == null
                || d.getPriceAtDecision() == null || d.getAmount() <= 0) return null;
        BigDecimal price = d.getPriceAtDecision();
        if (price.signum() <= 0) return null;
        int amt = (d.getAmount() / 100) * 100;
        if (amt <= 0) return null;

        if ("SELL".equalsIgnoreCase(d.getSide())) {
            BacktestSandbox.Pos pos = sandbox.position(d.getStockCode());
            if (pos != null && pos.amount > 0 && pos.amount < amt) {
                amt = (pos.amount / 100) * 100;
                if (amt <= 0) return null;
            }
        }
        return sandbox.executeImmediate(d.getStockCode(), d.getSide(), amt, price);
    }
}
