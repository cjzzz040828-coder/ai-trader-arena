<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SnapshotItem } from '@/api'
import TickList from './TickList.vue'

const props = defineProps<{ snap: SnapshotItem | null; code: string }>()

// detailExpanded=true（明细▼）：盘口只露卖1/买1，明细列表撑满
// detailExpanded=false（明细▲）：盘口完整 5 档，明细只露 4 行预览
const detailExpanded = ref<boolean>(true)

function toggleDetail() {
  detailExpanded.value = !detailExpanded.value
}

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

const visibleAskRows = computed<Row[]>(() =>
  detailExpanded.value ? askRows.value.slice(-1) : askRows.value
)
const visibleBidRows = computed<Row[]>(() =>
  detailExpanded.value ? bidRows.value.slice(0, 1) : bidRows.value
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

// 量单位：snapshot ask_vol/bid_vol 单位是「手」，1 手 = 100 股
// < 1万股 → 原值；< 1亿股 → X.X万 / XX万；>= 1亿股 → X.XX亿
function fmtVol(hands: number): string {
  if (!hands || !isFinite(hands)) return '--'
  const shares = hands * 100
  if (shares >= 1e8) return (shares / 1e8).toFixed(2) + '亿'
  if (shares >= 1e6) return (shares / 1e4).toFixed(0) + '万'
  if (shares >= 1e4) return (shares / 1e4).toFixed(1) + '万'
  return String(shares)
}
</script>

<template>
  <div class="orderbook" v-if="snap">
    <!-- 盘口 5 档 / 1 档 -->
    <div class="quotes">
      <!-- 卖盘（卖 5 → 卖 1，从上往下） -->
      <div v-for="row in visibleAskRows" :key="'a' + row.level" class="row ask">
        <div class="bar" :style="{ width: barWidth(row.vol) }"></div>
        <span class="label">{{ row.label }}</span>
        <span class="price">{{ fmtPrice(row.price) }}</span>
        <span class="vol">{{ fmtVol(row.vol) }}</span>
      </div>

      <!-- 红绿粗分隔（左红=卖压 右绿=买力，宽度按 b_vol/s_vol 比例） -->
      <div class="divider">
        <div class="divider-left"></div>
        <div class="divider-right"></div>
      </div>

      <!-- 买盘（买 1 → 买 5，从上往下） -->
      <div v-for="row in visibleBidRows" :key="'b' + row.level" class="row bid">
        <div class="bar" :style="{ width: barWidth(row.vol) }"></div>
        <span class="label">{{ row.label }}</span>
        <span class="price">{{ fmtPrice(row.price) }}</span>
        <span class="vol">{{ fmtVol(row.vol) }}</span>
      </div>
    </div>

    <!-- 明细切换条 -->
    <div class="detail-toggle" @click="toggleDetail" :title="detailExpanded ? '点击折叠明细' : '点击展开明细'">
      <span>明细</span>
      <span class="arrow">{{ detailExpanded ? '▼' : '▲' }}</span>
    </div>

    <!-- 明细区：detailExpanded 时撑满；否则只显示 4 行预览 -->
    <div class="tick-wrap" :class="{ compact: !detailExpanded }">
      <TickList :code="code" :last-close="snap.last_close" :compact="!detailExpanded" />
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
}

.quotes {
  flex: 0 0 auto;
}

.row {
  position: relative;
  display: flex;
  align-items: center;
  height: 30px;
  padding: 0 10px;
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
.row .label {
  flex: 0 0 40px;
  color: var(--brand-text-regular);
  font-weight: 600;
  z-index: 1;
}
.row .price {
  flex: 1;
  z-index: 1;
  font-weight: 700;
  font-size: 14px;
}
.row .vol {
  flex: 0 0 80px;
  text-align: right;
  color: var(--brand-text-regular);
  z-index: 1;
  font-weight: 500;
}

/* 红绿粗分隔线：左红右绿，整体高度 6px，模拟同花顺风格 */
.divider {
  display: flex;
  height: 6px;
  margin: 2px 0;
}
.divider-left {
  flex: 1;
  background: var(--brand-down);
}
.divider-right {
  flex: 1;
  background: var(--brand-up);
}

.detail-toggle {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 26px;
  background: var(--brand-bg-soft);
  border-top: 1px solid var(--brand-border);
  border-bottom: 1px solid var(--brand-border);
  color: var(--brand-text-secondary);
  font-size: 13px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s ease;
}
.detail-toggle:hover {
  background: var(--brand-bg-hover, var(--brand-bg-soft));
  color: var(--brand-text-primary);
}
.detail-toggle .arrow {
  font-size: 11px;
  color: var(--brand-text-placeholder);
}

.tick-wrap {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.tick-wrap.compact {
  flex: 0 0 auto;
  overflow: hidden;
}
</style>
