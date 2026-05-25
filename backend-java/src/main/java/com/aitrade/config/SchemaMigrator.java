package com.aitrade.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动时检查 ai_trader 表是否包含任务2 新加的列，缺啥补啥。
 * 适配老库（任务1/4/5 建立的）平滑升级。
 * SQLite 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，必须先查 PRAGMA。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMigrator {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("enabled", "INTEGER DEFAULT 1");
        required.put("deleted", "INTEGER DEFAULT 0");
        required.put("ma_short", "INTEGER DEFAULT 5");
        required.put("ma_long", "INTEGER DEFAULT 20");
        required.put("llm_base_url", "VARCHAR(256)");
        required.put("llm_api_key", "VARCHAR(256)");
        required.put("llm_model", "VARCHAR(64)");
        required.put("llm_prompt", "TEXT");
        required.put("initial_balance", "DECIMAL(18,2) DEFAULT 1000000");
        required.put("template_id", "INTEGER");
        required.put("indicator_config_json", "TEXT");
        required.put("cta_config_json", "TEXT");
        required.put("factor_config_json", "TEXT");
        required.put("script_code", "TEXT");
        required.put("pool_name", "VARCHAR(32)");

        Set<String> existing = new HashSet<>();
        List<Map<String, Object>> rows = jdbc.queryForList("PRAGMA table_info(ai_trader)");
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null) existing.add(String.valueOf(name).toLowerCase());
        }
        if (existing.isEmpty()) {
            log.warn("[schema-migrator] ai_trader table not found, skip (schema.sql will create)");
            return;
        }

        for (Map.Entry<String, String> col : required.entrySet()) {
            if (existing.contains(col.getKey().toLowerCase())) continue;
            String sql = "ALTER TABLE ai_trader ADD COLUMN " + col.getKey() + " " + col.getValue();
            try {
                jdbc.execute(sql);
                log.info("[schema-migrator] added column ai_trader.{}", col.getKey());
            } catch (Exception e) {
                log.error("[schema-migrator] failed to add column {}: {}", col.getKey(), e.getMessage());
            }
        }

        // SQLite 的 ALTER ADD COLUMN 的 DEFAULT 只对新行生效，老行得显式 backfill。
        try {
            int n = jdbc.update("UPDATE ai_trader SET initial_balance = 1000000 WHERE initial_balance IS NULL");
            if (n > 0) log.info("[schema-migrator] backfilled initial_balance=1000000 for {} legacy trader(s)", n);
        } catch (Exception e) {
            log.warn("[schema-migrator] backfill initial_balance: {}", e.getMessage());
        }

        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_trader_profit ON ai_trader(total_profit DESC)");
        } catch (Exception e) {
            log.warn("[schema-migrator] idx_trader_profit: {}", e.getMessage());
        }

        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_llm_act_user ON llm_activity(user_id, id DESC)");
        } catch (Exception e) {
            log.warn("[schema-migrator] idx_llm_act_user: {}", e.getMessage());
        }

        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_trader_template ON ai_trader(template_id)");
        } catch (Exception e) {
            log.warn("[schema-migrator] idx_trader_template: {}", e.getMessage());
        }

        migrateLlmActivity();
        ensureDecisionMemoryTable();
        ensureStrategyTemplateTable();
        seedStrategyTemplates();
        migrateBacktestTask();
        migrateBacktestTrade();
        migratePosition();
    }

    /** position 表加 high_since_entry 列：CTA 跟踪止损用持仓期间最高价。
     *  老仓没有这个字段，回填为 max(current_price, cost_price) 让跟踪止损有起点。 */
    private void migratePosition() {
        Set<String> existing = new HashSet<>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("PRAGMA table_info(position)");
        } catch (Exception e) {
            log.warn("[schema-migrator] PRAGMA position failed: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null) existing.add(String.valueOf(name).toLowerCase());
        }
        if (existing.isEmpty()) {
            log.warn("[schema-migrator] position not found, skip (schema.sql will create)");
            return;
        }
        if (!existing.contains("high_since_entry")) {
            try {
                jdbc.execute("ALTER TABLE position ADD COLUMN high_since_entry DECIMAL(10,3)");
                log.info("[schema-migrator] added column position.high_since_entry");
            } catch (Exception e) {
                log.error("[schema-migrator] failed to add column position.high_since_entry: {}", e.getMessage());
                return;
            }
            try {
                int n = jdbc.update(
                        "UPDATE position SET high_since_entry = " +
                        "CASE WHEN current_price IS NOT NULL AND current_price > cost_price " +
                        "     THEN current_price ELSE cost_price END " +
                        "WHERE high_since_entry IS NULL AND amount > 0");
                if (n > 0) log.info("[schema-migrator] backfilled high_since_entry for {} legacy position(s)", n);
            } catch (Exception e) {
                log.warn("[schema-migrator] backfill high_since_entry: {}", e.getMessage());
            }
        }
    }

    /** 任务3 阶段二：backtest_task 加专业指标列（Sharpe/Sortino/Calmar/年化/胜率/盈亏比 + 基准 + 月度收益）。 */
    private void migrateBacktestTask() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("sharpe_ratio", "DECIMAL(10,4)");
        required.put("sortino_ratio", "DECIMAL(10,4)");
        required.put("calmar_ratio", "DECIMAL(10,4)");
        required.put("annual_return_pct", "DECIMAL(10,4)");
        required.put("win_rate_pct", "DECIMAL(10,4)");
        required.put("profit_loss_ratio", "DECIMAL(10,4)");
        required.put("benchmark_curve_json", "TEXT");
        required.put("monthly_returns_json", "TEXT");

        Set<String> existing = new HashSet<>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("PRAGMA table_info(backtest_task)");
        } catch (Exception e) {
            log.warn("[schema-migrator] PRAGMA backtest_task failed: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null) existing.add(String.valueOf(name).toLowerCase());
        }
        if (existing.isEmpty()) {
            log.warn("[schema-migrator] backtest_task not found, skip (schema.sql will create)");
            return;
        }

        for (Map.Entry<String, String> col : required.entrySet()) {
            if (existing.contains(col.getKey().toLowerCase())) continue;
            String sql = "ALTER TABLE backtest_task ADD COLUMN " + col.getKey() + " " + col.getValue();
            try {
                jdbc.execute(sql);
                log.info("[schema-migrator] added column backtest_task.{}", col.getKey());
            } catch (Exception e) {
                log.error("[schema-migrator] failed to add column backtest_task.{}: {}", col.getKey(), e.getMessage());
            }
        }
    }

    /** backtest_trade 加 cost_price 列：用于展示卖出时的成本价、前端算每笔盈亏。 */
    private void migrateBacktestTrade() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("cost_price", "DECIMAL(10,3)");
        required.put("stock_name", "VARCHAR(64)");

        Set<String> existing = new HashSet<>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("PRAGMA table_info(backtest_trade)");
        } catch (Exception e) {
            log.warn("[schema-migrator] PRAGMA backtest_trade failed: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null) existing.add(String.valueOf(name).toLowerCase());
        }
        if (existing.isEmpty()) {
            log.warn("[schema-migrator] backtest_trade not found, skip (schema.sql will create)");
            return;
        }
        for (Map.Entry<String, String> col : required.entrySet()) {
            if (existing.contains(col.getKey().toLowerCase())) continue;
            String sql = "ALTER TABLE backtest_trade ADD COLUMN " + col.getKey() + " " + col.getValue();
            try {
                jdbc.execute(sql);
                log.info("[schema-migrator] added column backtest_trade.{}", col.getKey());
            } catch (Exception e) {
                log.error("[schema-migrator] failed to add column backtest_trade.{}: {}", col.getKey(), e.getMessage());
            }
        }
    }

    private void ensureStrategyTemplateTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS strategy_template (
                        id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                        code                VARCHAR(64) NOT NULL UNIQUE,
                        name                VARCHAR(64) NOT NULL,
                        description         TEXT,
                        strategy_type       VARCHAR(16) NOT NULL,
                        default_params_json TEXT NOT NULL,
                        tags                VARCHAR(128),
                        is_official         INTEGER DEFAULT 1,
                        sort_order          INTEGER DEFAULT 0,
                        created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tpl_official ON strategy_template(is_official, sort_order)");
        } catch (Exception e) {
            log.error("[schema-migrator] ensure strategy_template failed: {}", e.getMessage());
        }
    }

    /** 首次启动表为空时插入官方种子模板。按 code UNIQUE 兜底，重复运行无副作用。 */
    private void seedStrategyTemplates() {
        try {
            Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM strategy_template", Integer.class);
            if (cnt != null && cnt > 0) {
                // 表已有数据：仍补插 CTA 两个模板（如果 code 已存在会被 INSERT OR IGNORE 安静跳过），
                // 避免老库升级后用户在模板市场里看不到 CTA。
                seedCtaTemplates();
                return;
            }

            String maClassic = "{\"maShort\":5,\"maLong\":20}";
            String maMid = "{\"maShort\":10,\"maLong\":60}";
            String llmValue = "{\"llmBaseUrl\":\"https://api.deepseek.com\",\"llmModel\":\"deepseek-chat\","
                    + "\"llmPrompt\":\"你是一名稳健的价值投资型 A 股交易员。偏好低估值、稳定现金流的蓝筹股，回避高波动小盘股。"
                    + "持仓集中度上限 5 只；单只浮亏 8% 触发止损；连续上涨 20% 后逐步减仓。盘整期允许空仓观察。\"}";
            String llmTrend = "{\"llmBaseUrl\":\"https://api.deepseek.com\",\"llmModel\":\"deepseek-chat\","
                    + "\"llmPrompt\":\"你是一名趋势跟随型 A 股交易员。仅在突破 20 日均线且成交量放大时买入；"
                    + "跌破 10 日均线立即清仓。允许追高但拒绝抄底。盘整期保持轻仓。一次最多持有 3 只票。\"}";

            jdbc.update("INSERT OR IGNORE INTO strategy_template (code, name, description, strategy_type, default_params_json, tags, is_official, sort_order) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                    "ma-classic-5-20", "MA 经典金叉",
                    "5 日 / 20 日双均线金叉买入、死叉卖出。新手最易理解的趋势策略，适合在震荡偏多市场使用。",
                    "MA", maClassic, "趋势,短线", 10);
            jdbc.update("INSERT OR IGNORE INTO strategy_template (code, name, description, strategy_type, default_params_json, tags, is_official, sort_order) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                    "ma-midterm-10-60", "MA 中线持有",
                    "10 日 / 60 日双均线策略，过滤短期噪音，捕捉中期趋势。换手率更低，更适合中线持仓。",
                    "MA", maMid, "趋势,中线", 20);
            jdbc.update("INSERT OR IGNORE INTO strategy_template (code, name, description, strategy_type, default_params_json, tags, is_official, sort_order) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                    "llm-value-investor", "LLM 价值投资型",
                    "由 LLM 扮演价值投资者，偏好低估值蓝筹，单只止损 8%。使用前需自备 OpenAI 兼容 API Key。",
                    "LLM", llmValue, "LLM,价值", 30);
            jdbc.update("INSERT OR IGNORE INTO strategy_template (code, name, description, strategy_type, default_params_json, tags, is_official, sort_order) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                    "llm-trend-follower", "LLM 趋势跟随型",
                    "由 LLM 扮演趋势跟随者，仅在突破时买入，跌破短均线即清仓。使用前需自备 OpenAI 兼容 API Key。",
                    "LLM", llmTrend, "LLM,趋势", 40);
            log.info("[schema-migrator] seeded 4 official strategy templates");

            seedCtaTemplates();
        } catch (Exception e) {
            log.warn("[schema-migrator] seed strategy_template failed: {}", e.getMessage());
        }
    }

    /** CTA 模板单独抽出来：老库升级时也能补插（INSERT OR IGNORE 不会重复）。 */
    private void seedCtaTemplates() {
        try {
            String ctaDualMa = "{\"entry\":{\"type\":\"DUAL_MA\",\"shortPeriod\":5,\"longPeriod\":20},"
                    + "\"stopLoss\":{\"fixedPct\":8.0,\"trailingPct\":5.0},"
                    + "\"exitOnReverseSignal\":true}";
            String ctaBreakout = "{\"entry\":{\"type\":\"BREAKOUT\",\"lookback\":20},"
                    + "\"stopLoss\":{\"trailingPct\":5.0},"
                    + "\"exitOnReverseSignal\":false}";

            int a = jdbc.update("INSERT OR IGNORE INTO strategy_template (code, name, description, strategy_type, default_params_json, tags, is_official, sort_order) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                    "cta-dual-ma-5-20", "CTA 双均线趋势",
                    "5/20 双均线金叉买入、死叉卖出；同时配 8% 固定止损 + 5% 跟踪止损。比单纯 MA 更稳健——亏到 8% 强制止损，盈利后回撤 5% 止盈出场。",
                    "CTA", ctaDualMa, "趋势,CTA,止损", 50);
            int b = jdbc.update("INSERT OR IGNORE INTO strategy_template (code, name, description, strategy_type, default_params_json, tags, is_official, sort_order) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                    "cta-breakout-20d", "CTA 20 日突破跟踪",
                    "唐奇安通道思想：今日收盘价突破过去 20 日最高价时买入，5% 跟踪止损让利润奔跑。突破策略经典玩法，适合追趋势。",
                    "CTA", ctaBreakout, "突破,CTA,跟踪止损", 60);
            if (a + b > 0) log.info("[schema-migrator] seeded {} CTA template(s)", a + b);
        } catch (Exception e) {
            log.warn("[schema-migrator] seed CTA templates failed: {}", e.getMessage());
        }
    }

    private void ensureDecisionMemoryTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS llm_decision_memory (
                        id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                        trader_id           INTEGER NOT NULL,
                        user_id             INTEGER,
                        decision_id         INTEGER NOT NULL,
                        order_id            INTEGER,
                        stock_code          VARCHAR(16) NOT NULL,
                        side                VARCHAR(8) NOT NULL,
                        amount              INTEGER NOT NULL,
                        price_at_decision   DECIMAL(18,4) NOT NULL,
                        indicators_snapshot TEXT,
                        reason              TEXT,
                        created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        verified_at         TIMESTAMP,
                        verify_horizon_days INTEGER,
                        price_after_horizon DECIMAL(18,4),
                        actual_return_pct   DECIMAL(10,4),
                        was_correct         INTEGER
                    )
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_dec_mem_trader_time ON llm_decision_memory(trader_id, created_at DESC)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_dec_mem_pending ON llm_decision_memory(verified_at) WHERE verified_at IS NULL");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_dec_mem_user ON llm_decision_memory(user_id, created_at DESC)");
        } catch (Exception e) {
            log.error("[schema-migrator] ensure llm_decision_memory failed: {}", e.getMessage());
        }
    }

    private void migrateLlmActivity() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("prompt_json", "TEXT");

        Set<String> existing = new HashSet<>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("PRAGMA table_info(llm_activity)");
        } catch (Exception e) {
            log.warn("[schema-migrator] PRAGMA llm_activity failed: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null) existing.add(String.valueOf(name).toLowerCase());
        }
        if (existing.isEmpty()) {
            log.warn("[schema-migrator] llm_activity table not found, skip (schema.sql will create)");
            return;
        }

        for (Map.Entry<String, String> col : required.entrySet()) {
            if (existing.contains(col.getKey().toLowerCase())) continue;
            String sql = "ALTER TABLE llm_activity ADD COLUMN " + col.getKey() + " " + col.getValue();
            try {
                jdbc.execute(sql);
                log.info("[schema-migrator] added column llm_activity.{}", col.getKey());
            } catch (Exception e) {
                log.error("[schema-migrator] failed to add column llm_activity.{}: {}", col.getKey(), e.getMessage());
            }
        }
    }
}
