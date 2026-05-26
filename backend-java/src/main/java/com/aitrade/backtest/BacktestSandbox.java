package com.aitrade.backtest;

import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回测内存账户。维护 balance / frozenBalance / 持仓 / pending 单 / T+1 标志。
 *
 * 撮合规则：所有 enqueue* 的单子在下一交易日 settleAtOpen() 时按当天 open 成交。
 *   - 限价校验：BUY 若 open > limitPrice 不成交（A 股限价单语义）
 *   - 涨跌停校验：BUY 若 open ≥ 涨停价 / SELL 若 open ≤ 跌停价，拒绝成交（开盘一字板，单子排队无法成交）
 *   - 停牌（次日无 open 数据）：撤单返还冻结
 *   - 交易成本：BUY 加 0.125% 成本（0.1% 滑点 + 0.025% 佣金）
 *                SELL 扣 0.175% 成本（0.1% 滑点 + 0.025% 佣金 + 0.05% 印花税）
 *
 * 涨跌停规则：
 *   - 沪深主板 / 中小板 (0/2/6/9 开头但非 300/688): ±10%
 *   - 创业板 (300 开头) / 科创板 (688 开头): ±20%
 *   - 北交所 (8/4 开头): ±30%
 *   - ST 票应在 BacktestEngine 入口处过滤，这里不再判断 ST 的 ±5%
 */
public class BacktestSandbox {

    /** 买入综合成本：滑点 0.1% + 佣金 0.025%。表示实际占用资金/成交价相对于 open 的溢价。 */
    private static final BigDecimal BUY_COST_RATE = new BigDecimal("0.00125");
    /** 卖出综合成本：滑点 0.1% + 佣金 0.025% + 印花税 0.05%。表示实际到账相对于 open 的折扣。 */
    private static final BigDecimal SELL_COST_RATE = new BigDecimal("0.00175");

    @Getter
    private BigDecimal balance;
    @Getter
    private BigDecimal frozenBalance = BigDecimal.ZERO;
    @Getter
    private final BigDecimal initialBalance;

    private final Map<String, Pos> positions = new HashMap<>();
    private List<Pending> pendings = new ArrayList<>();
    private final Set<String> todayBuys = new HashSet<>();
    private final Set<String> codesWithPending = new HashSet<>();

    public BacktestSandbox(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
        this.balance = initialBalance;
    }

    public boolean hasPending(String code) { return codesWithPending.contains(code); }
    public boolean boughtToday(String code) { return todayBuys.contains(code); }
    public Pos position(String code) { return positions.get(code); }
    public Map<String, Pos> positions() { return positions; }

    /** 挂买单。冻结资金。返回 true 表示挂单成功。 */
    public boolean enqueueBuy(String code, int amount, BigDecimal limitPrice) {
        BigDecimal need = limitPrice.multiply(BigDecimal.valueOf(amount));
        if (balance.compareTo(need) < 0) return false;
        balance = balance.subtract(need);
        frozenBalance = frozenBalance.add(need);
        pendings.add(new Pending(code, "BUY", amount, limitPrice));
        codesWithPending.add(code);
        return true;
    }

    /** 挂卖单。持仓数量不够直接拒绝（持仓不冻结，只靠 codesWithPending 防重复挂）。 */
    public boolean enqueueSell(String code, int amount, BigDecimal limitPrice) {
        Pos pos = positions.get(code);
        if (pos == null || pos.amount < amount) return false;
        pendings.add(new Pending(code, "SELL", amount, limitPrice));
        codesWithPending.add(code);
        return true;
    }

    /**
     * 即时成交（LLM 决策回放专用）。不入 pending 队列，直接落账。
     *   - BUY: balance 直接扣（含 0.125% 综合成本），positions 增持，code 进 todayBuys（A 股 T+1）
     *   - SELL: 持仓直接减，balance 直接加（扣 0.175% 综合成本）；若 boughtToday 拒绝；持仓不足拒绝
     * 跟 enqueueBuy/Sell + settleAtOpen 链路并存，但**不要混用**——LlmReplayEngine 整条主循环
     * 走即时成交，没有 pending 单。
     */
    public Fill executeImmediate(String code, String side, int amount, BigDecimal price) {
        if (code == null || side == null || price == null || price.signum() <= 0 || amount <= 0) return null;
        if ("BUY".equalsIgnoreCase(side)) {
            BigDecimal grossCost = price.multiply(BigDecimal.valueOf(amount));
            BigDecimal cost = grossCost.add(grossCost.multiply(BUY_COST_RATE)).setScale(2, RoundingMode.HALF_UP);
            if (balance.compareTo(cost) < 0) return null;
            balance = balance.subtract(cost);
            // 实际持仓成本价 = 含费总成本 / 股数，让浮亏从一开始就反映交易成本
            BigDecimal effectivePrice = cost.divide(BigDecimal.valueOf(amount), 3, RoundingMode.HALF_UP);
            Pos pos = positions.get(code);
            if (pos == null) {
                positions.put(code, new Pos(amount, effectivePrice, price));
            } else {
                BigDecimal oldVal = pos.costPrice.multiply(BigDecimal.valueOf(pos.amount));
                BigDecimal newVal = effectivePrice.multiply(BigDecimal.valueOf(amount));
                int newAmt = pos.amount + amount;
                pos.costPrice = oldVal.add(newVal).divide(BigDecimal.valueOf(newAmt), 3, RoundingMode.HALF_UP);
                pos.amount = newAmt;
                if (pos.highSinceEntry == null || price.compareTo(pos.highSinceEntry) > 0) {
                    pos.highSinceEntry = price;
                }
            }
            todayBuys.add(code);
            return new Fill(code, "BUY", amount, price, balance, null);
        }
        if ("SELL".equalsIgnoreCase(side)) {
            if (todayBuys.contains(code)) return null;
            Pos pos = positions.get(code);
            if (pos == null || pos.amount < amount) return null;
            BigDecimal costPriceAtSell = pos.costPrice;
            pos.amount -= amount;
            if (pos.amount == 0) positions.remove(code);
            BigDecimal gross = price.multiply(BigDecimal.valueOf(amount));
            BigDecimal income = gross.subtract(gross.multiply(SELL_COST_RATE)).setScale(2, RoundingMode.HALF_UP);
            balance = balance.add(income);
            return new Fill(code, "SELL", amount, price, balance, costPriceAtSell);
        }
        return null;
    }

    /** 不带 prev_close 的便捷重载（跳过涨跌停校验），用于测试和兼容旧调用。 */
    public List<Fill> settleAtOpen(Map<String, BigDecimal> openByCode) {
        return settleAtOpen(openByCode, null);
    }

    /**
     * 用 fillDate（次日）的 open 撮合所有 pending。返回当日成交记录。
     * prevCloseByCode：前一交易日 close，用于涨跌停板判定（若 null 跳过涨跌停校验）
     */
    public List<Fill> settleAtOpen(Map<String, BigDecimal> openByCode, Map<String, BigDecimal> prevCloseByCode) {
        List<Fill> fills = new ArrayList<>();
        for (Pending p : pendings) {
            BigDecimal open = openByCode.get(p.code);
            if (open == null || open.signum() <= 0) {
                if ("BUY".equals(p.side)) {
                    BigDecimal frozen = p.limitPrice.multiply(BigDecimal.valueOf(p.amount));
                    frozenBalance = frozenBalance.subtract(frozen);
                    balance = balance.add(frozen);
                }
                continue;
            }
            // 涨跌停板：开盘一字涨/跌停的单子排队基本无法成交，整笔撤单
            BigDecimal prevClose = prevCloseByCode == null ? null : prevCloseByCode.get(p.code);
            if (prevClose != null && prevClose.signum() > 0) {
                BigDecimal pctCap = limitPctOf(p.code);
                BigDecimal limitUp = prevClose.multiply(BigDecimal.ONE.add(pctCap)).setScale(2, RoundingMode.HALF_UP);
                BigDecimal limitDown = prevClose.multiply(BigDecimal.ONE.subtract(pctCap)).setScale(2, RoundingMode.HALF_UP);
                if ("BUY".equals(p.side) && open.compareTo(limitUp) >= 0) {
                    BigDecimal frozen = p.limitPrice.multiply(BigDecimal.valueOf(p.amount));
                    frozenBalance = frozenBalance.subtract(frozen);
                    balance = balance.add(frozen);
                    continue;
                }
                if ("SELL".equals(p.side) && open.compareTo(limitDown) <= 0) {
                    continue;
                }
            }
            if ("BUY".equals(p.side)) {
                BigDecimal frozen = p.limitPrice.multiply(BigDecimal.valueOf(p.amount));
                // 限价单语义：open > limitPrice 不成交，撤单返还冻结
                if (open.compareTo(p.limitPrice) > 0) {
                    frozenBalance = frozenBalance.subtract(frozen);
                    balance = balance.add(frozen);
                    continue;
                }
                frozenBalance = frozenBalance.subtract(frozen);
                BigDecimal grossCost = open.multiply(BigDecimal.valueOf(p.amount));
                BigDecimal actualCost = grossCost.add(grossCost.multiply(BUY_COST_RATE)).setScale(2, RoundingMode.HALF_UP);
                BigDecimal diff = frozen.subtract(actualCost);
                if (diff.signum() > 0) {
                    // 跳空低开（actualCost < frozen），多冻结的退回 balance；
                    // 若交易成本让 actualCost > frozen 也得补差额（极端跳空情况）
                    balance = balance.add(diff);
                } else if (diff.signum() < 0) {
                    // 冻结资金不够付实际成本（开盘价 + 费用 > 限价），从 balance 补差
                    balance = balance.add(diff);
                }
                // 实际持仓成本含 0.125% 综合成本，让浮亏从开仓就反映真实代价
                BigDecimal effectivePrice = actualCost.divide(BigDecimal.valueOf(p.amount), 3, RoundingMode.HALF_UP);
                Pos pos = positions.get(p.code);
                if (pos == null) {
                    positions.put(p.code, new Pos(p.amount, effectivePrice, open));
                } else {
                    BigDecimal oldVal = pos.costPrice.multiply(BigDecimal.valueOf(pos.amount));
                    BigDecimal newVal = effectivePrice.multiply(BigDecimal.valueOf(p.amount));
                    int newAmt = pos.amount + p.amount;
                    pos.costPrice = oldVal.add(newVal).divide(BigDecimal.valueOf(newAmt), 3, RoundingMode.HALF_UP);
                    pos.amount = newAmt;
                    if (pos.highSinceEntry == null || open.compareTo(pos.highSinceEntry) > 0) {
                        pos.highSinceEntry = open;
                    }
                }
                todayBuys.add(p.code);
                fills.add(new Fill(p.code, "BUY", p.amount, open, balance, null));
            } else {
                Pos pos = positions.get(p.code);
                if (pos == null || pos.amount < p.amount) continue;
                BigDecimal costPriceAtSell = pos.costPrice;
                pos.amount -= p.amount;
                if (pos.amount == 0) positions.remove(p.code);
                BigDecimal gross = open.multiply(BigDecimal.valueOf(p.amount));
                BigDecimal income = gross.subtract(gross.multiply(SELL_COST_RATE)).setScale(2, RoundingMode.HALF_UP);
                balance = balance.add(income);
                fills.add(new Fill(p.code, "SELL", p.amount, open, balance, costPriceAtSell));
            }
        }
        pendings = new ArrayList<>();
        codesWithPending.clear();
        return fills;
    }

    /**
     * 按代码段判断涨跌停百分比：
     *   - 300xxx (创业板) / 688xxx (科创板): 20%
     *   - 8xxxxx / 4xxxxx (北交所): 30%
     *   - 其它 (沪深主板 / 中小板 / 深市 00x / 002): 10%
     * 注意：ST 票主板 ±5%，但 ST 票应在 BacktestEngine 入口处过滤，这里不再单独判断。
     */
    private static BigDecimal limitPctOf(String code) {
        if (code == null || code.length() < 3) return new BigDecimal("0.10");
        if (code.startsWith("300") || code.startsWith("688")) return new BigDecimal("0.20");
        char c = code.charAt(0);
        if (c == '8' || c == '4') return new BigDecimal("0.30");
        return new BigDecimal("0.10");
    }

    /** 进入新交易日；清当日 T+1 标志（在 settleAtOpen 之后调）。 */
    public void clearTodayBuys() { todayBuys.clear(); }

    /** 用今日 high 更新所有持仓的 high_since_entry（CTA 跟踪止损需要）。BacktestEngine 每个交易日调一次。
     *  null/0 的 high 跳过。新建仓尚未填 high 的也兜底成 today high。 */
    public void markHighWithDayHigh(Map<String, BigDecimal> highByCode) {
        for (Map.Entry<String, Pos> e : positions.entrySet()) {
            BigDecimal h = highByCode.get(e.getKey());
            if (h == null || h.signum() <= 0) continue;
            Pos pos = e.getValue();
            if (pos.highSinceEntry == null || h.compareTo(pos.highSinceEntry) > 0) {
                pos.highSinceEntry = h;
            }
        }
    }

    /** 用收盘价估总资产。停牌（无价）的股票用成本价估。 */
    public BigDecimal equity(Map<String, BigDecimal> closeByCode) {
        BigDecimal posVal = BigDecimal.ZERO;
        for (Map.Entry<String, Pos> e : positions.entrySet()) {
            BigDecimal price = closeByCode.get(e.getKey());
            if (price == null) price = e.getValue().costPrice;
            posVal = posVal.add(price.multiply(BigDecimal.valueOf(e.getValue().amount)));
        }
        return balance.add(frozenBalance).add(posVal);
    }

    public static class Pos {
        public int amount;
        public BigDecimal costPrice;
        /** 持仓期间最高价。BUY 时 = 成交价，每日 markHighWithDayHigh 时取 max。 */
        public BigDecimal highSinceEntry;
        public Pos(int amount, BigDecimal costPrice, BigDecimal highSinceEntry) {
            this.amount = amount; this.costPrice = costPrice; this.highSinceEntry = highSinceEntry;
        }
    }

    private record Pending(String code, String side, int amount, BigDecimal limitPrice) {}

    public record Fill(String code, String side, int amount, BigDecimal price, BigDecimal balanceAfter,
                       BigDecimal costPriceAtSell) {}
}
