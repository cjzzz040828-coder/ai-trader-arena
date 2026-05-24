package com.aitrade.trade.strategy.script;

import com.aitrade.trade.strategy.indicator.Indicators;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 暴露给 JavaScript 脚本的内建函数 / 工具对象。
 *
 * 沙箱原则：本类的全部 public 方法都只做纯计算或写日志，不做 IO / DB / 网络 / 反射。
 * 任何对外暴露的能力都要写在这里，绝不在 Bindings 里直接暴露 Java 类。
 *
 * 返回值约定：多列指标（MACD / BOLL / KDJ）返回 {@link Map}，因为 Nashorn 对 Java record 的
 * accessor（如 record Macd 的 macd() 方法）不会自动映射成 JS 属性，导致脚本里 m.macd 拿到方法引用而非数组。
 * 用 Map 后脚本可以直接 m.macd / m["macd"] 拿到 double[]。
 */
@Slf4j
public final class ScriptApi {

    private final String tag;

    public ScriptApi(String tag) {
        this.tag = tag;
    }

    // ---------------- 指标计算（复用 Indicators） ----------------

    public double[] rsi(double[] closes, int period) {
        return Indicators.rsi(closes, period);
    }

    public Map<String, double[]> macd(double[] closes, int fast, int slow, int signal) {
        Indicators.Macd m = Indicators.macd(closes, fast, slow, signal);
        Map<String, double[]> r = new LinkedHashMap<>();
        r.put("macd", m.macd());
        r.put("signal", m.signal());
        r.put("hist", m.hist());
        return r;
    }

    public Map<String, double[]> boll(double[] closes, int period, double k) {
        Indicators.Boll b = Indicators.boll(closes, period, k);
        Map<String, double[]> r = new LinkedHashMap<>();
        r.put("upper", b.upper());
        r.put("middle", b.middle());
        r.put("lower", b.lower());
        return r;
    }

    public Map<String, double[]> kdj(double[] highs, double[] lows, double[] closes, int n, int kPeriod, int dPeriod) {
        Indicators.Kdj kdj = Indicators.kdj(highs, lows, closes, n, kPeriod, dPeriod);
        Map<String, double[]> r = new LinkedHashMap<>();
        r.put("k", kdj.k());
        r.put("d", kdj.d());
        r.put("j", kdj.j());
        return r;
    }

    public double[] ma(double[] arr, int n) {
        if (arr == null || n <= 0) return new double[0];
        double[] out = new double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            if (i + 1 < n) { out[i] = Double.NaN; continue; }
            double sum = 0;
            for (int j = i - n + 1; j <= i; j++) sum += arr[j];
            out[i] = sum / n;
        }
        return out;
    }

    public double[] ema(double[] arr, int n) {
        if (arr == null || n <= 0 || arr.length < n) return naNs(arr == null ? 0 : arr.length);
        double[] out = new double[arr.length];
        for (int i = 0; i < n - 1; i++) out[i] = Double.NaN;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += arr[i];
        double prev = sum / n;
        out[n - 1] = prev;
        double alpha = 2.0 / (n + 1);
        for (int i = n; i < arr.length; i++) {
            prev = prev + alpha * (arr[i] - prev);
            out[i] = prev;
        }
        return out;
    }

    // ---------------- 序列工具 ----------------

    public double last(double[] arr) {
        if (arr == null || arr.length == 0) return Double.NaN;
        return arr[arr.length - 1];
    }

    public double prev(double[] arr) {
        if (arr == null || arr.length < 2) return Double.NaN;
        return arr[arr.length - 2];
    }

    /** 今日 a > b 且 昨日 a <= b → 上穿（金叉）。a/b 都是 double[] 序列。 */
    public boolean crossUp(double[] a, double[] b) {
        if (a == null || b == null || a.length < 2 || b.length < 2) return false;
        double ai = a[a.length - 1], aj = a[a.length - 2];
        double bi = b[b.length - 1], bj = b[b.length - 2];
        if (Double.isNaN(ai) || Double.isNaN(aj) || Double.isNaN(bi) || Double.isNaN(bj)) return false;
        return aj <= bj && ai > bi;
    }

    public boolean crossDown(double[] a, double[] b) {
        if (a == null || b == null || a.length < 2 || b.length < 2) return false;
        double ai = a[a.length - 1], aj = a[a.length - 2];
        double bi = b[b.length - 1], bj = b[b.length - 2];
        if (Double.isNaN(ai) || Double.isNaN(aj) || Double.isNaN(bi) || Double.isNaN(bj)) return false;
        return aj >= bj && ai < bi;
    }

    /** 给脚本写日志。tag 会带上 trader/test 信息便于追踪。 */
    public void log(Object msg) {
        log.info("[script:{}] {}", tag, msg);
    }

    private static double[] naNs(int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = Double.NaN;
        return out;
    }
}
