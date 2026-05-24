<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SnapshotItem } from '@/api'
import TickList from './TickList.vue'

const props = defineProps<{ snap: SnapshotItem | null; code: string }>()

type Mode = 'detail-major' | 'orderbook-major'
const mode = ref<Mode>('detail-major')

function toggleMode() {
  mode.value = mode.value === 'detail-major' ? 'orderbook-major' : 'detail-major'
}

const modeLabel = computed(() => mode.value === 'detail-major' ? '明细大' : '盘口大')

interface Row { label: string; price: number; vol: number; level: number }

const askRows = computed<Row[]>(() => {
  if (!props.snap) return []
  return [
    { label: '卖5', price: props.snap.ask5, vol: props.snap.ask_vol5, level: 5 },
    { label: '卖4', price: props.snap.ask4, vol: props.snap.ask_vol4, level: 4 },
    { label: '卖3', price: props.snap.ask3, vol: props.snap.ask_vol3, level: 3 },
    { label: '卖2', price: props.snap.ask2, vol: props.snap.ask_vol2, level: 2 },
    { label: '卖1', price: props.snap.ask1, vol: props.snap.ask_vol1, level: 1 }
  ]
})

const bidRows = computed<Row[]>(() => {
  if (!props.snap) return []
  return [
    { label: '买1', price: props.snap.bid1, vol: props.snap.bid_vol1, level: 1 },
    { label: '买2', price: props.snap.bid2, vol: props.snap.bid_vol2, level: 2 },
    { label: '买3', price: props.snap.bid3, vol: props.snap.bid_vol3, level: 3 },
    { label: '买4', price: props.snap.bid4, vol: props.snap.bid_vol4, level: 4 },
    { label: '买5', price: props.snap.bid5, vol: props.snap.bid_vol5, level: 5 }
  ]
})

// 按 mode 切片：detail-major 时上只露 卖1+买1；orderbook-major 完整 5 档
const visibleAskRows = computed<Row[]>(() =>
  mode.value === 'detail-major' ? askRows.value.slice(-1) : askRows.value
)
const visibleBidRows = computed<Row[]>(() =>
  mode.value === 'detail-major' ? bidRows.value.slice(0, 1) : bidRows.value
)

const maxVol = computed(() => {
  const all = [...askRows.value, ...bidRows.value].map(r => r.vol || 0)
  return Math.max(1, ...all)
})

function barWidth(vol: number) {
  return `${Math.min(100, (vol / maxVol.value) * 100)}%`
}

function fmtPrice(p: number) {
  if (!p) return '--'
  return p.toFixed(2)
}
function priceColor(p: number) {
  if (!props.snap || !p) return 'var(--brand-neutral)'
  return p > props.snap.last_close ? 'var(--brand-up)' : (p < props.snap.last_close ? 'var(--brand-down)' : 'var(--brand-neutral)')
}
</script>

<template>
  <div class="orderbook" v-if="snap" @click="toggleMode" :title="modeLabel + '（点击切换）'">
    <!-- 标题栏（带模式提示） -->
    <div class="header-bar">
      <span class="title">五档盘口 / 明细</span>
      <span class="mode-tag">⇅ {{ modeLabel }}</span>
    </div>

    <!-- 盘口区（按 mode 收放） -->
    <div class="quotes">
      <!-- 表头 -->
      <div class="row header">
        <span class="label">档位</span>
        <span class="price">价</span>
        <span class="vol">量(手)</span>
      </div>

      <!-- 卖盘 -->
      <div v-for="row in visibleAskRows" :key="'a' + row.level" class="row ask">
        <div class="bar" :style="{ width: barWidth(row.vol) }"></div>
        <span class="label">{{ row.label }}</span>
        <span class="price">{{ fmtPrice(row.price) }}</span>
        <span class="vol">{{ row.vol }}</span>
      </div>

      <!-- 现价分隔 -->
      <div class="current">
        <span>现价</span>
        <span :style="{ color: priceColor(snap.price), fontSize: '1.4em', fontWeight: 700 }">
          {{ snap.price.toFixed(2) }}
        </span>
        <span :style="{ color: snap.change >= 0 ? '#ef4444' : '#10b981' }">
          {{ snap.change >= 0 ? '+' : '' }}{{ snap.change }} ({{ snap.change_pct >= 0 ? '+' : '' }}{{ snap.change_pct }}%)
        </span>
      </div>

      <!-- 买盘 -->
      <div v-for="row in visibleBidRows" :key="'b' + row.level" class="row bid">
        <div class="bar" :style="{ width: barWidth(row.vol) }"></div>
        <span class="label">{{ row.label }}</span>
        <span class="price">{{ fmtPrice(row.price) }}</span>
        <span class="vol">{{ row.vol }}</span>
      </div>
    </div>

    <!-- 明细区（吃掉剩余空间，detail-major 大、orderbook-major 小） -->
    <div class="tick-wrap">
      <TickList :code="code" :last-close="snap.last_close" :compact="mode === 'orderbook-major'" />
    </div>
  </div>
</template>

<style scoped>
.orderbook {
  font-family: 'Consolas', monospace;
  font-size: 13px;
  display: flex;
  flex-direction: column;
  height: 100%;
  cursor: pointer;
  transition: background 0.15s ease;
}
.orderbook:hover {
  background: var(--brand-bg-soft);
}

.header-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  font-size: 14px;
  font-weight: 600;
  color: var(--brand-text-primary);
  border-bottom: 1px solid var(--brand-border);
}
.header-bar .mode-tag {
  font-size: 12px;
  font-weight: 500;
  color: var(--brand-primary);
}

.quotes {
  flex: 0 0 auto;
}
.tick-wrap {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.row {
  position: relative;
  display: flex;
  align-items: center;
  height: 28px;
  padding: 0 10px;
  border-bottom: 1px solid var(--brand-border-light);
  overflow: hidden;
}
.row .bar {
  position: absolute;
  top: 0; right: 0; bottom: 0;
  z-index: 0;
}
.row.ask .bar { background: var(--brand-down-soft); }
.row.bid .bar { background: var(--brand-up-soft); }
.row.ask .price { color: var(--brand-down); }
.row.bid .price { color: var(--brand-up); }
.row .label { flex: 0 0 36px; color: var(--brand-text-placeholder); z-index: 1; }
.row .price { flex: 1; z-index: 1; font-weight: 600; }
.row .vol { flex: 0 0 80px; text-align: right; color: var(--brand-text-regular); z-index: 1; }

.row.header {
  height: 24px;
  background: var(--brand-bg-soft);
  color: var(--brand-text-secondary);
  font-size: 12px;
  border-bottom: 1px solid var(--brand-border);
}
.row.header .label,
.row.header .price,
.row.header .vol { color: var(--brand-text-secondary); font-weight: 400; }
.row.header .price { text-align: left; }

.current {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px;
  background: var(--brand-bg-soft);
  border-top: 2px solid var(--brand-border);
  border-bottom: 2px solid var(--brand-border);
}
</style>
