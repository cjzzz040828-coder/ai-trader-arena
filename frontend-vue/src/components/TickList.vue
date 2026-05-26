<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, computed } from 'vue'
import { api, type TickItem } from '@/api'

const props = withDefaults(defineProps<{
  code: string
  lastClose: number
  compact?: boolean
  intervalMs?: number
}>(), {
  compact: false,
  intervalMs: 3000
})

const ticks = ref<TickItem[]>([])
let timer: number | null = null

async function load() {
  if (!props.code) {
    ticks.value = []
    return
  }
  try {
    const resp = await api.transaction(props.code, 200)
    ticks.value = resp.data || []
  } catch {
    // 静默失败：明细只是辅助，不影响主流程
  }
}

function clearTimer() {
  if (timer != null) {
    window.clearInterval(timer)
    timer = null
  }
}

function setupTimer() {
  clearTimer()
  if (props.intervalMs > 0) {
    timer = window.setInterval(load, props.intervalMs)
  }
}

onMounted(() => {
  load()
  setupTimer()
})

onBeforeUnmount(clearTimer)

watch(() => props.code, () => {
  ticks.value = []
  load()
})

// 倒序（最新在上）；compact 模式只取前 4 行
const visibleRows = computed(() => {
  const arr = [...ticks.value].reverse()
  return props.compact ? arr.slice(0, 4) : arr
})

// 价格颜色用 tick-rule：现价 vs 上一笔，A 股红涨绿跌。
// 旧实现只信 buyorsell 字段，但 mootdx/tdxpy 不同版本里这个字段语义不稳，
// 实测大段时间窗口里几乎全是 1，导致明细一片绿/红，体验明显不真实。
// idx 是 visibleRows 里的位置（0=最新），更早的一笔在 idx+1。
function priceColor(idx: number, list: TickItem[]): string {
  const cur = list[idx]
  if (!cur) return 'var(--brand-text-primary)'
  const prev = list[idx + 1]
  if (prev && prev.price > 0) {
    if (cur.price > prev.price) return 'var(--brand-up)'    // uptick → 主动买（红）
    if (cur.price < prev.price) return 'var(--brand-down)'  // downtick → 主动卖（绿）
  }
  // 同价位 / 无前一笔：退到 buyorsell 兜底（0=买 1=卖 2=中性）
  if (cur.buyorsell === 0) return 'var(--brand-up)'
  if (cur.buyorsell === 1) return 'var(--brand-down)'
  return 'var(--brand-text-primary)'
}

// 金额单位：元 → 万 / 亿 自适应
// < 1万 → 原值；< 100万 → X.X万；< 1亿 → XX万；>= 1亿 → X.XX亿
function fmtAmount(amount: number): string {
  if (!amount || !isFinite(amount)) return '--'
  if (amount >= 1e8) return (amount / 1e8).toFixed(2) + '亿'
  if (amount >= 1e6) return (amount / 1e4).toFixed(0) + '万'
  if (amount >= 1e4) return (amount / 1e4).toFixed(1) + '万'
  return amount.toFixed(0)
}
</script>

<template>
  <div class="tick-list">
    <div v-if="visibleRows.length === 0" class="empty">暂无明细</div>
    <div v-else>
      <div v-for="(t, i) in visibleRows" :key="(t.time || '') + i" class="tick-row">
        <span class="time">{{ t.time }}</span>
        <span class="price" :style="{ color: priceColor(i, visibleRows) }">{{ t.price.toFixed(2) }}</span>
        <span class="amt" :style="{ color: priceColor(i, visibleRows) }">{{ fmtAmount(t.amount) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tick-list {
  font-family: 'Consolas', monospace;
  font-size: 13px;
  color: var(--brand-text-regular);
}
.tick-row {
  display: flex;
  align-items: center;
  height: 26px;
  padding: 0 10px;
}
.tick-row .time {
  flex: 0 0 52px;
  color: var(--brand-text-secondary);
  font-size: 12px;
}
.tick-row .price {
  flex: 1;
  font-weight: 700;
  font-size: 14px;
}
.tick-row .amt {
  flex: 0 0 70px;
  text-align: right;
  font-weight: 600;
}
.empty {
  padding: 16px 10px;
  text-align: center;
  color: var(--brand-text-placeholder);
  font-size: 12px;
}
</style>
