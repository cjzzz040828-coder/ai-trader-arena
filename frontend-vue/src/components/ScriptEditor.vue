<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'

const props = defineProps<{
  modelValue: string
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
}>()

const code = ref(props.modelValue || '')
watch(() => props.modelValue, v => { if (v !== code.value) code.value = v || '' })
watch(code, v => emit('update:modelValue', v))

const samples: { label: string; code: string }[] = [
  {
    label: 'RSI 反转',
    code: `// 经典 RSI 反转：超卖买、超买卖
function decide() {
  var r = last(rsi(close, 14));
  if (holding === 0 && r < 30) return "BUY";
  if (holding > 0 && r > 70) return "SELL";
  return "HOLD";
}
`
  },
  {
    label: 'MACD 金叉死叉',
    code: `// MACD 金叉买、死叉卖
function decide() {
  var m = macd(close, 12, 26, 9);
  if (holding === 0 && crossUp(m.macd, m.signal)) return "BUY";
  if (holding > 0 && crossDown(m.macd, m.signal)) return "SELL";
  return "HOLD";
}
`
  },
  {
    label: '布林带通道',
    code: `// 跌穿下轨买入、突破上轨卖出
function decide() {
  var b = boll(close, 20, 2.0);
  var p = price;
  if (holding === 0 && p < last(b.lower)) return "BUY";
  if (holding > 0 && p > last(b.upper)) return "SELL";
  return "HOLD";
}
`
  },
  {
    label: '止损止盈（带成本）',
    code: `// MACD 金叉买；持仓后 +15% 止盈、-8% 止损
function decide() {
  var m = macd(close, 12, 26, 9);
  if (holding === 0 && crossUp(m.macd, m.signal)) return "BUY";
  if (holding > 0 && costPrice > 0) {
    var pct = (price - costPrice) / costPrice;
    if (pct >= 0.15) return "SELL"; // 止盈
    if (pct <= -0.08) return "SELL"; // 止损
  }
  return "HOLD";
}
`
  },
  {
    label: '首板打板（极短线）',
    code: `// 首板量价打板：T 日识别涨停+放量，T+1 open 接力
// 买点：涨幅≥9.5% + 量比≥1.5 + 站上 MA20 + 5 日内非连板
// 卖点：+5% 止盈 / -3% 止损 / RSI(6)<40 趋势衰竭
// 注意：日 K 限制下一字板会因高开撤单，回测偏保守
function decide() {
  var n = close.length;
  if (n < 25) return "HOLD";

  // 持仓中：盯止盈止损
  if (holding > 0 && costPrice > 0) {
    var pct = (price - costPrice) / costPrice;
    if (pct >= 0.05) { log("止盈 " + (pct * 100).toFixed(2) + "%"); return "SELL"; }
    if (pct <= -0.03) { log("止损 " + (pct * 100).toFixed(2) + "%"); return "SELL"; }
    var r6 = last(rsi(close, 6));
    if (r6 < 40) { log("RSI 走弱=" + r6.toFixed(2)); return "SELL"; }
    return "HOLD";
  }

  // 空仓：找打板信号
  var c = close[n - 1], cPrev = close[n - 2], v = vol[n - 1];

  // 1. 涨幅≥9.5%（接近或触及涨停）
  var rise = (c - cPrev) / cPrev;
  if (rise < 0.095) return "HOLD";

  // 2. 放量：今日量≥5 日均量(不含今日)×1.5
  var v5 = ma(vol, 5);
  var vAvg = v5[n - 2];
  if (!vAvg || v < vAvg * 1.5) return "HOLD";

  // 3. 站上 MA20，过滤下跌通道反弹
  var ma20 = last(ma(close, 20));
  if (c < ma20) return "HOLD";

  // 4. 过去 5 日不能有另一次涨停（避免连板高位接力）
  for (var i = n - 6; i < n - 1; i++) {
    if ((close[i] - close[i - 1]) / close[i - 1] >= 0.095) {
      log("5 日内已有涨停，跳过");
      return "HOLD";
    }
  }

  log("打板信号 涨幅=" + (rise * 100).toFixed(2) + "% 量比=" + (v / vAvg).toFixed(2));
  return "BUY";
}
`
  }
]

function applySample(s: { label: string; code: string }) {
  code.value = s.code
  ElMessage.success(`已加载示例：${s.label}`)
}

const testing = ref(false)
const testResult = ref<{ ok: boolean; message: string; sample?: string } | null>(null)
async function runTest() {
  if (!code.value.trim()) {
    ElMessage.warning('脚本为空')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const r = await api.trade.testScript(code.value)
    testResult.value = r
    if (r.ok) ElMessage.success('编译 + 运行通过')
    else ElMessage.error(r.message)
  } catch (e: any) {
    testResult.value = { ok: false, message: e?.response?.data?.message || e?.message || '请求失败' }
    ElMessage.error('测试失败')
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <div class="script-editor">
    <div class="head">
      <span class="title">JS 脚本</span>
      <span class="hint">需定义 function decide() 返回 "BUY" / "SELL" / "HOLD"</span>
      <span class="spacer"></span>
      <el-dropdown @command="applySample" trigger="click">
        <el-button size="small" plain>插入示例 ▼</el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="s in samples" :key="s.label" :command="s">{{ s.label }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button size="small" type="primary" :loading="testing" @click="runTest">测试编译</el-button>
    </div>
    <el-input v-model="code" type="textarea" :rows="14" resize="vertical"
              class="code-area" :input-style="{ fontFamily: 'Consolas, Menlo, Monaco, monospace', fontSize: '13px' }"
              placeholder="// 在此编写 JavaScript 策略代码"
              :maxlength="32000" show-word-limit />
    <div v-if="testResult" class="test-result" :class="{ ok: testResult.ok, fail: !testResult.ok }">
      <strong>{{ testResult.ok ? '✓' : '✗' }}</strong>
      <span>{{ testResult.message }}</span>
    </div>
    <div class="api-doc">
      <strong>可用变量</strong>：<code>close[] open[] high[] low[] vol[]</code>（升序日 K），
      <code>price</code> 当前价，<code>holding</code> 持仓数（0=空仓），<code>costPrice</code> 成本价，
      <code>code</code> <code>name</code> 股票信息<br>
      <strong>内建函数</strong>：<code>rsi(c, p) macd(c, f, s, sig) boll(c, p, k) kdj(h, l, c, n, kp, dp)</code>
      <code>ma(a, n) ema(a, n) last(a) prev(a) crossUp(a, b) crossDown(a, b) log(msg)</code>
    </div>
  </div>
</template>

<style scoped>
.script-editor { display: flex; flex-direction: column; gap: 8px; }
.head { display: flex; align-items: center; gap: 8px; }
.head .title { font-weight: 600; color: var(--brand-text-primary, #1f2937); font-size: 13px; }
.head .hint { color: var(--brand-text-secondary, #6b7280); font-size: 12px; }
.head .spacer { flex: 1; }
.code-area :deep(textarea) { line-height: 1.5; }
.test-result { padding: 6px 10px; border-radius: 4px; font-size: 12px; display: flex; gap: 8px; align-items: flex-start; }
.test-result.ok { background: rgba(34, 197, 94, 0.1); color: #15803d; border: 1px solid rgba(34, 197, 94, 0.3); }
.test-result.fail { background: rgba(239, 68, 68, 0.1); color: #b91c1c; border: 1px solid rgba(239, 68, 68, 0.3); }
.api-doc { font-size: 12px; color: var(--brand-text-secondary, #6b7280); line-height: 1.7; padding: 8px 10px; background: var(--brand-bg-elev, #fafafa); border-radius: 4px; border: 1px dashed var(--brand-border, #e5e7eb); }
.api-doc code { background: rgba(0, 0, 0, 0.06); padding: 1px 4px; border-radius: 3px; font-size: 11.5px; margin: 0 2px; }
</style>
