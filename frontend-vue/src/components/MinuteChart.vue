<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { api, type BarItem, type TradeMarker } from '@/api'
import { readChartColors } from '@/composables/chartTheme'
import { useTheme } from '@/composables/theme'

const props = withDefaults(defineProps<{
  code: string
  lastClose?: number
  currentPrice?: number  // snapshot 的实时价，盖到当前分钟的 close 上做 tick 级反馈
  intervalMs?: number
  trades?: TradeMarker[]
}>(), {
  intervalMs: 3000
})
const theme = useTheme()

const chartEl = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)
const loading = ref(false)
const errMsg = ref('')
const barsToday = ref<BarItem[]>([])

// A 股交易时段 240 分钟。mootdx 用 K 线起始时刻打标，所以是 09:30~11:29 + 13:00~14:59。
// x 轴标签按人类习惯把 11:29 显示为 "11:30"，14:59 显示为 "15:00"。
const FULL_SLOTS: string[] = (() => {
  const slots: string[] = []
  const push = (h: number, m: number) => slots.push(`${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`)
  for (let h = 9, m = 30; !(h === 11 && m > 29); m++) {
    if (m === 60) { h++; m = 0 }
    push(h, m)
  }
  for (let h = 13, m = 0; !(h === 14 && m > 59); m++) {
    if (m === 60) { h++; m = 0 }
    push(h, m)
  }
  return slots
})()

const LABEL_MAP: Record<string, string> = {
  '09:30': '09:30',
  '10:30': '10:30',
  '11:29': '11:30',
  '14:00': '14:00',
  '14:59': '15:00'
}

function todayKey(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function filterToday(bars: BarItem[]): BarItem[] {
  const key = todayKey()
  return bars.filter(b => typeof b.datetime === 'string' && b.datetime.startsWith(key))
}

/** 把成交时间字符串归一到 "YYYY-MM-DD HH:mm:ss" */
function normalizeFilledAt(s: string): string {
  return s.replace('T', ' ').replace(/\.\d+$/, '')
}

/** 取出今天的成交点，按 HH:MM 落到 FULL_SLOTS 上 */
function buildTradeMarks(trades: TradeMarker[], baseline: number, maxDev: number) {
  if (!trades.length) return []
  const c = readChartColors()
  const todayPrefix = todayKey()
  const marks: any[] = []
  for (const t of trades) {
    const ft = normalizeFilledAt(t.filledAt)
    if (!ft.startsWith(todayPrefix)) continue
    const hhmm = ft.substring(11, 16)
    // 不在交易时段的成交（理论上不会发生）跳过
    if (!FULL_SLOTS.includes(hhmm)) continue
    const isBuy = t.side === 'BUY'
    // 让标记落在价格附近偏移一点，避免压住折线
    const yOffset = maxDev * 0.08
    const yPos = isBuy ? t.price - yOffset : t.price + yOffset
    marks.push({
      name: isBuy ? 'B' : 'S',
      coord: [hhmm, yPos],
      symbol: 'triangle',
      symbolSize: [14, 16],
      symbolRotate: isBuy ? 0 : 180,
      itemStyle: {
        color: isBuy ? c.up : c.down,
        borderColor: c.surface,
        borderWidth: 1
      },
      label: {
        show: true,
        position: isBuy ? 'bottom' : 'top',
        formatter: isBuy ? 'B' : 'S',
        color: isBuy ? c.up : c.down,
        fontSize: 10,
        fontWeight: 700,
        distance: 3
      },
      _trade: t
    })
  }
  return marks
}

/** 当前所处的 mootdx 分钟 slot（起始时刻打标）。非交易时段返回 null。 */
function currentSlot(): string | null {
  const d = new Date()
  const total = d.getHours() * 60 + d.getMinutes()
  const inMorning = total >= 9 * 60 + 30 && total <= 11 * 60 + 29
  const inAfternoon = total >= 13 * 60 && total <= 14 * 60 + 59
  if (!inMorning && !inAfternoon) return null
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function buildOption(bars: BarItem[], baseline: number, liveTickPrice?: number, trades: TradeMarker[] = []) {
  const byTime = new Map<string, BarItem>()
  for (const b of bars) byTime.set(b.datetime.substring(11, 16), b)

  const prices: (number | null)[] = FULL_SLOTS.map(t => byTime.get(t)?.close ?? null)

  // 午休边界修补：mootdx 偶尔不返回 11:29 这根 K 线，导致 connectNulls=false 下
  // 上午末尾与 13:00 之间断开（视觉上一道空隙）。把缺失的 11:29 / 13:00 用最近的有效价填上。
  const idx1129 = FULL_SLOTS.indexOf('11:29')
  const idx1300 = FULL_SLOTS.indexOf('13:00')
  if (idx1129 >= 0 && prices[idx1129] == null) {
    for (let i = idx1129 - 1; i >= 0; i--) {
      if (prices[i] != null) { prices[idx1129] = prices[i]; break }
    }
  }
  if (idx1300 >= 0 && prices[idx1300] == null) {
    for (let i = idx1300 + 1; i < prices.length; i++) {
      if (prices[i] != null) { prices[idx1300] = prices[i]; break }
    }
  }

  const volData = FULL_SLOTS.map((t, i) => {
    const b = byTime.get(t)
    if (!b) return [t, null, 0]
    return [t, b.vol, b.close >= baseline ? 1 : -1]
  })

  // 把快照实时价盖到当前分钟 slot 上，做 tick 级更新
  if (liveTickPrice && liveTickPrice > 0) {
    const slot = currentSlot()
    if (slot) {
      const idx = FULL_SLOTS.indexOf(slot)
      if (idx >= 0) prices[idx] = liveTickPrice
    }
  }

  const realPrices = prices.filter((p): p is number => p != null)
  // 对称围绕昨收做 y 轴：让最大偏离两侧都显示，同涨同跌一目了然
  // 至少 0.01（一个 tick）做下限，避免完全平盘时 y 轴塌陷
  const maxDev = Math.max(
    ...realPrices.map(p => Math.abs(p - baseline)),
    0.01
  )
  const yMin = baseline - maxDev * 1.05
  const yMax = baseline + maxDev * 1.05

  const tradeMarks = buildTradeMarks(trades, baseline, maxDev)
  const c = readChartColors()

  return {
    animation: false,
    backgroundColor: 'transparent',
    textStyle: { color: c.textRegular },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { color: c.primary }, crossStyle: { color: c.primary } },
      backgroundColor: c.tooltipBg,
      borderColor: c.primary,
      textStyle: { color: c.textRegular },
      formatter: (params: any) => {
        const arr = Array.isArray(params) ? params : [params]
        const mp = arr.find((p: any) => p.componentType === 'markPoint')
        if (mp && mp.data?._trade) {
          const tt = mp.data._trade as TradeMarker
          const isBuy = tt.side === 'BUY'
          const color = isBuy ? c.up : c.down
          return `<b style="color:${color}">${isBuy ? '买入' : '卖出'}</b> ${tt.amount} 股<br/>价格 <b>${tt.price.toFixed(3)}</b><br/>时间 ${normalizeFilledAt(tt.filledAt)}`
        }
        const t = arr[0]?.name
        const p = arr.find((x: any) => x.seriesName === '价格')?.data
        const v = arr.find((x: any) => x.seriesName === '成交量')?.data
        const vol = Array.isArray(v) ? v[1] : v
        if (p == null) return `${t}<br/>暂无数据`
        const pct = ((p - baseline) / baseline * 100).toFixed(2)
        const color = p >= baseline ? c.up : c.down
        return `${t}<br/>价格 <b style="color:${color}">${p.toFixed(2)}</b> (${pct}%)<br/>成交量 ${vol ?? '-'} 手`
      }
    },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: '8%', right: '4%', top: 30, height: '65%' },
      { left: '8%', right: '4%', top: '78%', height: '17%' }
    ],
    xAxis: [
      {
        type: 'category',
        data: FULL_SLOTS,
        boundaryGap: false,
        axisLine: { onZero: false, lineStyle: { color: c.border } },
        axisLabel: {
          color: c.textMuted,
          interval: (idx: number, val: string) => val in LABEL_MAP,
          formatter: (val: string) => LABEL_MAP[val] || val
        },
        axisTick: { alignWithLabel: true }
      },
      {
        type: 'category',
        gridIndex: 1,
        data: FULL_SLOTS,
        axisLine: { lineStyle: { color: c.border } },
        axisLabel: { show: false },
        axisTick: { show: false }
      }
    ],
    yAxis: [
      {
        scale: true,
        min: yMin,
        max: yMax,
        splitNumber: 6,
        splitLine: { show: true, lineStyle: { color: c.borderLight } },
        axisLine: { lineStyle: { color: c.border } },
        axisLabel: {
          formatter: (v: number) => v.toFixed(2),
          color: (v: number) => v > baseline ? c.up : v < baseline ? c.down : c.textMuted
        }
      },
      { gridIndex: 1, splitNumber: 2, axisLabel: { show: false }, axisLine: { show: false }, axisTick: { show: false }, splitLine: { show: false } }
    ],
    series: [
      {
        name: '价格',
        type: 'line',
        data: prices,
        showSymbol: false,
        smooth: false,
        connectNulls: false,
        lineStyle: { width: 1.5, color: c.primary },
        areaStyle: { color: c.primary + '26' },
        markLine: {
          symbol: 'none',
          silent: true,
          lineStyle: { color: c.textMuted, type: 'dashed', width: 1 },
          data: [{ yAxis: baseline, label: { formatter: `昨收 ${baseline.toFixed(2)}`, color: c.textMuted, position: 'insideStartTop' } }]
        },
        markPoint: tradeMarks.length ? {
          data: tradeMarks
        } : undefined
      },
      {
        name: '成交量',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: volData,
        itemStyle: {
          color: (p: any) => p.data[2] >= 0 ? c.up : c.down
        }
      }
    ]
  }
}

function redraw() {
  if (!chart.value && chartEl.value) {
    chart.value = echarts.init(chartEl.value)
  }
  const baseline = props.lastClose && props.lastClose > 0
    ? props.lastClose
    : (barsToday.value[0]?.open ?? 0)
  if (baseline <= 0) {
    errMsg.value = '无昨收基准价'
    chart.value?.clear()
    return
  }
  chart.value?.setOption(buildOption(barsToday.value, baseline, props.currentPrice, props.trades || []), true)
  errMsg.value = barsToday.value.length === 0 && !props.currentPrice ? '非交易时段或暂无数据' : ''
}

async function load() {
  if (!props.code) return
  // 首次渲染前才显示加载遮罩；之后每次定时刷新静默
  if (!chart.value) loading.value = true
  try {
    const resp = await api.bars(props.code, 8, 300)
    barsToday.value = filterToday(resp.data || [])
    redraw()
  } catch (e: any) {
    errMsg.value = e?.message || '分时数据请求失败'
  } finally {
    loading.value = false
  }
}

function onResize() {
  chart.value?.resize()
}

let timer: number | null = null
function setupTimer() {
  if (timer) window.clearInterval(timer)
  if (props.intervalMs > 0) {
    timer = window.setInterval(load, props.intervalMs)
  }
}

onMounted(() => {
  load()
  setupTimer()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
  window.removeEventListener('resize', onResize)
  chart.value?.dispose()
})

watch(() => [props.code, props.lastClose], load)
watch(() => props.currentPrice, redraw)  // 实时价变了直接重绘，不打网络
watch(() => props.trades, redraw, { deep: true })
watch(() => props.intervalMs, setupTimer)
watch(() => theme.mode.value, redraw)
</script>

<template>
  <div style="position: relative;">
    <div ref="chartEl" style="width: 100%; height: 480px;"></div>
    <div v-if="loading" class="overlay">加载中...</div>
    <div v-if="errMsg" class="overlay error">{{ errMsg }}</div>
  </div>
</template>

<style scoped>
.overlay {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  background: color-mix(in srgb, var(--brand-surface) 70%, transparent);
  color: var(--brand-text-secondary);
  pointer-events: none;
}
.overlay.error { color: var(--brand-up); }
</style>
