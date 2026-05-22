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

    quote_cache_ttl_seconds: float = 1.0
    heartbeat_interval_seconds: int = 60
    reconnect_max_backoff_seconds: int = 60

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
