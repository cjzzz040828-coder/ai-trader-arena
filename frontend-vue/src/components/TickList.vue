<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, computed } from 'vue'
import { api, type BarItem } from '@/api'

const props = withDefaults(defineProps<{
  code: string
  lastClose: number
  compact?: boolean
  intervalMs?: number
}>(), {
  compact: false,
  intervalMs: 3000
})

const barsToday = ref<BarItem[]>([])
let timer: number | null = null

function todayKey(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function filterToday(bars: BarItem[]): BarItem[] {
  const key = todayKey()
  return bars.filter(b => typeof b.datetime === 'string' && b.datetime.startsWith(key))
}

async function load() {
  if (!props.code) {
    barsToday.value = []
    return
  }
  try {
    const resp = await api.bars(props.code, 8, 300)
    barsToday.value = filterToday(resp.data || [])
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
  barsToday.value = []
  load()
})

// 倒序（最新在上），compact 模式只取前 4 行
const visibleRows = computed(() => {
  const arr = [...barsToday.value].reverse()
  return props.compact ? arr.slice(0, 4) : arr
})

function fmtTime(dt: string): string {
  // datetime 形如 "YYYY-MM-DD HH:MM:SS"，截 HH:MM
  return dt.length >= 16 ? dt.substring(11, 16) : dt
}

function pctOf(close: number): number {
  if (!props.lastClose) return 0
  return (close - props.lastClose) / props.lastClose * 100
}

function pctColor(pct: number): string {
  if (pct > 0) return 'var(--brand-up)'
  if (pct < 0) return 'var(--brand-down)'
  return 'var(--brand-text-placeholder)'
}

function fmtPct(pct: number): string {
  const s = pct.toFixed(2)
  return pct > 0 ? `+${s}%` : `${s}%`
}
</script>

<template>
  <div class="tick-list">
    <div v-if="visibleRows.length === 0" class="empty">暂无明细</div>
    <div v-else>
      <div v-for="(b, i) in visibleRows" :key="b.datetime || i" class="tick-row">
        <span class="time">{{ fmtTime(b.datetime) }}</span>
        <span class="price" :style="{ color: pctColor(pctOf(b.close)) }">{{ b.close.toFixed(2) }}</span>
        <span class="pct" :style="{ color: pctColor(pctOf(b.close)) }">{{ fmtPct(pctOf(b.close)) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tick-list {
  font-family: 'Consolas', monospace;
  font-size: 12px;
  color: var(--brand-text-regular);
}
.tick-row {
  display: flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-bottom: 1px solid var(--brand-border-light);
}
.tick-row .time { flex: 0 0 60px; color: var(--brand-text-placeholder); }
.tick-row .price { flex: 1; font-weight: 600; }
.tick-row .pct { flex: 0 0 70px; text-align: right; }
.empty {
  padding: 16px 10px;
  text-align: center;
  color: var(--brand-text-placeholder);
  font-size: 12px;
}
</style>
