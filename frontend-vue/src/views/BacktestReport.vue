<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { api, type BacktestTaskVO, type BacktestTradeVO } from '@/api'
import { readChartColors } from '@/composables/chartTheme'
import { useTheme } from '@/composables/theme'

const props = defineProps<{ id: number }>()
defineOptions({ name: 'BacktestReport' })

const router = useRouter()
const theme = useTheme()

const loading = ref(true)
const task = ref<BacktestTaskVO | null>(null)
const trades = ref<BacktestTradeVO[]>([])
const stockNameMap = ref<Map<string, string>>(new Map())

const equityEl = ref<HTMLDivElement | null>(null)
const heatmapEl = ref<HTMLDivElement | null>(null)
const equityChart = shallowRef<echarts.ECharts | null>(null)
const heatmapChart = shallowRef<echarts.ECharts | null>(null)

interface CurvePoint { date: string; equity: string }
interface MonthlyPoint { ym: string; returnPct: string }

const equityPoints = computed<CurvePoint[]>(() => {
  try { return JSON.parse(task.value?.equityCurveJson || '[]') } catch { return [] }
})
const benchmarkPoints = computed<CurvePoint[]>(() => {
  try { return JSON.parse(task.value?.benchmarkCurveJson || '[]') } catch { return [] }
})
const monthlyPoints = computed<MonthlyPoint[]>(() => {
  try { return JSON.parse(task.value?.monthlyReturnsJson || '[]') } catch { return [] }
})
const hasBenchmark = computed(() => benchmarkPoints.value.length > 0)
const shortWindow = computed(() => equityPoints.value.length > 0 && equityPoints.value.length < 60)

async function loadAll() {
  loading.value = true
  try {
    const [t, ts, wl] = await Promise.all([
      api.backtest.get(props.id),
      api.backtest.trades(props.id),
      api.watchlist().catch(() => null)
    ])
    task.value = t
    trades.value = ts
    if (wl?.data) {
      const m = new Map<string, string>()
      wl.data.forEach(it => m.set(it.code, it.name))
      stockNameMap.value = m
    }
    if (t.status !== 'DONE') {
      ElMessage.warning('回测尚未完成，部分指标可能为空')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '加载报告失败')
  } finally {
    loading.value = false
    await nextTick()
    renderCharts()
  }
}

function stockLabel(code: string, fallback?: string | null): string {
  // 优先用回测交易自带的 stockName（严格池模式里有些股票已退市，运行时 watchlist 拿不到）
  if (fallback && fallback.trim() && fallback !== code) return fallback
  return stockNameMap.value.get(code) || code
}

function renderCharts() {
  renderEquityChart()
  renderHeatmap()
}

function renderEquityChart() {
  if (!equityEl.value) return
  if (equityChart.value) equityChart.value.dispose()
  equityChart.value = echarts.init(equityEl.value)
  const c = readChartColors()
  const eq = equityPoints.value
  const bm = benchmarkPoints.value
  const initial = Number(task.value?.initialBalance || 0)

  // 用 equityCurve 的日期为 X 轴；基准曲线按同日期对齐
  const dates = eq.map(p => p.date)
  const eqVals = eq.map(p => Number(p.equity))
  const dateToEq = new Map<string, number>()
  eq.forEach(p => dateToEq.set(p.date, Number(p.equity)))
  const benchByDate = new Map<string, number>()
  bm.forEach(p => benchByDate.set(p.date, Number(p.equity)))
  const bmVals = dates.map(d => benchByDate.has(d) ? benchByDate.get(d)! : null)

  // 买卖点：把每笔成交的位置投到 equity 曲线 y 值上
  const buyPoints: any[] = []
  const sellPoints: any[] = []
  trades.value.forEach(t => {
    const y = dateToEq.get(t.tradeDate)
    if (y == null) return
    const point = {
      name: t.side,
      coord: [t.tradeDate, y],
      value: t.side === 'BUY' ? 'B' : 'S',
      itemStyle: { color: t.side === 'BUY' ? c.up : c.down },
      label: {
        show: true,
        color: '#fff',
        fontWeight: 700,
        fontSize: 10,
        formatter: t.side === 'BUY' ? 'B' : 'S'
      },
      symbol: 'triangle',
      symbolSize: 16,
      symbolRotate: t.side === 'BUY' ? 0 : 180
    }
    if (t.side === 'BUY') buyPoints.push(point); else sellPoints.push(point)
  })

  const series: any[] = [
    {
      name: '策略净值',
      type: 'line',
      data: eqVals,
      smooth: true,
      showSymbol: false,
      lineStyle: { color: c.primary, width: 2 },
      areaStyle: { color: c.primary + '22' },
      markPoint: {
        symbol: 'triangle',
        symbolSize: 14,
        data: [...buyPoints, ...sellPoints]
      },
      markLine: {
        silent: true,
        symbol: 'none',
        data: [{
          yAxis: initial,
          lineStyle: { color: c.textMuted, type: 'dashed' },
          label: { formatter: '初始 ¥' + (initial / 10000).toFixed(0) + '万', color: c.textMuted }
        }]
      }
    }
  ]
  if (hasBenchmark.value) {
    series.push({
      name: '沪深300 基准',
      type: 'line',
      data: bmVals,
      connectNulls: true,
      smooth: true,
      showSymbol: false,
      lineStyle: { color: c.textMuted, width: 1.5, type: 'dashed' }
    })
  }

  equityChart.value.setOption({
    backgroundColor: 'transparent',
    textStyle: { color: c.textRegular },
    legend: {
      data: hasBenchmark.value ? ['策略净值', '沪深300 基准'] : ['策略净值'],
      textStyle: { color: c.textRegular },
      right: 20, top: 6
    },
    grid: { top: 50, left: 80, right: 30, bottom: 50 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: c.tooltipBg,
      borderColor: c.primary,
      textStyle: { color: c.textRegular },
      valueFormatter: (v: any) => v == null ? '-' : '¥' + Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 })
    },
    xAxis: {
      type: 'category', data: dates, boundaryGap: false,
      axisLine: { lineStyle: { color: c.border } },
      axisLabel: { fontSize: 11, color: c.textMuted }
    },
    yAxis: {
      type: 'value', scale: true,
      splitLine: { lineStyle: { color: c.borderLight } },
      axisLine: { lineStyle: { color: c.border } },
      axisLabel: { color: c.textMuted, formatter: (v: number) => '¥' + (v / 10000).toFixed(0) + '万' }
    },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 12 }],
    series
  })
}

function renderHeatmap() {
  if (!heatmapEl.value) return
  if (heatmapChart.value) heatmapChart.value.dispose()
  const months = monthlyPoints.value
  if (months.length === 0) return
  heatmapChart.value = echarts.init(heatmapEl.value)
  const c = readChartColors()

  // 解析年份并构造数据矩阵
  const years = Array.from(new Set(months.map(m => m.ym.slice(0, 4)))).sort()
  const monthLabels = ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月']
  const data: [number, number, number][] = []
  let maxAbs = 0
  for (const m of months) {
    const year = m.ym.slice(0, 4)
    const month = Number(m.ym.slice(5, 7)) - 1
    const yIdx = years.indexOf(year)
    if (yIdx < 0 || month < 0 || month > 11) continue
    const val = Number(m.returnPct)
    data.push([month, yIdx, val])
    if (Math.abs(val) > maxAbs) maxAbs = Math.abs(val)
  }
  if (maxAbs === 0) maxAbs = 1

  heatmapChart.value.setOption({
    backgroundColor: 'transparent',
    textStyle: { color: c.textRegular },
    tooltip: {
      backgroundColor: c.tooltipBg,
      borderColor: c.primary,
      textStyle: { color: c.textRegular },
      formatter: (params: any) => {
        const [mIdx, yIdx, val] = params.value
        return `${years[yIdx]}-${monthLabels[mIdx]}<br/>收益: <b style="color:${val > 0 ? c.up : (val < 0 ? c.down : c.textRegular)}">${val > 0 ? '+' : ''}${val.toFixed(2)}%</b>`
      }
    },
    grid: { top: 20, left: 60, right: 30, bottom: 60 },
    xAxis: {
      type: 'category', data: monthLabels,
      splitArea: { show: true },
      axisLine: { lineStyle: { color: c.border } },
      axisLabel: { color: c.textMuted }
    },
    yAxis: {
      type: 'category', data: years,
      splitArea: { show: true },
      axisLine: { lineStyle: { color: c.border } },
      axisLabel: { color: c.textMuted }
    },
    visualMap: {
      min: -maxAbs, max: maxAbs,
      calculable: true,
      orient: 'horizontal', left: 'center', bottom: 8,
      textStyle: { color: c.textRegular },
      inRange: { color: [c.down, '#e5e5e5', c.up] }
    },
    series: [{
      type: 'heatmap',
      data,
      label: {
        show: true,
        color: '#222',
        fontWeight: 600,
        formatter: (params: any) => {
          const v = params.value[2]
          return (v > 0 ? '+' : '') + v.toFixed(1) + '%'
        }
      },
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.4)' } }
    }]
  })
}

watch(() => theme.mode.value, () => { renderCharts() })

onMounted(loadAll)
onBeforeUnmount(() => {
  equityChart.value?.dispose()
  heatmapChart.value?.dispose()
})

const fmtPct = (v: number | null | undefined, withSign = false) => {
  if (v == null) return '-'
  const n = Number(v)
  const s = n.toFixed(2) + '%'
  return withSign && n > 0 ? '+' + s : s
}
const fmtNum = (v: number | null | undefined, digits = 2) => {
  if (v == null) return '-'
  return Number(v).toFixed(digits)
}
const fmtMoney = (v: number | null | undefined) => {
  if (v == null) return '-'
  return '¥' + Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 })
}

function valClass(v: number | null | undefined) {
  if (v == null) return ''
  const n = Number(v)
  if (n > 0) return 'up'
  if (n < 0) return 'down'
  return ''
}

function sellPnl(row: BacktestTradeVO): { amt: number; pct: number } | null {
  if (row.side !== 'SELL' || row.costPrice == null || !row.amount) return null
  const cost = Number(row.costPrice)
  const sell = Number(row.price)
  if (!cost) return null
  return { amt: (sell - cost) * row.amount, pct: (sell - cost) / cost * 100 }
}

function back() {
  router.push('/my-trader')
}
</script>

<template>
  <div class="backtest-report" v-loading="loading">
    <div class="page-head" v-if="task">
      <el-button @click="back" size="small" plain>← 返回</el-button>
      <div class="title-block">
        <h2>
          回测报告 · {{ task.traderName }}
          <el-tag size="small" :type="task.status === 'DONE' ? 'success' : (task.status === 'FAILED' ? 'danger' : 'info')">
            {{ task.status }}
          </el-tag>
        </h2>
        <div class="meta">
          策略 {{ task.strategyType }} ({{ task.strategyParams }}) · 区间 {{ task.startDate }} ~ {{ task.endDate }}
          · 初始 {{ fmtMoney(task.initialBalance) }} · 任务 #{{ task.id }}
        </div>
      </div>
    </div>

    <div v-if="shortWindow" class="warn-bar">
      ⚠ 回测窗口仅 {{ equityPoints.length }} 个交易日（&lt; 60），夏普 / 索提诺 / 年化等指标样本不足，仅供参考。
    </div>

    <div class="metrics-grid">
      <div class="metric">
        <div class="label">总收益</div>
        <div class="value" :class="valClass(task?.totalReturnPct)">
          {{ (task?.totalReturnPct ?? 0) >= 0 ? '+' : '' }}{{ fmtPct(task?.totalReturnPct) }}
        </div>
      </div>
      <div class="metric">
        <div class="label">年化收益</div>
        <div class="value" :class="valClass(task?.annualReturnPct)">
          {{ (task?.annualReturnPct ?? 0) >= 0 ? '+' : '' }}{{ fmtPct(task?.annualReturnPct) }}
        </div>
      </div>
      <div class="metric">
        <div class="label">最大回撤</div>
        <div class="value down">{{ fmtPct(task?.maxDrawdownPct) }}</div>
      </div>
      <div class="metric">
        <div class="label">夏普比率</div>
        <div class="value" :class="valClass(task?.sharpeRatio)">{{ fmtNum(task?.sharpeRatio) }}</div>
      </div>

      <div class="metric">
        <div class="label">索提诺</div>
        <div class="value" :class="valClass(task?.sortinoRatio)">{{ fmtNum(task?.sortinoRatio) }}</div>
      </div>
      <div class="metric">
        <div class="label">Calmar</div>
        <div class="value" :class="valClass(task?.calmarRatio)">{{ fmtNum(task?.calmarRatio) }}</div>
      </div>
      <div class="metric">
        <div class="label">胜率</div>
        <div class="value">{{ fmtPct(task?.winRatePct) }}</div>
      </div>
      <div class="metric">
        <div class="label">盈亏比</div>
        <div class="value">{{ fmtNum(task?.profitLossRatio) }}</div>
      </div>
    </div>

    <el-card shadow="never" class="chart-card">
      <template #header>
        <span>资金曲线 {{ hasBenchmark ? '· 沪深300 基准对比 · 买卖点' : '· 买卖点（未取得沪深300 基准数据）' }}</span>
      </template>
      <div ref="equityEl" class="chart-equity"></div>
    </el-card>

    <el-card shadow="never" class="chart-card">
      <template #header>
        <span>月度收益热力图</span>
      </template>
      <div v-if="monthlyPoints.length === 0" class="empty-hint">暂无月度数据</div>
      <div v-else ref="heatmapEl" class="chart-heatmap"></div>
    </el-card>

    <el-card shadow="never" class="chart-card">
      <template #header>
        <span>成交明细（共 {{ trades.length }} 笔）</span>
      </template>
      <el-table :data="trades" stripe height="400" empty-text="无成交">
        <el-table-column prop="tradeDate" label="日期" width="120" />
        <el-table-column label="证券" width="160">
          <template #default="{ row }">
            <div class="stock-cell">
              <span class="stock-name">{{ stockLabel(row.stockCode, row.stockName) }}</span>
              <span class="stock-code">{{ row.stockCode }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="方向" width="80">
          <template #default="{ row }">
            <el-tag :type="row.side === 'BUY' ? 'danger' : 'success'" size="small">{{ row.side }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="数量" width="100" align="right" />
        <el-table-column label="成交价" width="110" align="right">
          <template #default="{ row }">¥{{ Number(row.price).toFixed(3) }}</template>
        </el-table-column>
        <el-table-column label="成本价" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.side === 'SELL' && row.costPrice != null">¥{{ Number(row.costPrice).toFixed(3) }}</span>
            <span v-else class="dash">-</span>
          </template>
        </el-table-column>
        <el-table-column label="盈亏" width="160" align="right">
          <template #default="{ row }">
            <template v-if="sellPnl(row)">
              <div :class="sellPnl(row)!.amt >= 0 ? 'up' : 'down'" class="pnl-cell">
                <div class="pnl-amt">{{ sellPnl(row)!.amt >= 0 ? '+' : '' }}{{ fmtMoney(sellPnl(row)!.amt) }}</div>
                <div class="pnl-pct">{{ sellPnl(row)!.pct >= 0 ? '+' : '' }}{{ sellPnl(row)!.pct.toFixed(2) }}%</div>
              </div>
            </template>
            <span v-else class="dash">-</span>
          </template>
        </el-table-column>
        <el-table-column width="160" align="right">
          <template #header>
            <el-tooltip content="该笔成交后的可用现金（不含冻结资金与持仓市值）" placement="top">
              <span>剩余现金 <span class="info-mark">ⓘ</span></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">¥{{ Number(row.balanceAfter).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="信号" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.backtest-report {
  padding: 16px;
  height: 100%;
  overflow: auto;
  background: var(--brand-bg);
}
.page-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
}
.title-block h2 {
  margin: 0;
  font-size: 18px;
  color: var(--brand-text-primary);
  display: flex;
  align-items: center;
  gap: 10px;
}
.title-block .meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--brand-text-secondary);
}
.warn-bar {
  background: rgba(245, 158, 11, 0.12);
  border-left: 3px solid #f59e0b;
  color: var(--brand-text-regular);
  padding: 8px 14px;
  margin-bottom: 14px;
  border-radius: 4px;
  font-size: 13px;
}
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.metric {
  padding: 14px 18px;
  background: var(--brand-surface);
  border: 1px solid var(--brand-border);
  border-radius: 6px;
  text-align: center;
}
.metric .label {
  color: var(--brand-text-secondary);
  font-size: 12px;
  margin-bottom: 6px;
}
.metric .value {
  font-size: 20px;
  font-weight: 700;
  color: var(--brand-text-primary);
}
.up { color: var(--brand-up); }
.down { color: var(--brand-down); }
.stock-cell { display: flex; flex-direction: column; gap: 2px; line-height: 1.2; }
.stock-name { color: var(--brand-text-primary); font-size: 13px; font-weight: 500; }
.stock-code { color: var(--brand-text-placeholder); font-size: 11px; font-family: 'Consolas', 'Monaco', monospace; }
.pnl-cell { line-height: 1.2; }
.pnl-amt { font-weight: 600; font-variant-numeric: tabular-nums; }
.pnl-pct { font-size: 11px; opacity: 0.85; font-variant-numeric: tabular-nums; }
.dash { color: var(--brand-text-placeholder); }
.info-mark { color: var(--brand-text-placeholder); font-size: 11px; margin-left: 2px; cursor: help; }
.chart-card {
  margin-bottom: 16px;
  background: var(--brand-surface);
  border: 1px solid var(--brand-border);
}
.chart-equity { width: 100%; height: 360px; }
.chart-heatmap { width: 100%; height: 240px; }
.empty-hint {
  text-align: center;
  color: var(--brand-text-placeholder);
  padding: 40px 0;
  font-size: 13px;
}
@media (max-width: 1100px) {
  .metrics-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
