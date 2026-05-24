package com.aitrade.backtest;

import com.aitrade.backtest.dto.BacktestRequest;
import com.aitrade.backtest.dto.BacktestTaskVO;
import com.aitrade.backtest.dto.BacktestTradeVO;
import com.aitrade.common.ApiException;
import com.aitrade.entity.AiTrader;
import com.aitrade.entity.BacktestTask;
import com.aitrade.entity.BacktestTrade;
import com.aitrade.mapper.AiTraderMapper;
import com.aitrade.mapper.BacktestTaskMapper;
import com.aitrade.mapper.BacktestTradeMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private static final BigDecimal DEFAULT_INITIAL = new BigDecimal("1000000");
    private static final ObjectMapper JSON = new ObjectMapper();
    // 网关 bars 接口只能拉最近 N 根（BacktestEngine.BAR_COUNT=300），按周末/节假日打折后约支撑 1 年自然日窗口。
    private static final int MAX_LOOKBACK_DAYS = 400;
    private static final int MAX_WINDOW_DAYS = 400;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "backtest-worker");
        t.setDaemon(true);
        return t;
    });

    private final BacktestTaskMapper taskMapper;
    private final BacktestTradeMapper tradeMapper;
    private final AiTraderMapper traderMapper;
    private final BacktestEngine engine;
    private final LlmReplayEngine llmReplayEngine;

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public BacktestTaskVO create(BacktestRequest req, Long userId) {
        if (req.getTraderId() == null) throw ApiException.badRequest("traderId 必填");
        AiTrader trader = traderMapper.selectById(req.getTraderId());
        if (trader == null || trader.getDeleted() != null && trader.getDeleted() == 1) {
            throw ApiException.badRequest("trader 不存在");
        }
        if (!trader.getUserId().equals(userId)) throw ApiException.unauthorized("非本人 trader");
        String strategyType = trader.getStrategyType() == null ? "" : trader.getStrategyType().toUpperCase();
        if (!"MA".equals(strategyType) && !"INDICATOR".equals(strategyType)
                && !"SCRIPT".equals(strategyType) && !"LLM".equals(strategyType)) {
            throw ApiException.badRequest("当前只支持 MA / INDICATOR / SCRIPT / LLM 策略回测，trader 策略类型: " + trader.getStrategyType());
        }

        LocalDate start = parseDate(req.getStartDate(), "startDate");
        LocalDate end = parseDate(req.getEndDate(), "endDate");
        if (start.isAfter(end)) throw ApiException.badRequest("startDate 必须不晚于 endDate");

        // 网关 bars 接口只能拉最近 N 根（BacktestEngine.BAR_COUNT=300，约 1.2 年自然日）。
        // 超出窗口的回测虽然能跑但会因 K 线稀疏报"交易日不足"或结果失真，提前给清晰错误信息。
        LocalDate today = LocalDate.now();
        if (start.isBefore(today.minusDays(MAX_LOOKBACK_DAYS))) {
            throw ApiException.badRequest("startDate 不能早于 " + MAX_LOOKBACK_DAYS
                    + " 天前（gateway 日 K 只能拉最近约 1 年）");
        }
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end)) {
            throw ApiException.badRequest("回测窗口跨度不可超过 " + MAX_WINDOW_DAYS + " 天");
        }
        if (end.isAfter(today)) {
            throw ApiException.badRequest("endDate 不能晚于今天");
        }

        BigDecimal initial = req.getInitialBalance() == null
                ? DEFAULT_INITIAL
                : req.getInitialBalance().setScale(2, RoundingMode.HALF_UP);
        if (initial.signum() <= 0) throw ApiException.badRequest("initialBalance 必须大于 0");

        BacktestTask task = new BacktestTask();
        task.setUserId(userId);
        task.setTraderId(trader.getId());
        task.setTraderName(trader.getName());
        task.setStrategyType(strategyType);
        task.setStrategyParams(buildStrategyParamsLabel(trader, strategyType));
        task.setStartDate(start.toString());
        task.setEndDate(end.toString());
        task.setInitialBalance(initial);
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setTotalTrades(0);
        task.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        Long taskId = task.getId();
        pool.submit(() -> runTask(taskId));

        return BacktestTaskVO.from(task);
    }

    public BacktestTaskVO get(Long taskId, Long userId) {
        BacktestTask t = taskMapper.selectById(taskId);
        if (t == null) throw ApiException.badRequest("任务不存在");
        if (!t.getUserId().equals(userId)) throw ApiException.unauthorized("非本人任务");
        return BacktestTaskVO.from(t);
    }

    public List<BacktestTaskVO> listByUser(Long userId) {
        List<BacktestTask> rows = taskMapper.selectList(new QueryWrapper<BacktestTask>()
                .eq("user_id", userId).orderByDesc("created_at").last("LIMIT 100"));
        List<BacktestTaskVO> out = new ArrayList<>(rows.size());
        for (BacktestTask t : rows) out.add(BacktestTaskVO.from(t));
        return out;
    }

    public List<BacktestTradeVO> trades(Long taskId, Long userId) {
        BacktestTask t = taskMapper.selectById(taskId);
        if (t == null) throw ApiException.badRequest("任务不存在");
        if (!t.getUserId().equals(userId)) throw ApiException.unauthorized("非本人任务");
        List<BacktestTrade> rows = tradeMapper.selectList(new QueryWrapper<BacktestTrade>()
                .eq("task_id", taskId).orderByAsc("trade_date").orderByAsc("id"));
        List<BacktestTradeVO> out = new ArrayList<>(rows.size());
        for (BacktestTrade r : rows) out.add(BacktestTradeVO.from(r));
        return out;
    }

    // ---------------- 异步执行 ----------------

    private void runTask(Long taskId) {
        BacktestTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("[backtest-svc] task {} disappeared", taskId);
            return;
        }
        AiTrader trader = traderMapper.selectById(task.getTraderId());
        if (trader == null) {
            markFailed(task, "trader 不存在");
            return;
        }

        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        try {
            // LLM 策略走"决策回放"引擎：读 llm_decision_memory 历史决策按时序重演；
            // 其它策略走原引擎：在历史 K 线上重新跑策略 executor。
            String strategyType = trader.getStrategyType() == null ? "" : trader.getStrategyType().toUpperCase();
            BacktestEngine.BacktestResult result = "LLM".equals(strategyType)
                    ? llmReplayEngine.run(
                            trader,
                            LocalDate.parse(task.getStartDate()),
                            LocalDate.parse(task.getEndDate()),
                            task.getInitialBalance(),
                            progress -> updateProgress(taskId, progress))
                    : engine.run(
                            trader,
                            LocalDate.parse(task.getStartDate()),
                            LocalDate.parse(task.getEndDate()),
                            task.getInitialBalance(),
                            progress -> updateProgress(taskId, progress));

            // 写成交明细
            for (BacktestEngine.EngineTrade et : result.trades()) {
                BacktestTrade row = new BacktestTrade();
                row.setTaskId(taskId);
                row.setTradeDate(et.date());
                row.setStockCode(et.code());
                row.setStockName(et.name());
                row.setSide(et.side());
                row.setAmount(et.amount());
                row.setPrice(et.price().setScale(3, RoundingMode.HALF_UP));
                row.setBalanceAfter(et.balanceAfter().setScale(2, RoundingMode.HALF_UP));
                if (et.costPriceAtSell() != null) {
                    row.setCostPrice(et.costPriceAtSell().setScale(3, RoundingMode.HALF_UP));
                }
                row.setReason(truncate(et.reason(), 240));
                tradeMapper.insert(row);
            }

            task.setStatus("DONE");
            task.setProgress(100);
            task.setFinalEquity(result.finalEquity().setScale(2, RoundingMode.HALF_UP));
            task.setTotalReturnPct(result.totalReturnPct());
            task.setMaxDrawdownPct(result.maxDrawdownPct());
            task.setTotalTrades(result.trades().size());
            task.setSharpeRatio(result.sharpeRatio());
            task.setSortinoRatio(result.sortinoRatio());
            task.setCalmarRatio(result.calmarRatio());
            task.setAnnualReturnPct(result.annualReturnPct());
            task.setWinRatePct(result.winRatePct());
            task.setProfitLossRatio(result.profitLossRatio());
            try {
                task.setEquityCurveJson(JSON.writeValueAsString(result.equityCurveAsList()));
            } catch (Exception e) {
                log.warn("[backtest-svc] task {} serialize equity failed: {}", taskId, e.getMessage());
                task.setEquityCurveJson("[]");
            }
            try {
                task.setBenchmarkCurveJson(JSON.writeValueAsString(result.benchmarkCurveAsList()));
            } catch (Exception e) {
                log.warn("[backtest-svc] task {} serialize benchmark failed: {}", taskId, e.getMessage());
                task.setBenchmarkCurveJson("[]");
            }
            try {
                task.setMonthlyReturnsJson(JSON.writeValueAsString(result.monthlyReturnsAsList()));
            } catch (Exception e) {
                log.warn("[backtest-svc] task {} serialize monthly failed: {}", taskId, e.getMessage());
                task.setMonthlyReturnsJson("[]");
            }
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            log.info("[backtest-svc] task {} done: return={}% maxDD={}% trades={}",
                    taskId, task.getTotalReturnPct(), task.getMaxDrawdownPct(), task.getTotalTrades());
        } catch (Exception e) {
            log.error("[backtest-svc] task {} failed: {}", taskId, e.getMessage(), e);
            markFailed(task, e.getMessage());
        }
    }

    private void updateProgress(Long taskId, int progress) {
        try {
            BacktestTask patch = new BacktestTask();
            patch.setId(taskId);
            patch.setProgress(progress);
            taskMapper.updateById(patch);
        } catch (Exception ignored) {}
    }

    private void markFailed(BacktestTask task, String error) {
        task.setStatus("FAILED");
        task.setError(truncate(error, 1000));
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private static LocalDate parseDate(String s, String field) {
        if (s == null || s.isBlank()) throw ApiException.badRequest(field + " 必填");
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " 格式必须为 yyyy-MM-dd: " + s);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** 给 backtest_task.strategy_params 拼一个简短摘要：MA5/20 或 INDICATOR:2ind/1buy/1sell。 */
    private String buildStrategyParamsLabel(AiTrader trader, String strategyType) {
        if ("MA".equals(strategyType)) {
            return String.format("MA%d/%d",
                    trader.getMaShort() == null ? 5 : trader.getMaShort(),
                    trader.getMaLong() == null ? 20 : trader.getMaLong());
        }
        if ("INDICATOR".equals(strategyType)) {
            String json = trader.getIndicatorConfigJson();
            if (json == null || json.isBlank()) return "INDICATOR";
            try {
                var node = JSON.readTree(json);
                int ind = node.path("indicators").isArray() ? node.path("indicators").size() : 0;
                int buy = node.path("buyRules").isArray() ? node.path("buyRules").size() : 0;
                int sell = node.path("sellRules").isArray() ? node.path("sellRules").size() : 0;
                return String.format("INDICATOR:%dind/%dbuy/%dsell", ind, buy, sell);
            } catch (Exception ignored) {
                return "INDICATOR";
            }
        }
        if ("SCRIPT".equals(strategyType)) {
            String code = trader.getScriptCode();
            if (code == null) return "SCRIPT";
            int lines = code.split("\n", -1).length;
            return String.format("SCRIPT:%d行/%d字", lines, code.length());
        }
        if ("LLM".equals(strategyType)) {
            // LLM 策略不带可序列化的策略参数（prompt 是模糊的），用 model + prompt 长度做摘要
            String model = trader.getLlmModel() == null ? "?" : trader.getLlmModel();
            int promptLen = trader.getLlmPrompt() == null ? 0 : trader.getLlmPrompt().length();
            return String.format("LLM:%s/prompt%d字", model, promptLen);
        }
        return strategyType;
    }
}
