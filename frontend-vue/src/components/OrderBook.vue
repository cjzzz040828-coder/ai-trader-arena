<script setup lang="ts">
import { computed } from 'vue'
import type { SnapshotItem } from '@/api'

const props = defineProps<{ snap: SnapshotItem | null }>()

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

const maxVol = computed(() => {
  const all = [...askRows.value, ...bidRows.value].map(r => r.vol || 0)
  return Math.max(1, ...all)
})

function barWidth(vol: number) {
  return `${Math.min(100, (vol / maxVol.value) * 100)}%`
}

function fmtPrice(p: number, lastClose: number) {
  if (!p) return '--'
  return p.toFixed(2)
}
function priceColor(p: number) {
  if (!props.snap || !p) return '#666'
  return p > props.snap.last_close ? '#ef4444' : (p < props.snap.last_close ? '#10b981' : '#666')
}
</script>

<template>
  <div class="orderbook" v-if="snap">
    <!-- 表头 -->
    <div class="row header">
      <span class="label">档位</span>
      <span class="price">价</span>
      <span class="vol">量(手)</span>
    </div>

    <!-- 卖盘 -->
    <div v-for="row in askRows" :key="'a' + row.level" class="row ask">
      <div class="bar" :style="{ width: barWidth(row.vol) }"></div>
      <span class="label">{{ row.label }}</span>
      <span class="price">{{ fmtPrice(row.price, snap.last_close) }}</span>
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
    <div v-for="row in bidRows" :key="'b' + row.level" class="row bid">
      <div class="bar" :style="{ width: barWidth(row.vol) }"></div>
      <span class="label">{{ row.label }}</span>
      <span class="price">{{ fmtPrice(row.price, snap.last_close) }}</span>
      <span class="vol">{{ row.vol }}</span>
    </div>
  </div>
</template>

<style scoped>
.orderbook {
  font-family: 'Consolas', monospace;
  font-size: 13px;
}
.row {
  position: relative;
  display: flex;
  align-items: center;
  height: 28px;
  padding: 0 10px;
  border-bottom: 1px solid #f3f4f6;
  overflow: hidden;
}
.row .bar {
  position: absolute;
  top: 0; right: 0; bottom: 0;
  z-index: 0;
}
.row.ask .bar { background: rgba(16, 185, 129, 0.10); }
.row.bid .bar { background: rgba(239, 68, 68, 0.10); }
.row.ask .price { color: #10b981; }
.row.bid .price { color: #ef4444; }
.row .label { flex: 0 0 36px; color: #9ca3af; z-index: 1; }
.row .price { flex: 1; z-index: 1; font-weight: 600; }
.row .vol { flex: 0 0 80px; text-align: right; color: #4b5563; z-index: 1; }

.row.header {
  height: 24px;
  background: #f9fafb;
  color: #6b7280;
  font-size: 12px;
  border-bottom: 1px solid #e5e7eb;
}
.row.header .label,
.row.header .price,
.row.header .vol { color: #6b7280; font-weight: 400; }
.row.header .price { text-align: left; }

.current {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px;
  background: #fafafa;
  border-top: 2px solid #d1d5db;
  border-bottom: 2px solid #d1d5db;
}
</style>
