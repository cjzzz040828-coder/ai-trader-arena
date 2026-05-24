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
 * 撮合规则：所有 enqueue* 的单子在下一交易日 settleAtOpen() 时按当天 open 成交；
 * 如果次日该股无数据（停牌），撤单并解冻资金。
 *
 * 不支持的（简化）：手续费、印花税、滑点、限价撮合（一律按 open 成交）。
 */
public class BacktestSandbox {

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
     *   - BUY: balance 直接扣，positions 增持，code 进 todayBuys（A 股 T+1，当日买入次日才能卖）
     *   - SELL: 持仓直接减，balance 直接加；若 boughtToday 拒绝；持仓不足拒绝
     * 跟 enqueueBuy/Sell + settleAtOpen 链路并存，但**不要混用**——LlmReplayEngine 整条主循环
     * 走即时成交，没有 pending 单。
     */
    public Fill executeImmediate(String code, String side, int amount, BigDecimal price) {
        if (code == null || side == null || price == null || price.signum() <= 0 || amount <= 0) return null;
        if ("BUY".equalsIgnoreCase(side)) {
            BigDecimal cost = price.multiply(BigDecimal.valueOf(amount));
            if (balance.compareTo(cost) < 0) return null;
            balance = balance.subtract(cost);
            Pos pos = positions.get(code);
            if (pos == null) {
                positions.put(code, new Pos(amount, price));
            } else {
                BigDecimal oldVal = pos.costPrice.multiply(BigDecimal.valueOf(pos.amount));
                BigDecimal newVal = price.multiply(BigDecimal.valueOf(amount));
                int newAmt = pos.amount + amount;
                pos.costPrice = oldVal.add(newVal).divide(BigDecimal.valueOf(newAmt), 3, RoundingMode.HALF_UP);
                pos.amount = newAmt;
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
            BigDecimal income = price.multiply(BigDecimal.valueOf(amount));
            balance = balance.add(income);
            return new Fill(code, "SELL", amount, price, balance, costPriceAtSell);
        }
        return null;
    }

    /** 用 fillDate（次日）的 open 撮合所有 pending。返回当日成交记录。 */
    public List<Fill> settleAtOpen(Map<String, BigDecimal> openByCode) {
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
            if ("BUY".equals(p.side)) {
                BigDecimal frozen = p.limitPrice.multiply(BigDecimal.valueOf(p.amount));
                // 限价单语义：open > limitPrice 不成交，撤单返还冻结。
                // 之前会强行按 open 成交（甚至从 balance 里"补差"），违背 A 股限价规则、
                // 在跳空高开时会让回测结果系统性偏乐观。
                if (open.compareTo(p.limitPrice) > 0) {
                    frozenBalance = frozenBalance.subtract(frozen);
                    balance = balance.add(frozen);
                    continue;
                }
                frozenBalance = frozenBalance.subtract(frozen);
                BigDecimal actualCost = open.multiply(BigDecimal.valueOf(p.amount));
                BigDecimal diff = frozen.subtract(actualCost);
                if (diff.signum() > 0) {
                    // 跳空低开（open < limit），按 open 成交并把多冻结的部分退回 balance
                    balance = balance.add(diff);
                }
                Pos pos = positions.get(p.code);
                if (pos == null) {
                    positions.put(p.code, new Pos(p.amount, open));
                } else {
                    BigDecimal oldVal = pos.costPrice.multiply(BigDecimal.valueOf(pos.amount));
                    BigDecimal newVal = open.multiply(BigDecimal.valueOf(p.amount));
                    int newAmt = pos.amount + p.amount;
                    pos.costPrice = oldVal.add(newVal).divide(BigDecimal.valueOf(newAmt), 3, RoundingMode.HALF_UP);
                    pos.amount = newAmt;
                }
                todayBuys.add(p.code);
                fills.add(new Fill(p.code, "BUY", p.amount, open, balance, null));
            } else {
                Pos pos = positions.get(p.code);
                if (pos == null || pos.amount < p.amount) continue;
                BigDecimal costPriceAtSell = pos.costPrice;
                pos.amount -= p.amount;
                if (pos.amount == 0) positions.remove(p.code);
                BigDecimal income = open.multiply(BigDecimal.valueOf(p.amount));
                balance = balance.add(income);
                fills.add(new Fill(p.code, "SELL", p.amount, open, balance, costPriceAtSell));
            }
        }
        pendings = new ArrayList<>();
        codesWithPending.clear();
        return fills;
    }

    /** 进入新交易日；清当日 T+1 标志（在 settleAtOpen 之后调）。 */
    public void clearTodayBuys() { todayBuys.clear(); }

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
        public Pos(int amount, BigDecimal costPrice) {
            this.amount = amount; this.costPrice = costPrice;
        }
    }

    private record Pending(String code, String side, int amount, BigDecimal limitPrice) {}

    public record Fill(String code, String side, int amount, BigDecimal price, BigDecimal balanceAfter,
                       BigDecimal costPriceAtSell) {}
}
