<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { api, type BarItem, type TradeMarker } from '@/api'
import { readChartColors } from '@/composables/chartTheme'
import { useTheme } from '@/composables/theme'

const props = defineProps<{ code: string; frequency?: number; count?: number; trades?: TradeMarker[] }>()
const theme = useTheme()

const chartEl = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)
const loading = ref(false)
const errMsg = ref('')
const lastBars = ref<BarItem[]>([])

/** 把成交时间字符串归一到 "YYYY-MM-DD HH:mm:ss" */
function normalizeFilledAt(s: string): string {
  return s.replace('T', ' ').replace(/\.\d+$/, '')
}

/** 找到 bar.datetime <= filledAt 的最后一根 bar，把买卖点钉在那根 K 线上 */
function buildTradeMarks(bars: BarItem[], trades: TradeMarker[]) {
  if (!bars.length || !trades.length) return []
  const c = readChartColors()
  const marks: any[] = []
  for (const t of trades) {
    const ft = normalizeFilledAt(t.filledAt)
    let idx = -1
    for (let i = 0; i < bars.length; i++) {
      if (bars[i].datetime <= ft) idx = i
      else break
    }
    if (idx < 0) continue
    const bar = bars[idx]
    const isBuy = t.side === 'BUY'
    const range = Math.max(bar.high - bar.low, bar.close * 0.003)
    const yPos = isBuy ? bar.low - range * 0.4 : bar.high + range * 0.4
    marks.push({
      name: isBuy ? 'B' : 'S',
      coord: [bar.datetime, yPos],
      value: t.price.toFixed(3),
      symbol: 'triangle',
      symbolSize: [16, 18],
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
        fontSize: 11,
        fontWeight: 700,
        distance: 3
      },
      _trade: t
    })
  }
  return marks
}

function buildOption(bars: BarItem[], trades: TradeMarker[]) {
  const c = readChartColors()
  const dates = bars.map(b => b.datetime)
  const klineData = bars.map(b => [b.open, b.close, b.low, b.high])
  const volumes = bars.map((b, i) => [i, b.vol, b.close >= b.open ? 1 : -1])

  // 简单 MA 计算
  const ma = (n: number) => bars.map((_, i) => {
    if (i < n - 1) return '-'
    let sum = 0
    for (let j = i - n + 1; j <= i; j++) sum += bars[j].close
    return +(sum / n).toFixed(2)
  })

  const tradeMarks = buildTradeMarks(bars, trades)

  return {
    animation: false,
    backgroundColor: 'transparent',
    textStyle: { color: c.textRegular },
    legend: { data: ['K线', 'MA5', 'MA10', 'MA20'], top: 5, textStyle: { color: c.textRegular } },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { color: c.primary }, crossStyle: { color: c.primary } },
      backgroundColor: c.tooltipBg,
      borderColor: c.primary,
      textStyle: { color: c.textRegular },
      formatter: (params: any) => {
        const arr = Array.isArray(params) ? params : [params]
        // markPoint 单独触发时 componentType === 'markPoint'
        const mp = arr.find((p: any) => p.componentType === 'markPoint')
        if (mp && mp.data?._trade) {
          const t = mp.data._trade as TradeMarker
          const isBuy = t.side === 'BUY'
          const color = isBuy ? c.up : c.down
          return `<b style="color:${color}">${isBuy ? '买入' : '卖出'}</b> ${t.amount} 股<br/>价格 <b>${t.price.toFixed(3)}</b><br/>时间 ${normalizeFilledAt(t.filledAt)}`
        }
        // 默认 axis tooltip：用 ECharts 自带格式
        return arr.map((p: any) => `${p.marker} ${p.seriesName} ${Array.isArray(p.data) ? p.data.join(' / ') : p.data}`).join('<br/>')
      }
    },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: '8%', right: '4%', top: 40, height: '56%' },
      { left: '8%', right: '4%', top: '72%', height: '14%' }
    ],
    xAxis: [
      { type: 'category', data: dates, scale: true, boundaryGap: false, axisLine: { onZero: false, lineStyle: { color: c.border } }, splitLine: { show: false }, axisLabel: { show: false } },
      { type: 'category', gridIndex: 1, data: dates, scale: true, boundaryGap: false, axisLine: { onZero: false, lineStyle: { color: c.border } }, axisTick: { show: false }, splitLine: { show: false }, axisLabel: { color: c.textMuted } }
    ],
    yAxis: [
      { scale: true, splitArea: { show: false }, splitLine: { lineStyle: { color: c.borderLight } }, axisLine: { lineStyle: { color: c.border } }, axisLabel: { color: c.textMuted } },
      { gridIndex: 1, splitNumber: 2, axisLabel: { show: false }, axisLine: { show: false }, axisTick: { show: false }, splitLine: { show: false } }
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: 60, end: 100 },
      { show: true, type: 'slider', xAxisIndex: [0, 1], bottom: 8, height: 20, start: 60, end: 100, backgroundColor: c.bgSoft, borderColor: c.border, fillerColor: c.primary + '33', handleStyle: { color: c.primary }, textStyle: { color: c.textMuted } }
    ],
    series: [
      {
        name: 'K线',
        type: 'candlestick',
        data: klineData,
        itemStyle: {
          color: c.up,
          color0: c.down,
          borderColor: c.up,
          borderColor0: c.down
        },
        markPoint: tradeMarks.length ? {
          symbol: 'triangle',
          symbolSize: [16, 18],
          data: tradeMarks
        } : undefined
      },
      { name: 'MA5', type: 'line', data: ma(5), smooth: true, lineStyle: { width: 1.5, color: c.textRegular }, showSymbol: false },
      { name: 'MA10', type: 'line', data: ma(10), smooth: true, lineStyle: { width: 1.5, color: c.primary }, showSymbol: false },
      { name: 'MA20', type: 'line', data: ma(20), smooth: true, lineStyle: { width: 1.5, color: '#fbbf24' }, showSymbol: false },
      {
        name: '成交量',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: volumes,
        itemStyle: {
          color: (params: any) => params.data[2] >= 0 ? c.up : c.down
        }
      }
    ]
  }
}

async function load() {
  if (!props.code) return
  loading.value = true
  errMsg.value = ''
  try {
    const resp = await api.bars(props.code, props.frequency ?? 9, props.count ?? 240)
    if (!chart.value && chartEl.value) {
      chart.value = echarts.init(chartEl.value)
    }
    if (resp.data?.length) {
      lastBars.value = resp.data
      chart.value?.setOption(buildOption(resp.data, props.trades || []), true)
    } else {
      errMsg.value = '暂无K线数据'
    }
  } catch (e: any) {
    errMsg.value = e?.message || 'K线请求失败'
  } finally {
    loading.value = false
  }
}

/** 仅重画买卖点（trades 变了但 bars 没变时用，避免重新拉网络） */
function redrawMarks() {
  if (!chart.value || !lastBars.value.length) return
  chart.value.setOption(buildOption(lastBars.value, props.trades || []), true)
}

function onResize() {
  chart.value?.resize()
}

onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart.value?.dispose()
})

watch(() => [props.code, props.frequency], load)
watch(() => props.trades, redrawMarks, { deep: true })
watch(() => theme.mode.value, redrawMarks)
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
}
.overlay.error { color: var(--brand-up); }
</style>
