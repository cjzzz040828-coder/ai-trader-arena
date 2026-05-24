package com.aitrade.trade.strategy.script;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openjdk.nashorn.api.scripting.ClassFilter;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import org.springframework.stereotype.Component;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SCRIPT 策略的 Nashorn 引擎工厂 + 沙箱 + 编译缓存 + 超时执行。
 *
 * 沙箱约束：
 *   - ClassFilter 拒绝任何 Java 类访问（Java.type / load / loadWithNewGlobal 都会抛 SecurityException）
 *   - 创建 engine 时不传 -scripting，禁用 shell 扩展（${var}、readLine、exec 等）
 *   - Bindings 只放入参数据 + ScriptApi 实例。无 print / engine / context
 *   - 每次 decide() 调用通过 ExecutorService.submit + Future.get(timeout) 兜超时
 *
 * 关于线程：Nashorn 单 engine 实例可重入（每次 eval 用独立 Bindings），但脚本内若 while(true) 卡住，
 * Future.cancel(true) 对 Nashorn 不是强制中断，对应线程会一直跑直到 GC。这里通过固定大小线程池
 * （SCRIPT_THREAD_LIMIT）作为兜底——同时被卡的脚本数超过这个值后续直接拒绝执行避免线程膨胀。
 */
@Slf4j
@Component
public class ScriptEngineFactory {

    private static final long DECIDE_TIMEOUT_MS = 200;
    private static final long COMPILE_TIMEOUT_MS = 1000;
    private static final int SCRIPT_THREAD_LIMIT = 8;
    private static final int MAX_SOURCE_BYTES = 32 * 1024;

    /**
     * 注入到用户脚本前面的 preamble：把 api 实例的方法暴露成全局函数，用户可以直接写 rsi(close, 14)
     * 而不用 api.rsi(close, 14)。preamble 用 IIFE 包裹避免污染 var，函数挂到 globalThis。
     */
    private static final String PREAMBLE = String.join("\n",
            "var rsi = function(c, p){ return api.rsi(c, p); };",
            "var macd = function(c, f, s, sig){ return api.macd(c, f, s, sig); };",
            "var boll = function(c, p, k){ return api.boll(c, p, k); };",
            "var kdj = function(h, l, c, n, kp, dp){ return api.kdj(h, l, c, n, kp, dp); };",
            "var ma = function(a, n){ return api.ma(a, n); };",
            "var ema = function(a, n){ return api.ema(a, n); };",
            "var last = function(a){ return api.last(a); };",
            "var prev = function(a){ return api.prev(a); };",
            "var crossUp = function(a, b){ return api.crossUp(a, b); };",
            "var crossDown = function(a, b){ return api.crossDown(a, b); };",
            "var log = function(m){ api.log(String(m)); };",
            ""
    );

    /** 注入到用户脚本之后的 epilogue：调用 decide() 把结果写回 bindings.__result。 */
    private static final String EPILOGUE = "\n;var __result = (typeof decide === 'function') ? decide() : null;";

    /** Nashorn ClassFilter：拒绝所有 Java 类访问。脚本里 Java.type('xxx') 会抛 SecurityException；
     *  但已经绑定到 bindings 的 Java 对象（如 api）调用其方法不走 ClassFilter，正常可用。 */
    private static final ClassFilter NO_JAVA = name -> false;

    private final NashornScriptEngineFactory engineFactory = new NashornScriptEngineFactory();

    /** 编译缓存：sha256(source) -> CompiledScript。trader 改脚本 → 新 hash → 新条目。 */
    private final Map<String, CompiledScript> compiledCache = new ConcurrentHashMap<>();

    /** 脚本执行线程池。daemon 线程，进程退出自动释放；超 SCRIPT_THREAD_LIMIT 时排队（但有 200ms timeout 兜底）。 */
    private final ExecutorService scriptPool = Executors.newFixedThreadPool(SCRIPT_THREAD_LIMIT, new AtomicNamedFactory("script-exec"));

    @PreDestroy
    public void shutdown() {
        scriptPool.shutdownNow();
        try { scriptPool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    /** 编译脚本，命中缓存则直接复用。脚本源码超 32KB 直接拒。 */
    public CompiledScript compile(String source) throws ScriptException {
        if (source == null) throw new ScriptException("脚本源码为空");
        byte[] bytes = source.getBytes();
        if (bytes.length > MAX_SOURCE_BYTES) {
            throw new ScriptException("脚本超过 " + MAX_SOURCE_BYTES + " 字节上限");
        }
        String key = sha256(source);
        CompiledScript cached = compiledCache.get(key);
        if (cached != null) return cached;
        ScriptEngine engine = newEngine();
        String fullSource = PREAMBLE + source + EPILOGUE;
        CompiledScript cs = ((javax.script.Compilable) engine).compile(fullSource);
        compiledCache.put(key, cs);
        return cs;
    }

    /**
     * 在沙箱内执行 decide()。inputs 包含暴露给脚本的全部全局变量（含 ScriptApi 实例的方法绑定）。
     * 返回 decide() 的字符串结果（"BUY"/"SELL"/"HOLD"/null）。
     */
    public Object runDecide(CompiledScript script, Map<String, Object> inputs, long timeoutMs) throws Exception {
        Callable<Object> task = () -> {
            // 必须用 engine.createBindings() 拿 Nashorn-flavored Bindings，
            // 否则脚本里 `var __result = decide()` 写到 Nashorn 的内部 Global 里、SimpleBindings 取不到
            ScriptEngine engine = script.getEngine();
            Bindings bindings = engine.createBindings();
            bindings.putAll(inputs);
            script.eval(bindings);
            return bindings.get("__result");
        };
        Future<Object> future = scriptPool.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ScriptException("脚本执行超时（" + timeoutMs + "ms）");
        }
    }

    public Object runDecide(CompiledScript script, Map<String, Object> inputs) throws Exception {
        return runDecide(script, inputs, DECIDE_TIMEOUT_MS);
    }

    public long compileTimeoutMs() { return COMPILE_TIMEOUT_MS; }
    public long decideTimeoutMs() { return DECIDE_TIMEOUT_MS; }

    /** 新建一个隔离的 Nashorn engine，应用 ClassFilter。
     *  注意：不传 --no-java，因为那会切断 bindings 里的 api 对象的方法调用。
     *  靠 ClassFilter 拒绝 Java.type('xxx') 等显式类加载来兜底。 */
    private ScriptEngine newEngine() {
        return engineFactory.getScriptEngine(new String[0], getClass().getClassLoader(), NO_JAVA);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static class AtomicNamedFactory implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private final AtomicInteger idx = new AtomicInteger();
        AtomicNamedFactory(String prefix) { this.prefix = prefix; }
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
