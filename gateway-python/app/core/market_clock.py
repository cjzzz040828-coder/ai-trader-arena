from datetime import datetime, time, date
from functools import lru_cache

try:
    import chinese_calendar as cn_cal
    _HAS_CN_CAL = True
except Exception:
    _HAS_CN_CAL = False

MORNING_PREMARKET = time(9, 15)
MORNING_OPEN = time(9, 30)
MORNING_CLOSE = time(11, 30)
AFTERNOON_PREMARKET = time(12, 57)
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
    """是否在A股连续竞价时段内（不含集合竞价 PREMARKET）。"""
    now = now or datetime.now()
    if not _is_workday(now.date()):
        return False
    t = now.time()
    return (MORNING_OPEN <= t <= MORNING_CLOSE) or (AFTERNOON_OPEN <= t <= AFTERNOON_CLOSE)


def market_status(now: datetime | None = None) -> str:
    """OPEN / PREMARKET / LUNCH / CLOSED / WEEKEND_OR_HOLIDAY

    PREMARKET 覆盖 9:15-9:30（上午集合竞价）与 12:57-13:00（下午开盘前 3 分钟）。
    虚拟撮合系统在 PREMARKET 时允许策略产生决策与 PENDING 单，但撮合引擎不撮合，
    单子会在 9:30 / 13:00 切到 OPEN 后由 MatchEngine 撮合。
    """
    now = now or datetime.now()
    if not _is_workday(now.date()):
        return "WEEKEND_OR_HOLIDAY"
    t = now.time()
    if MORNING_OPEN <= t <= MORNING_CLOSE or AFTERNOON_OPEN <= t <= AFTERNOON_CLOSE:
        return "OPEN"
    if MORNING_PREMARKET <= t < MORNING_OPEN or AFTERNOON_PREMARKET <= t < AFTERNOON_OPEN:
        return "PREMARKET"
    if MORNING_CLOSE < t < AFTERNOON_PREMARKET:
        return "LUNCH"
    return "CLOSED"
