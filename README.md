# aiTrade · 国内A股实时AI量化平台

> 个人MVP级全栈项目。零成本接入实盘行情 + 多 AI 交易员虚拟撮合 + 排行榜。

## 架构

```
┌──────────────┐     ┌──────────────────────┐     ┌─────────────────────┐
│ Vue3 前端    │ ──▶ │ Java 业务后端        │ ──▶ │ Python 本地网关     │
│ (Vite+Pinia) │     │ (Spring Boot)        │     │ (FastAPI)           │
└──────────────┘     │ SQLite               │ HTTP│ mootdx              │
                     │ 虚拟撮合 / 策略调度  │     │ 自选股 .sel 解析    │
                     └──────────────────────┘     └─────────────────────┘
                                                           │
                                                           ▼
                                                    通达信公网行情
```

| 模块 | 端口 | 路径 | 职责 |
|------|------|------|------|
| Python网关 | 8000 | `gateway-python/` | 行情快照、日K、自选股 |
| Java后端 | 8080 | `backend-java/` | 鉴权、虚拟账户、撮合、策略、回测 |
| Vue3前端 | 5173 | `frontend-vue/` | Dashboard / MyTrader / Leaderboard / TraderManage |

**下单链路说明：** Python 端的 easytrader（同花顺PC自动化）已退役，仅保留行情。每个 AI trader 在 SQLite 里独立持有虚拟账户（初始 ¥1,000,000），`OrderService.place()` 入虚拟订单簿，`MatchEngine` 每 10s tick 按最新行情撮合。

## 启动顺序

### 0. 准备
- **Python 3.10+**、**JDK 17**、**Node 18+**、**Maven 3.6+** 已安装。
- **自选股**：在同花顺 PC 客户端里"自选股 → 导出"得到 `.sel` 文件，放到 `doc/自选股.sel`（路径可在 `gateway-python/app/config.py` 的 `ths_sel_export_path` 改）。仅启动前做一次即可，运行时不需要同花顺常驻。
- 推荐安装 [cpolar](https://www.cpolar.com/)（内网穿透，云端部署 Java 时需要）。

### 1. 启 Python 网关
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
浏览器打开 http://localhost:5173 ，注册账号后会自动建一个"默认交易员"虚拟账户。

### 一键启动
根目录的 `start-all.bat` 会依次启 Python → Java → 前端（间隔 8s/25s），`stop-all.bat` 全部关闭。

### 4. 内网穿透（云端部署 Java 时才用）
```
cpolar http 8000
```
拿到公网URL（如 `https://abc.r6.cpolar.cn`），填到 Java 的 `application.yml -> gateway.python.base-url`。

## 关键避坑点

- **网关Token**：cpolar URL 是公网可达的，务必改 `GATEWAY_TOKEN`，否则会被爆刷。
- **不要高频请求 mootdx**：客户端已是全局单例 + TTL缓存（默认1秒），通达信不喜欢密集请求。
- **非交易时段**：通达信会返回空，已自动走 fallback 缓存或返回 `market_status=CLOSED`。
- **自选股 .sel 文件**：从同花顺导出，比读 stockblock.ini 完整（后者部分分组加密）。换股票池时重新导出并 `GET /watchlist/reload`。
- **资金守恒不变量**：`balance + frozen_balance + Σ(amount × current_price) == 1_000_000 + total_profit`。改动账逻辑时务必保持等式成立。
- **bash 里跑不了 npm**：Claude/Git-bash 的 PATH 没有 node，前端启动用 cmd。

## 当前进度

### 阶段一·骨架
- [x] Python网关：行情快照、日K、自选股、心跳重连、TTL缓存、市场时段判断、Token鉴权
- [x] Java后端：SpringBoot + SQLite + MyBatis-Plus + 网关透传
- [x] Vue3前端：Dashboard 行情自刷新

### 阶段二·虚拟交易 + AI 策略
- [x] 用户注册/登录（JWT）
- [x] 虚拟账户 + Java 端撮合（`OrderService` + `MatchEngine`，10s tick）
- [x] AI 交易员模型（多策略：MA 双均线 / LLM OpenAI 兼容）+ 全站排行榜
- [x] 下单流水可视化、持仓刷新
- [x] 完整 K 线 + 分时图 + 五档盘口组件

### 阶段三·研究与可解释
- [x] 回测系统（MA 策略，T+1 open 撮合，日 K 切片）
- [x] LLM trader 实时活动监控面板（SSE 推送决策过程 + 中断按钮 + 历史持久化）
- [ ] LLM 策略回测（成本考量未做，`ToolBox` 抽象接口已预留）
- [ ] 更丰富的回测指标（夏普/年化/胜率）
