package com.aitrade.trade.strategy.indicator;

/**
 * 经典技术指标的纯函数计算。所有方法接收 double[]（升序，最后一个是最新），返回长度相同的 double[] 序列，
 * 长度不足时序列前部填 Double.NaN。Executor 用最近两点（i, i-1）判断阈值/交叉。
 */
public final class Indicators {

    private Indicators() {}

    /** Wilder RSI。period 通常 6/12/14。 */
    public static double[] rsi(double[] closes, int period) {
        int n = closes.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = Double.NaN;
        if (n <= period || period <= 0) return out;

        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double diff = closes[i] - closes[i - 1];
            if (diff >= 0) gain += diff;
            else loss -= diff;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        out[period] = rsiValue(avgGain, avgLoss);
        for (int i = period + 1; i < n; i++) {
            double diff = closes[i] - closes[i - 1];
            double g = diff > 0 ? diff : 0;
            double l = diff < 0 ? -diff : 0;
            avgGain = (avgGain * (period - 1) + g) / period;
            avgLoss = (avgLoss * (period - 1) + l) / period;
            out[i] = rsiValue(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiValue(double avgGain, double avgLoss) {
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - 100 / (1 + rs);
    }

    /** MACD：dif/dea/hist 三条线。默认 12/26/9。 */
    public static Macd macd(double[] closes, int fast, int slow, int signal) {
        double[] emaFast = ema(closes, fast);
        double[] emaSlow = ema(closes, slow);
        int n = closes.length;
        double[] dif = new double[n];
        for (int i = 0; i < n; i++) {
            dif[i] = (Double.isNaN(emaFast[i]) || Double.isNaN(emaSlow[i])) ? Double.NaN : emaFast[i] - emaSlow[i];
        }
        double[] dea = emaSkipNaN(dif, signal);
        double[] hist = new double[n];
        for (int i = 0; i < n; i++) {
            hist[i] = (Double.isNaN(dif[i]) || Double.isNaN(dea[i])) ? Double.NaN : 2 * (dif[i] - dea[i]);
        }
        return new Macd(dif, dea, hist);
    }

    public record Macd(double[] macd, double[] signal, double[] hist) {}

    /** 布林带：上中下三条，k 通常 2.0。 */
    public static Boll boll(double[] closes, int period, double k) {
        int n = closes.length;
        double[] upper = new double[n];
        double[] middle = new double[n];
        double[] lower = new double[n];
        for (int i = 0; i < n; i++) { upper[i] = middle[i] = lower[i] = Double.NaN; }
        if (period <= 1 || n < period) return new Boll(upper, middle, lower);
        for (int i = period - 1; i < n; i++) {
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) sum += closes[j];
            double mean = sum / period;
            double sumSq = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double d = closes[j] - mean;
                sumSq += d * d;
            }
            double std = Math.sqrt(sumSq / period);
            middle[i] = mean;
            upper[i] = mean + k * std;
            lower[i] = mean - k * std;
        }
        return new Boll(upper, middle, lower);
    }

    public record Boll(double[] upper, double[] middle, double[] lower) {}

    /**
     * KDJ：n 周期 RSV，K=SMA(RSV, k)（A 股标准用 1/k 平滑），D=SMA(K, d)，J=3K-2D。
     * 这里采用国内通行的"前值 * (k-1)/k + 当期 * 1/k"递推。
     */
    public static Kdj kdj(double[] highs, double[] lows, double[] closes, int n, int kPeriod, int dPeriod) {
        int len = closes.length;
        double[] kArr = new double[len];
        double[] dArr = new double[len];
        double[] jArr = new double[len];
        for (int i = 0; i < len; i++) { kArr[i] = dArr[i] = jArr[i] = Double.NaN; }
        if (n <= 0 || len < n) return new Kdj(kArr, dArr, jArr);

        double prevK = 50, prevD = 50;
        for (int i = n - 1; i < len; i++) {
            double hh = highs[i], ll = lows[i];
            for (int j = i - n + 1; j <= i; j++) {
                if (highs[j] > hh) hh = highs[j];
                if (lows[j] < ll) ll = lows[j];
            }
            double rsv = (hh == ll) ? 50 : (closes[i] - ll) / (hh - ll) * 100;
            double k = ((kPeriod - 1.0) / kPeriod) * prevK + (1.0 / kPeriod) * rsv;
            double d = ((dPeriod - 1.0) / dPeriod) * prevD + (1.0 / dPeriod) * k;
            kArr[i] = k;
            dArr[i] = d;
            jArr[i] = 3 * k - 2 * d;
            prevK = k;
            prevD = d;
        }
        return new Kdj(kArr, dArr, jArr);
    }

    public record Kdj(double[] k, double[] d, double[] j) {}

    /** 经典 EMA：第一个 EMA = 前 period 个 close 的 SMA，之后 EMA(i) = EMA(i-1) + α*(close - EMA(i-1))，α=2/(period+1)。 */
    private static double[] ema(double[] closes, int period) {
        int n = closes.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = Double.NaN;
        if (period <= 0 || n < period) return out;
        double sum = 0;
        for (int i = 0; i < period; i++) sum += closes[i];
        double prev = sum / period;
        out[period - 1] = prev;
        double alpha = 2.0 / (period + 1);
        for (int i = period; i < n; i++) {
            prev = prev + alpha * (closes[i] - prev);
            out[i] = prev;
        }
        return out;
    }

    /** 对一条含前缀 NaN 的序列计算 EMA（用于 MACD 的 DEA）。 */
    private static double[] emaSkipNaN(double[] src, int period) {
        int n = src.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = Double.NaN;
        int firstValid = -1;
        for (int i = 0; i < n; i++) if (!Double.isNaN(src[i])) { firstValid = i; break; }
        if (firstValid < 0 || n - firstValid < period || period <= 0) return out;
        double sum = 0;
        for (int i = firstValid; i < firstValid + period; i++) sum += src[i];
        double prev = sum / period;
        out[firstValid + period - 1] = prev;
        double alpha = 2.0 / (period + 1);
        for (int i = firstValid + period; i < n; i++) {
            prev = prev + alpha * (src[i] - prev);
            out[i] = prev;
        }
        return out;
    }
}
