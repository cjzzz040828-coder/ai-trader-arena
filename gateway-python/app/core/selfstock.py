"""
读取同花顺PC客户端的自选股。

数据源优先级：
1. ths_sel_export_path 指向的 .sel 文件（用户从 THS 里"自选股→导出"得到，覆盖全分组）
2. mx_<user>/stockblock.ini    [BLOCK_STOCK_CONTEXT] 段（仅完整导出当前激活分组）
3. mx_<user>/custom_block/330  (stockblock.ini 同步出来的副本)
4. mx_<user>/SelfStockInfo.json (最近添加索引，只有 ~40 只)

.sel 格式：2 字节头 + N×8 字节记录，每条 = [07, market_byte, ASCII6]
  market_byte: 0x11=沪市, 0x21=深市

stockblock.ini 格式：
[BLOCK_STOCK_CONTEXT]
14A=33:000036,33:000586,17:600076,...
其中 33=深市, 17=沪市，6位股票代码
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Optional

from loguru import logger

from app.config import settings
from app.core.mootdx_client import STOCK_NAMES

_cache: list[dict] | None = None
_cache_mtime: float = 0
_cache_groups: dict[str, list[str]] = {}

CODE_PATTERN = re.compile(r'\b(33|17):(\d{6})\b')


def _resolve_ths_user_dir() -> Optional[Path]:
    """找到 mx_<user_id> 目录。"""
    p_json = Path(settings.ths_selfstock_json)
    if p_json.exists():
        return p_json.parent

    ths_root = Path(settings.ths_exe_path).parent
    if not ths_root.exists():
        return None

    candidates = []
    for sub in ths_root.glob("mx_*"):
        if sub.is_dir() and (sub / "stockblock.ini").exists():
            candidates.append(sub)
    if not candidates:
        for sub in ths_root.glob("thsguest_*"):
            if sub.is_dir() and (sub / "stockblock.ini").exists():
                candidates.append(sub)
    if not candidates:
        return None
    return max(candidates, key=lambda x: x.stat().st_mtime)


def _parse_sel_file(path: Path) -> list[str]:
    """解析 THS 导出的 .sel 二进制自选股文件，返回去重后的代码列表。"""
    data = path.read_bytes()
    if len(data) < 2 or (len(data) - 2) % 8 != 0:
        logger.warning(f"[selfstock] .sel size suspicious: {len(data)}")
    codes: list[str] = []
    seen: set[str] = set()
    i = 2  # 跳过 2 字节头
    while i + 8 <= len(data):
        rec = data[i:i + 8]
        try:
            code = rec[2:8].decode("ascii")
        except UnicodeDecodeError:
            i += 8
            continue
        if code.isdigit() and code not in seen:
            seen.add(code)
            codes.append(code)
        i += 8
    return codes


def _parse_stockblock_ini(path: Path) -> tuple[list[str], dict[str, list[str]]]:
    """返回 (所有去重的代码列表, 分组名->代码列表)。"""
    with open(path, encoding="gbk", errors="replace") as f:
        text = f.read()

    groups: dict[str, list[str]] = {}
    in_context = False
    for line in text.splitlines():
        line = line.strip()
        if line == "[BLOCK_STOCK_CONTEXT]":
            in_context = True
            continue
        if line.startswith("[") and line.endswith("]"):
            in_context = False
            continue
        if not in_context or "=" not in line:
            continue
        key, _, value = line.partition("=")
        codes = [m[1] for m in CODE_PATTERN.findall(value)]
        if codes:
            groups[key.strip()] = codes

    # 去重 + 保持顺序
    seen = set()
    all_codes: list[str] = []
    for codes in groups.values():
        for c in codes:
            if c not in seen:
                seen.add(c)
                all_codes.append(c)
    return all_codes, groups


def _parse_selfstock_json(path: Path) -> list[dict]:
    """fallback：读取 SelfStockInfo.json。"""
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    result = []
    for item in raw:
        code = item.get("C", "")
        if not code:
            continue
        result.append({
            "code": code,
            "market": "SH" if item.get("M") == "17" else "SZ",
            "added_price": float(item.get("P", 0) or 0),
            "added_date": item.get("T", ""),
        })
    return result


def load_selfstock(force: bool = False) -> list[dict]:
    """主入口：返回完整自选股列表 [{code, name, market, added_price, added_date}, ...]"""
    global _cache, _cache_mtime, _cache_groups

    sel_path = Path(settings.ths_sel_export_path)
    user_dir = _resolve_ths_user_dir()

    stockblock_path = (user_dir / "stockblock.ini") if user_dir else None
    selfstock_path = (user_dir / "SelfStockInfo.json") if user_dir else None

    mtimes = []
    if sel_path.exists():
        mtimes.append(sel_path.stat().st_mtime)
    if stockblock_path and stockblock_path.exists():
        mtimes.append(stockblock_path.stat().st_mtime)
    if selfstock_path and selfstock_path.exists():
        mtimes.append(selfstock_path.stat().st_mtime)
    if not mtimes:
        logger.warning("[selfstock] no source files found")
        return []
    cur_mtime = max(mtimes)

    if not force and _cache is not None and cur_mtime == _cache_mtime:
        return _cache

    # 读 SelfStockInfo.json 拿额外元数据（加入价/日期）
    extra: dict[str, dict] = {}
    if selfstock_path and selfstock_path.exists():
        try:
            for item in _parse_selfstock_json(selfstock_path):
                extra[item["code"]] = item
        except Exception as e:
            logger.warning(f"[selfstock] read SelfStockInfo.json failed: {e}")

    all_codes: list[str] = []
    groups: dict[str, list[str]] = {}

    # 数据源 1：.sel 导出文件（覆盖全分组）
    if sel_path.exists():
        try:
            all_codes = _parse_sel_file(sel_path)
            groups = {"SEL_EXPORT": all_codes}
            logger.info(f"[selfstock] .sel export: {len(all_codes)} stocks ({sel_path})")
        except Exception as e:
            logger.warning(f"[selfstock] read .sel failed: {e}")

    # 数据源 2：stockblock.ini
    if not all_codes and stockblock_path and stockblock_path.exists():
        try:
            all_codes, groups = _parse_stockblock_ini(stockblock_path)
            logger.info(f"[selfstock] stockblock.ini: {len(all_codes)} unique stocks across {len(groups)} groups")
        except Exception as e:
            logger.warning(f"[selfstock] read stockblock.ini failed: {e}")

    # 兜底：用 SelfStockInfo.json 的代码
    if not all_codes and extra:
        all_codes = list(extra.keys())
        logger.info(f"[selfstock] fallback to SelfStockInfo.json: {len(all_codes)} stocks")

    result = []
    for code in all_codes:
        ex = extra.get(code, {})
        result.append({
            "code": code,
            "name": STOCK_NAMES.get(code, code),
            "market": ex.get("market") or ("SH" if code.startswith(("5", "6", "9")) else "SZ"),
            "added_price": ex.get("added_price", 0),
            "added_date": ex.get("added_date", ""),
        })

    _cache = result
    _cache_mtime = cur_mtime
    _cache_groups = groups
    return result


def list_groups() -> dict[str, list[str]]:
    if not _cache_groups:
        load_selfstock()
    return _cache_groups
