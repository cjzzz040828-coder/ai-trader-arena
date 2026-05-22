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
    }
}
