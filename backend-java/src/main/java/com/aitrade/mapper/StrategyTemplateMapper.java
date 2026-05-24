package com.aitrade.mapper;

import com.aitrade.entity.StrategyTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface StrategyTemplateMapper extends BaseMapper<StrategyTemplate> {

    /**
     * 一次性聚合每个模板下所有实例（基于 template_id 反查 ai_trader）的运行指标。
     *   - instance_count: 该模板创建的、未软删 trader 数
     *   - avg_return_pct: 各实例当前持仓 P&L / 初始资产 的算术平均（单位 %）
     *   - total_trades:   全部 FILLED 订单条数（一个 BUY/SELL 算一笔）
     *   - win_rate:       已 FILLED 的 SELL 单中，filled_price > 对应 trader 该股最近一笔 BUY 的 filled_price 的比例（单位 %）
     *
     * 注意：胜率口径简化为「SELL 比同股最近一次 BUY 价高即视为赢」，不做严格 FIFO 配对，避免一次大 JOIN 拖慢列表。
     */
    @Select("""
        SELECT t.id AS template_id,
               COALESCE(agg.instance_count, 0) AS instance_count,
               COALESCE(agg.avg_return_pct, 0) AS avg_return_pct,
               COALESCE(agg.total_trades, 0)   AS total_trades,
               COALESCE(agg.win_rate, 0)       AS win_rate
        FROM strategy_template t
        LEFT JOIN (
          SELECT a.template_id,
                 COUNT(*) AS instance_count,
                 AVG(CASE WHEN a.initial_balance > 0
                          THEN (a.total_profit * 100.0 / a.initial_balance)
                          ELSE 0 END) AS avg_return_pct,
                 COALESCE(SUM(o.filled_total), 0) AS total_trades,
                 CASE WHEN COALESCE(SUM(o.sell_total), 0) > 0
                      THEN COALESCE(SUM(o.sell_win), 0) * 100.0 / SUM(o.sell_total)
                      ELSE 0 END AS win_rate
          FROM ai_trader a
          LEFT JOIN (
            SELECT s.trader_id,
                   COUNT(*) AS filled_total,
                   SUM(CASE WHEN s.side='SELL' THEN 1 ELSE 0 END) AS sell_total,
                   SUM(CASE WHEN s.side='SELL' AND s.filled_price > IFNULL((
                                SELECT b.filled_price FROM trade_order b
                                WHERE b.trader_id = s.trader_id AND b.stock_code = s.stock_code
                                  AND b.side = 'BUY' AND b.status='FILLED' AND b.filled_at < s.filled_at
                                ORDER BY b.filled_at DESC LIMIT 1
                              ), 0)
                        THEN 1 ELSE 0 END) AS sell_win
            FROM trade_order s
            WHERE s.status='FILLED'
            GROUP BY s.trader_id
          ) o ON o.trader_id = a.id
          WHERE a.deleted = 0 AND a.template_id IS NOT NULL
          GROUP BY a.template_id
        ) agg ON agg.template_id = t.id
        WHERE t.id = #{templateId}
    """)
    Map<String, Object> selectMetrics(@Param("templateId") Long templateId);

    @Select("""
        SELECT t.id AS template_id,
               COALESCE(agg.instance_count, 0) AS instance_count,
               COALESCE(agg.avg_return_pct, 0) AS avg_return_pct,
               COALESCE(agg.total_trades, 0)   AS total_trades,
               COALESCE(agg.win_rate, 0)       AS win_rate
        FROM strategy_template t
        LEFT JOIN (
          SELECT a.template_id,
                 COUNT(*) AS instance_count,
                 AVG(CASE WHEN a.initial_balance > 0
                          THEN (a.total_profit * 100.0 / a.initial_balance)
                          ELSE 0 END) AS avg_return_pct,
                 COALESCE(SUM(o.filled_total), 0) AS total_trades,
                 CASE WHEN COALESCE(SUM(o.sell_total), 0) > 0
                      THEN COALESCE(SUM(o.sell_win), 0) * 100.0 / SUM(o.sell_total)
                      ELSE 0 END AS win_rate
          FROM ai_trader a
          LEFT JOIN (
            SELECT s.trader_id,
                   COUNT(*) AS filled_total,
                   SUM(CASE WHEN s.side='SELL' THEN 1 ELSE 0 END) AS sell_total,
                   SUM(CASE WHEN s.side='SELL' AND s.filled_price > IFNULL((
                                SELECT b.filled_price FROM trade_order b
                                WHERE b.trader_id = s.trader_id AND b.stock_code = s.stock_code
                                  AND b.side = 'BUY' AND b.status='FILLED' AND b.filled_at < s.filled_at
                                ORDER BY b.filled_at DESC LIMIT 1
                              ), 0)
                        THEN 1 ELSE 0 END) AS sell_win
            FROM trade_order s
            WHERE s.status='FILLED'
            GROUP BY s.trader_id
          ) o ON o.trader_id = a.id
          WHERE a.deleted = 0 AND a.template_id IS NOT NULL
          GROUP BY a.template_id
        ) agg ON agg.template_id = t.id
        WHERE t.is_official = 1
        ORDER BY t.sort_order, t.id
    """)
    List<Map<String, Object>> selectAllMetrics();
}
