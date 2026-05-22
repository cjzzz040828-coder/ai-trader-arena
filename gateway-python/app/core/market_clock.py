from datetime import datetime, time, date
from functools import lru_cache

try:
    import chinese_calendar as cn_cal
    _HAS_CN_CAL = True
except Exception:
    _HAS_CN_CAL = False

MORNING_OPEN = time(9, 30)
MORNING_CLOSE = time(11, 30)
AFTERNOON_OPEN = time(13, 0)
AFTERNOON_CLOSE = time(15, 0)


@lru_cache(maxsize=512)
def _is_workday(d: date) -> bool:
    if _HAS_CN_CAL:
        try:
            return cn_cal.is_workday(d) and not cn_cal.is_holiday(d)
        except Exception:
            pass
    return d.weekday() < 5


def is_trading_now(now: datetime | None = None) -> bool:
    """是否在A股连续竞价时段内（含集合竞价后到收盘）。"""
    now = now or datetime.now()
    if not _is_workday(now.date()):
        return False
    t = now.time()
    return (MORNING_OPEN <= t <= MORNING_CLOSE) or (AFTERNOON_OPEN <= t <= AFTERNOON_CLOSE)


def market_status(now: datetime | None = None) -> str:
    """OPEN / LUNCH / CLOSED / WEEKEND_OR_HOLIDAY"""
    now = now or datetime.now()
    if not _is_workday(now.date()):
        return "WEEKEND_OR_HOLIDAY"
    t = now.time()
    if MORNING_OPEN <= t <= MORNING_CLOSE or AFTERNOON_OPEN <= t <= AFTERNOON_CLOSE:
        return "OPEN"
    if MORNING_CLOSE < t < AFTERNOON_OPEN:
        return "LUNCH"
    return "CLOSED"
