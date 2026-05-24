"""
池子定义注册中心：管理 data/pool_definitions.json。

每个池子是一份独立的筛选规则（板块、价格、市值、是否排除 ST 等），可以并存多个，每个 trader 可选定一个。

文件结构（data/pool_definitions.json）：
[
  {
    "name": "default",          # 唯一英文 slug，调度引用 ID
    "displayName": "主板打板池",  # UI 显示用
    "rules": {
        "markets": ["MAIN_SH", "MAIN_SZ"],
        "exclude_st": true,
        "exclude_delisting": true,
        "min_price": 2,
        "max_price": 50,
        "min_market_cap": 2000000000,
        "max_market_cap": 50000000000
    },
    "autoRefresh": true,        # 周五 cron 是否包含此池
    "createdAt": "2026-05-24T15:00:00"
  }
]

向后兼容：首次启动若 pool_definitions.json 不存在，用 settings.pool_* 配置自动建一个 "default" 池。
"""
from __future__ import annotations

import json
import re
import threading
from datetime import datetime
from pathlib import Path
from typing import Any

from loguru import logger

from app.config import settings

DEFAULT_POOL_NAME = "default"
_NAME_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")
_lock = threading.RLock()


def _registry_path() -> Path:
    return Path(settings.pool_file_path).parent / "pool_definitions.json"


def _default_rules_from_settings() -> dict:
    """用 settings.pool_* 拼一份默认规则，仅用于首次启动时创建 default 池。"""
    return {
        "markets": sorted(settings.pool_markets_set),
        "exclude_st": settings.pool_exclude_st,
        "exclude_delisting": settings.pool_exclude_delisting,
        "min_price": float(settings.pool_min_price),
        "max_price": float(settings.pool_max_price),
        "min_market_cap": float(settings.pool_min_market_cap),
        "max_market_cap": float(settings.pool_max_market_cap),
    }


def _read_file() -> list[dict]:
    p = _registry_path()
    if not p.exists():
        return []
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except Exception as e:
        logger.warning(f"[pool_registry] read failed: {e}")
        return []


def _write_file(pools: list[dict]) -> None:
    p = _registry_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(p.suffix + ".tmp")
    tmp.write_text(json.dumps(pools, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(p)


def _validate_name(name: str) -> None:
    if not name or not _NAME_PATTERN.match(name):
        raise ValueError("池子 name 必须是 1-32 位小写字母/数字/下划线/横线，首位字母数字")


def _validate_rules(rules: Any) -> dict:
    if not isinstance(rules, dict):
        raise ValueError("rules 必须是 object")
    out = {
        "markets": list(rules.get("markets") or []),
        "exclude_st": bool(rules.get("exclude_st", True)),
        "exclude_delisting": bool(rules.get("exclude_delisting", True)),
        "min_price": float(rules.get("min_price", 0)),
        "max_price": float(rules.get("max_price", 0)),
        "min_market_cap": float(rules.get("min_market_cap", 0)),
        "max_market_cap": float(rules.get("max_market_cap", 0)),
    }
    valid_markets = {"MAIN_SH", "MAIN_SZ", "SME", "GEM", "STAR"}
    out["markets"] = [m.upper() for m in out["markets"] if isinstance(m, str) and m.upper() in valid_markets]
    if not out["markets"]:
        raise ValueError("markets 不能为空，至少选一个板块")
    if out["min_price"] < 0 or out["max_price"] <= out["min_price"]:
        raise ValueError("min_price/max_price 不合法（要求 0 ≤ min < max）")
    if out["min_market_cap"] < 0 or out["max_market_cap"] <= out["min_market_cap"]:
        raise ValueError("min_market_cap/max_market_cap 不合法")
    return out


def _ensure_default() -> None:
    """启动时若注册表为空，用 settings 默认值初始化一个 default 池。"""
    with _lock:
        pools = _read_file()
        if any(p.get("name") == DEFAULT_POOL_NAME for p in pools):
            return
        default_pool = {
            "name": DEFAULT_POOL_NAME,
            "displayName": "主板打板池",
            "rules": _default_rules_from_settings(),
            "autoRefresh": True,
            "createdAt": datetime.now().isoformat(timespec="seconds"),
        }
        pools.insert(0, default_pool)
        _write_file(pools)
        logger.info(f"[pool_registry] initialized default pool with rules={default_pool['rules']}")


def list_pools() -> list[dict]:
    _ensure_default()
    with _lock:
        return _read_file()


def get_pool(name: str) -> dict | None:
    for p in list_pools():
        if p.get("name") == name:
            return p
    return None


def create_pool(payload: dict) -> dict:
    name = (payload.get("name") or "").strip().lower()
    _validate_name(name)
    rules = _validate_rules(payload.get("rules") or {})
    display = (payload.get("displayName") or name).strip()
    auto_refresh = bool(payload.get("autoRefresh", True))
    with _lock:
        pools = _read_file()
        if any(p.get("name") == name for p in pools):
            raise ValueError(f"池子 '{name}' 已存在")
        new_pool = {
            "name": name,
            "displayName": display,
            "rules": rules,
            "autoRefresh": auto_refresh,
            "createdAt": datetime.now().isoformat(timespec="seconds"),
        }
        pools.append(new_pool)
        _write_file(pools)
        logger.info(f"[pool_registry] created pool {name}")
        return new_pool


def update_pool(name: str, payload: dict) -> dict:
    with _lock:
        pools = _read_file()
        idx = next((i for i, p in enumerate(pools) if p.get("name") == name), -1)
        if idx < 0:
            raise ValueError(f"池子 '{name}' 不存在")
        existing = pools[idx]
        if "rules" in payload:
            existing["rules"] = _validate_rules(payload["rules"])
        if "displayName" in payload:
            existing["displayName"] = (payload["displayName"] or name).strip()
        if "autoRefresh" in payload:
            existing["autoRefresh"] = bool(payload["autoRefresh"])
        pools[idx] = existing
        _write_file(pools)
        logger.info(f"[pool_registry] updated pool {name}")
        return existing


def delete_pool(name: str) -> None:
    if name == DEFAULT_POOL_NAME:
        raise ValueError("不能删除 default 池")
    with _lock:
        pools = _read_file()
        new_pools = [p for p in pools if p.get("name") != name]
        if len(new_pools) == len(pools):
            raise ValueError(f"池子 '{name}' 不存在")
        _write_file(new_pools)
        # 同时清理该池子的快照和历史目录
        snapshot = Path(settings.pool_file_path).parent / "pools" / f"{name}.json"
        if snapshot.exists():
            snapshot.unlink()
        hist = Path(settings.pool_file_path).parent / "pool_history" / name
        if hist.exists():
            import shutil
            shutil.rmtree(hist, ignore_errors=True)
        logger.info(f"[pool_registry] deleted pool {name}")


def auto_refresh_names() -> list[str]:
    return [p["name"] for p in list_pools() if p.get("autoRefresh", True)]
