# StratForge · 策略熔炉

> 个人 MVP 级全栈项目。**多策略实时决策与回测平台**：聚合金融时序数据 → 多策略并行调度 → 沙盒环境虚拟撮合 → 专业回测与可解释性分析。

## 架构

```
┌──────────────┐     ┌──────────────────────┐     ┌─────────────────────┐
│ Vue3 前端    │ ──▶ │ Java 业务后端        │ ──▶ │ Python 数据网关     │
│ (Vite+Pinia) │     │ (Spring Boot)        │     │ (FastAPI)           │
└──────────────┘     │ SQLite               │ HTTP│ 行情数据源 SDK      │
                     │ 沙盒撮合 / 策略调度  │     │ 监控股票列表解析    │
                     └──────────────────────┘     └─────────────────────┘
                                                           │
                                                           ▼
                                                  公开行情数据源
```

| 模块 | 端口 | 路径 | 职责 |
|------|------|------|------|
| Python 数据网关 | 8000 | `gateway-python/` | 行情快照、日 K、监控列表、标的池 |
| Java 后端 | 8080 | `backend-java/` | 鉴权、虚拟账户、沙盒撮合、多策略调度、回测、模板市场 |
| Vue3 前端 | 5173 | `frontend-vue/` | Dashboard / MyTrader / Leaderboard / TraderManage / StrategyMarket / PoolManage / BacktestHistory / LlmActivity |

**沙盒撮合说明：** 平台**不接入任何真实下单通道**，纯沙盒环境。每个 AI Agent 在 SQLite 中独立持有虚拟账户（初始 ¥1,000,000），`OrderService.place()` 写入虚拟订单簿，`MatchEngine` 每 10s tick 按最新行情快照模拟成交，遵循 T+1 与涨跌停规则。

## 功能截图

### Dashboard 行情看板
分时/K 线图 + 五档盘口 + 逐笔成交 + 快速下单，左栏自选股/持仓按涨幅排序。

![Dashboard 行情看板](doc/dashboard.png)

### 我的 Trader
虚拟账户总览：持仓明细（成本/现价/盈亏）+ 订单流水。

![我的 Trader](doc/my-trader.png)

### LLM 实时驾驶舱
多 LLM Agent 实时决策监控，SSE 推送当前动作 + 总资产/收益，可一键禁用或查看详情。

![LLM 实时驾驶舱](doc/llm-monitor.png)

### 策略库
官方预置策略模板（MA / LLM / CTA），一键复制为自己的 Trader。

![策略库](doc/strategy-library.png)

### 全站排行榜
所有用户的 Trader 按总盈亏排序，本人 Trader 高亮，5 秒刷新。

![全站排行榜](doc/leaderboard.png)

### AI 分析助手
感知当前选中股票，多轮对话式盘面分析，Markdown 渲染输出。

![AI 分析助手](doc/ai-assistant.png)

## 启动顺序

### 0. 准备
- **Python 3.10+**、**JDK 17**、**Node 18+**、**Maven 3.6+** 已安装。
- **监控股票列表**：从公开行情终端导出 `.sel` 格式标的列表，放到 `doc/自选股.sel`（路径可在 `gateway-python/app/config.py` 的 `ths_sel_export_path` 改）。仅启动前做一次即可，运行时不依赖任何桌面客户端。如需按周/主题维护多组标的列表，在 `.env` 设 `THS_SEL_EXTRA_PATHS=doc/5.4.sel,doc/6.1.sel`（默认已包含 `doc/5.4.sel`），每个文件作为独立分组（文件名 stem 作组名，前端 Dashboard 左栏可切换）。
- 推荐安装 [cpolar](https://www.cpolar.com/)（内网穿透，云端部署 Java 时需要）。

### 1. 启 Python 数据网关
```cmd
cd gateway-python
copy .env.example .env
notepad .env              :: 修改 GATEWAY_TOKEN
run.bat
```
验证：
```
curl http://localhost:8000/health
curl "http://localhost:8000/quote/snapshot?codes=000001" -H "X-Gateway-Token: <你的token>"
```

### 2. 启 Java 后端
```cmd
cd backend-java
:: 首次运行：复制配置模板，填入 gateway token（与 Python 网关一致）和 JWT secret
copy src\main\resources\application.yml.example src\main\resources\application.yml
notepad src\main\resources\application.yml
mvn spring-boot:run
```
验证：
```
curl http://localhost:8080/api/quote/gateway-health
```
其它业务接口（`/api/quote/**`、`/api/auth/**`、`/api/trader/**` 等）需带 JWT。先 `POST /api/auth/register` 拿 token，再带 `Authorization: Bearer <token>` 访问。

### 3. 启前端
```cmd
cd frontend-vue
npm install
npm run dev
```
浏览器打开 http://localhost:5173 ，注册账号后会自动建一个"默认 Agent"虚拟账户。

### 一键启动
根目录的 `start-all.bat` 会依次启 Python → Java → 前端（间隔 8s/25s），`stop-all.bat` 全部关闭。

### 4. 内网穿透（云端部署 Java 时才用）
```
cpolar http 8000
```
拿到公网URL（如 `https://abc.r6.cpolar.cn`），填到 Java 的 `application.yml -> gateway.python.base-url`。

## 关键避坑点

- **网关 Token**：cpolar URL 是公网可达的，务必改 `GATEWAY_TOKEN`，否则会被爆刷。
- **数据源限频**：行情 SDK 客户端已是全局单例 + TTL 缓存（默认 1 秒），底层数据源对密集请求不友好。
- **非交易时段**：数据源会返回空，已自动走 fallback 缓存或返回 `market_status=CLOSED`。
- **`.sel` 标的列表**：完整度优于配置文件方式（后者部分分组加密）。换股票池时重新导出并 `GET /watchlist/reload`。支持多 `.sel` 并存为分组（主文件 + `THS_SEL_EXTRA_PATHS`），全集去重合并。
- **资金守恒不变量**：`balance + frozen_balance + Σ(amount × current_price) == 1_000_000 + total_profit`。改动账逻辑时务必保持等式成立。
- **bash 里跑不了 npm**：Claude/Git-bash 的 PATH 没有 node，前端启动用 cmd。

## 当前进度

### 阶段一·骨架
- [x] Python 数据网关：行情快照、日 K、监控列表、心跳重连、TTL 缓存、市场时段判断、Token 鉴权
- [x] Java 后端：SpringBoot + SQLite + MyBatis-Plus + 网关透传
- [x] Vue3 前端：Dashboard 行情自刷新

### 阶段二·沙盒交易 + AI 策略
- [x] 用户注册/登录（JWT + IP 维度限流）
- [x] 虚拟账户 + Java 端沙盒撮合（`OrderService` + `MatchEngine`，10s tick，按 traderId 取细粒度锁）
- [x] AI Agent 模型 — 多策略：MA 双均线 / LLM OpenAI 兼容 / INDICATOR 指标规则 / SCRIPT JS 脚本 / CTA 趋势
- [x] 全站排行榜（按 total_profit 排序，5s 刷新，本人 Agent 高亮）
- [x] 下单流水可视化、持仓刷新
- [x] 完整 K 线 + 分时图 + 五档盘口组件 + 逐笔成交

### 阶段三·研究与可解释
- [x] 回测系统 — MA T+1 open 撮合 + LLM 决策回放即时成交（`LlmReplayEngine`）
- [x] 卖出盈亏 / 标的名映射 / 回测历史页 / 弹窗"最近回测"列表
- [x] 专业回测报告（夏普/索提诺/Calmar/年化/胜率/盈亏比 + 沪深300 基准对比 + 月度收益热力图，独立报告页 `/backtest/:id`）
- [x] LLM Agent 实时活动监控 — 独立 `/llm-activity` 路由（驾驶舱 + 详情页），SSE 推送 + 中断按钮 + 历史持久化
- [x] LLM 决策落库 + 4 小时后验反思（DecisionMemory + ReflectionWorker，过去 30 天准确率喂回 prompt 自我校准）
- [x] 策略超市（官方模板库 → 一键派生 Agent，含 MA/LLM 模板）
- [x] 多 `.sel` 监控列表分组（按周/按主题维护多组标的，前端 Dashboard 左栏可切换）
- [x] 标的池 / 多池子分组（Agent 通过 poolName 绑定池子，调度按池子分组复用 MarketContext）
- [x] 策略调度优化（PREMARKET 集合竞价预热 + 开盘瞬间边沿补 tick + 间隔可配）
- [x] 日/夜双主题（默认白天，右上角切夜间，红涨绿跌独立于 Element Plus 语义色）
- [ ] LLM × MA 信号融合（把回测验证过的 MA 参数注入 LLM Agent prompt 作"专家信号"参考）
- [x] CTA 趋势策略（DUAL_MA / BREAKOUT 入场 + 固定/跟踪止损 + 反向出场；持仓最高价落库 `position.high_since_entry`，沙盒+回测同步维护；官方模板 2 个）
- [ ] 因子库（gateway 端动量/波动率/成交量因子计算 + Java `FactorService` 只读 API，喂 LLM prompt / CTA 融合）
</content>
</invoke>