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
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "backtest-worker");
        t.setDaemon(true);
        return t;
    });

    private final BacktestTaskMapper taskMapper;
    private final BacktestTradeMapper tradeMapper;
    private final AiTraderMapper traderMapper;
    private final BacktestEngine engine;

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
        if (!"MA".equalsIgnoreCase(trader.getStrategyType())) {
            throw ApiException.badRequest("当前只支持 MA 策略回测，trader 策略类型: " + trader.getStrategyType());
        }

        LocalDate start = parseDate(req.getStartDate(), "startDate");
        LocalDate end = parseDate(req.getEndDate(), "endDate");
        if (start.isAfter(end)) throw ApiException.badRequest("startDate 必须不晚于 endDate");

        BigDecimal initial = req.getInitialBalance() == null
                ? DEFAULT_INITIAL
                : req.getInitialBalance().setScale(2, RoundingMode.HALF_UP);
        if (initial.signum() <= 0) throw ApiException.badRequest("initialBalance 必须大于 0");

        BacktestTask task = new BacktestTask();
        task.setUserId(userId);
        task.setTraderId(trader.getId());
        task.setTraderName(trader.getName());
        task.setStrategyType("MA");
        task.setStrategyParams(String.format("MA%d/%d",
                trader.getMaShort() == null ? 5 : trader.getMaShort(),
                trader.getMaLong() == null ? 20 : trader.getMaLong()));
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
            BacktestEngine.BacktestResult result = engine.run(
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
                row.setSide(et.side());
                row.setAmount(et.amount());
                row.setPrice(et.price().setScale(3, RoundingMode.HALF_UP));
                row.setBalanceAfter(et.balanceAfter().setScale(2, RoundingMode.HALF_UP));
                row.setReason(truncate(et.reason(), 240));
                tradeMapper.insert(row);
            }

            task.setStatus("DONE");
            task.setProgress(100);
            task.setFinalEquity(result.finalEquity().setScale(2, RoundingMode.HALF_UP));
            task.setTotalReturnPct(result.totalReturnPct());
            task.setMaxDrawdownPct(result.maxDrawdownPct());
            task.setTotalTrades(result.trades().size());
            try {
                task.setEquityCurveJson(JSON.writeValueAsString(result.equityCurveAsList()));
            } catch (Exception e) {
                log.warn("[backtest-svc] task {} serialize equity failed: {}", taskId, e.getMessage());
                task.setEquityCurveJson("[]");
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
}
