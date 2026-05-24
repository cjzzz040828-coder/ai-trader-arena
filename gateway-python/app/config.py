from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """全局配置。可通过同目录下 .env 文件覆盖默认值。"""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_name: str = "aiTrade Gateway"
    host: str = "0.0.0.0"
    port: int = 8000

    gateway_token: str = "change-me-in-prod-please"

    ths_exe_path: str = r"C:\同花顺\xiadan.exe"
    ths_account: str = ""
    ths_password: str = ""

    # 同花顺自选股JSON的本地路径（多账户时取最新修改的）。默认从 THS_SELFSTOCK_JSON 环境变量读
    # 形如 C:\同花顺\同花顺\mx_<账户哈希>\SelfStockInfo.json，在 .env 里覆盖即可
    ths_selfstock_json: str = ""

    # 同花顺导出的 .sel 自选股二进制文件（在 THS 里"自选股 → 导出"得到）。优先级高于
    # 直接读 stockblock.ini，因为 .sel 文件能完整导出所有分组（stockblock.ini 里其它分组被加密）
    ths_sel_export_path: str = str(Path(__file__).resolve().parent.parent.parent / "doc" / "自选股.sel")

    # 额外的 .sel 文件路径列表（逗号分隔）。每个文件作为独立分组（用文件名 stem 作组名），
    # 所有文件的代码合并去重后形成全集。用于按周 / 按主题维护多组选股。
    # 例如：THS_SEL_EXTRA_PATHS=doc/5.4.sel,doc/6.1.sel
    ths_sel_extra_paths: str = str(Path(__file__).resolve().parent.parent.parent / "doc" / "5.4.sel")

    @property
    def sel_extra_paths_list(self) -> list[Path]:
        raw = (self.ths_sel_extra_paths or "").strip()
        if not raw:
            return []
        return [Path(p.strip()) for p in raw.split(",") if p.strip()]

    # ---------------- Watchlist 数据源模式 ----------------
    # sel    : 从同花顺导出的 .sel 文件解析（默认，约 500 只）
    # filter : 走 mootdx 全市场名表 + 代码段/名称规则筛选（适合扫板等需要更大池子的策略）
    # pool   : 走 dynamic_pool.json（每周五收市后按市值+价格+板块规则筛出来的"打板候选池"）
    watchlist_mode: str = "sel"

    # filter 模式下保留的板块代码段。逗号分隔，可选：
    #   MAIN_SH = 沪市主板 (600/601/603/605)
    #   MAIN_SZ = 深市主板 (000)
    #   SME     = 中小板 (002)
    #   GEM     = 创业板 (300)
    #   STAR    = 科创板 (688)
    filter_markets: str = "MAIN_SH,MAIN_SZ,SME,GEM,STAR"

    # 排除名称含 ST / *ST 的退市风险股
    filter_exclude_st: bool = True

    # 排除名称含 "退" 的退市股
    filter_exclude_delisting: bool = True

    # filter 模式最终保留的最大只数（按 code 升序截断）。默认 1500 兼顾覆盖度与性能
    filter_max_count: int = 1500

    @property
    def filter_markets_set(self) -> set[str]:
        raw = (self.filter_markets or "").strip().upper()
        if not raw:
            return set()
        return {s.strip() for s in raw.split(",") if s.strip()}

    # ---------------- 动态打板候选池（pool 模式） ----------------
    # 池子规则：主板 + 非 ST + 流通市值 ∈ [pool_min_market_cap, pool_max_market_cap]
    #                + 股价 ∈ [pool_min_price, pool_max_price]
    # 每周五 15:30（A 股收盘后）自动重建一次，结果落盘 pool_file_path
    pool_file_path: str = str(Path(__file__).resolve().parent.parent / "data" / "dynamic_pool.json")
    # 仅保留以下板块；默认主板（沪深主板），不含创业板/科创板/中小板
    pool_markets: str = "MAIN_SH,MAIN_SZ"
    pool_exclude_st: bool = True
    pool_exclude_delisting: bool = True
    # 流通市值区间，元为单位。20 亿 = 2_000_000_000；500 亿 = 50_000_000_000
    pool_min_market_cap: float = 2_000_000_000.0
    pool_max_market_cap: float = 50_000_000_000.0
    # 股价区间
    pool_min_price: float = 2.0
    pool_max_price: float = 50.0
    # 自动重建 cron（day_of_week=fri, hour=15, minute=30）— A 股周五 15:00 收盘，15:30 数据已稳
    pool_cron_day_of_week: str = "fri"
    pool_cron_hour: int = 15
    pool_cron_minute: int = 30
    # 启动时若 pool 文件不存在或更新时间超过 N 天，自动跑一次（避免长时间停机后池子空）
    pool_max_age_days: int = 8
    # 历史快照保留几份（每次 build 归档到 data/pool_history/{name}/{date}.json，超过删旧的）
    # 默认 60 ≈ 14 个月（每周一份），覆盖最大回测窗口（BacktestService.MAX_LOOKBACK_DAYS=400 天）
    # 还有冗余，避免严格池回测时早期快照被 prune
    pool_history_keep: int = 60

    @property
    def pool_markets_set(self) -> set[str]:
        raw = (self.pool_markets or "").strip().upper()
        if not raw:
            return set()
        return {s.strip() for s in raw.split(",") if s.strip()}

    quote_cache_ttl_seconds: float = 1.0
    heartbeat_interval_seconds: int = 60
    reconnect_max_backoff_seconds: int = 60

    # CORS 允许的 origin。默认仅本机 Java 后端 + 前端 dev 端口；
    # 公网部署（cpolar 等）请在 .env 里逗号分隔追加，例如：
    # GATEWAY_CORS_ORIGINS=http://localhost:5173,http://localhost:8080,https://abc.r6.cpolar.cn
    # 设为 "*" 可放开所有 origin（不推荐，仅本地开发）
    gateway_cors_origins: str = "http://localhost:5173,http://localhost:8080,http://127.0.0.1:5173,http://127.0.0.1:8080"

    @property
    def cors_origins_list(self) -> list[str]:
        raw = (self.gateway_cors_origins or "").strip()
        if raw == "*":
            return ["*"]
        return [o.strip() for o in raw.split(",") if o.strip()]

    # 通达信服务器直连IP（避免 bestip=True 每次启动探测 60-90s）
    # 启动慢时可以重新跑 scripts/test_bestip.py 挑最快的换上
    tdx_servers: list[tuple[str, int]] = [
        ("119.97.185.59", 7709),
        ("110.41.147.114", 7709),
        ("123.60.70.228", 7709),
        ("121.36.225.169", 7709),
        ("123.60.73.44", 7709),
    ]

    log_dir: Path = Path(__file__).resolve().parent.parent / "logs"


settings = Settings()
settings.log_dir.mkdir(parents=True, exist_ok=True)
