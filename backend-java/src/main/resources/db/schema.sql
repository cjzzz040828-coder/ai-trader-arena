-- 用户表
CREATE TABLE IF NOT EXISTS user (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    username   VARCHAR(64) NOT NULL UNIQUE,
    password   VARCHAR(128) NOT NULL,
    nickname   VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- AI 交易员（每个用户可以有多个虚拟账户）
CREATE TABLE IF NOT EXISTS ai_trader (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    name            VARCHAR(64) NOT NULL,
    strategy_type   VARCHAR(64) DEFAULT 'MANUAL',
    initial_balance DECIMAL(18,2) DEFAULT 1000000,
    balance         DECIMAL(18,2) DEFAULT 1000000,
    frozen_balance  DECIMAL(18,2) DEFAULT 0,
    total_profit    DECIMAL(18,2) DEFAULT 0,
    enabled         INTEGER DEFAULT 1,
    deleted         INTEGER DEFAULT 0,
    ma_short        INTEGER DEFAULT 5,
    ma_long         INTEGER DEFAULT 20,
    llm_base_url    VARCHAR(256),
    llm_api_key     VARCHAR(256),
    llm_model       VARCHAR(64),
    llm_prompt      TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_trader_user ON ai_trader(user_id);
CREATE INDEX IF NOT EXISTS idx_trader_profit ON ai_trader(total_profit DESC);

-- 持仓
CREATE TABLE IF NOT EXISTS position (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    trader_id      INTEGER NOT NULL,
    stock_code     VARCHAR(16) NOT NULL,
    amount         INTEGER NOT NULL,
    frozen_amount  INTEGER NOT NULL DEFAULT 0,
    cost_price     DECIMAL(10,3) NOT NULL,
    current_price  DECIMAL(10,3),
    updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pos_trader ON position(trader_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_pos_trader_code ON position(trader_id, stock_code);

-- 订单
CREATE TABLE IF NOT EXISTS trade_order (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    trader_id     INTEGER NOT NULL,
    stock_code    VARCHAR(16) NOT NULL,
    side          VARCHAR(8) NOT NULL,
    price         DECIMAL(10,3) NOT NULL,
    amount        INTEGER NOT NULL,
    status        VARCHAR(16) DEFAULT 'PENDING',
    filled_price  DECIMAL(10,3),
    filled_at     TIMESTAMP,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_order_trader_status ON trade_order(trader_id, status);
CREATE INDEX IF NOT EXISTS idx_order_status ON trade_order(status);

-- 回测任务（每次提交回测产生一行；异步执行）
CREATE TABLE IF NOT EXISTS backtest_task (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id           INTEGER NOT NULL,
    trader_id         INTEGER NOT NULL,
    trader_name       VARCHAR(64),
    strategy_type     VARCHAR(32) NOT NULL,
    strategy_params   VARCHAR(256),
    start_date        VARCHAR(10) NOT NULL,
    end_date          VARCHAR(10) NOT NULL,
    initial_balance   DECIMAL(18,2) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    progress          INTEGER DEFAULT 0,
    error             TEXT,
    final_equity      DECIMAL(18,2),
    total_return_pct  DECIMAL(10,4),
    total_trades      INTEGER DEFAULT 0,
    max_drawdown_pct  DECIMAL(10,4),
    equity_curve_json TEXT,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at        TIMESTAMP,
    finished_at       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_bt_user_status ON backtest_task(user_id, status);
CREATE INDEX IF NOT EXISTS idx_bt_trader ON backtest_task(trader_id);

-- 回测成交明细
CREATE TABLE IF NOT EXISTS backtest_trade (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id       INTEGER NOT NULL,
    trade_date    VARCHAR(10) NOT NULL,
    stock_code    VARCHAR(16) NOT NULL,
    side          VARCHAR(8) NOT NULL,
    amount        INTEGER NOT NULL,
    price         DECIMAL(10,3) NOT NULL,
    balance_after DECIMAL(18,2),
    reason        VARCHAR(256)
);
CREATE INDEX IF NOT EXISTS idx_bt_trade_task ON backtest_trade(task_id);

-- LLM 决策实时活动日志（每次工具调用一行,started/final/cancelled/failed 也各一行）
CREATE TABLE IF NOT EXISTS llm_activity (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    trader_id    INTEGER NOT NULL,
    user_id      INTEGER NOT NULL,
    decision_id  INTEGER NOT NULL,
    seq          INTEGER NOT NULL,
    phase        VARCHAR(24) NOT NULL,
    round        INTEGER,
    tool_name    VARCHAR(64),
    tool_call_id VARCHAR(64),
    args_json    TEXT,
    result_json  TEXT,
    message      TEXT,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_llm_act_trader_time ON llm_activity(trader_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_llm_act_decision ON llm_activity(decision_id);
