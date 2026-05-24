<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { api, type BacktestTaskVO, type BacktestTradeVO, type TraderVO } from '@/api'
import { readChartColors } from '@/composables/chartTheme'
import { useTheme } from '@/composables/theme'

const props = defineProps<{ open: boolean; trader: TraderVO | null }>()
const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>()
const theme = useTheme()
const router = useRouter()

function dateNDaysAgo(n: number): string {
  const d = new Date(Date.now() - n * 86400000)
  return d.toISOString().slice(0, 10)
}

const startDate = ref(dateNDaysAgo(90))
const endDate = ref(dateNDaysAgo(1))
const initialBalance = ref(1000000)

type Phase = 'form' | 'running' | 'done' | 'failed'
const phase = ref<Phase>('form')
const task = ref<BacktestTaskVO | null>(null)
const trades = ref<BacktestTradeVO[]>([])
const stockNameMap = ref<Map<string, string>>(new Map())
const recentTasks = ref<BacktestTaskVO[]>([])
let pollTimer: any = null

const chartEl = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)

function close() {
  stopPolling()
  emit('update:open', false)
}

function openFullReport() {
  if (task.value?.id) {
    emit('update:open', false)
    router.push(`/backtest/${task.value.id}`)
  }
}

function reset() {
  phase.value = 'form'
  task.value = null
  trades.value = []
  stopPolling()
}

watch(() => props.open, (v) => {
  if (v) {
    reset()
    startDate.value = dateNDaysAgo(90)
    endDate.value = dateNDaysAgo(1)
    initialBalance.value = props.trader?.initialBalance ?? 1000000
    api.watchlist().then(wl => {
      const m = new Map<string, string>()
      wl.data.forEach(it => m.set(it.code, it.name))
      stockNameMap.value = m
    }).catch(() => { /* 自选股取不到不影响回测，列里就回退到代码 */ })
    loadRecent()
  }
})

async function loadRecent() {
  if (!props.trader) { recentTasks.value = []; return }
  try {
    const all = await api.backtest.list()
    recentTasks.value = all.filter(t => t.traderId === props.trader!.id).slice(0, 5)
  } catch {
    recentTasks.value = []
  }
}

function openHistoryReport(id: number) {
  emit('update:open', false)
  router.push(`/backtest/${id}`)
}

function stockLabel(code: string): string {
  return stockNameMap.value.get(code) || code
}

async function submit() {
  if (!props.trader) return
  if (!startDate.value || !endDate.value) {
    ElMessage.warning('请填写起止日期'); return
  }
  if (startDate.value >= endDate.value) {
    ElMessage.warning('起始日期必须早于结束日期'); return
  }
  if (initialBalance.value <= 0) {
    ElMessage.warning('初始资金必须大于 0'); return
  }
  try {
    const t = await api.backtest.create({
      traderId: props.trader.id,
      startDate: startDate.value,
      endDate: endDate.value,
      initialBalance: initialBalance.value
    })
    task.value = t
    phase.value = 'running'
    startPolling()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!task.value) return
    try {
      const t = await api.backtest.get(task.value.id)
      task.value = t
      if (t.status === 'DONE') {
        stopPolling()
        trades.value = await api.backtest.trades(t.id)
        phase.value = 'done'
        await nextTick()
        renderChart()
      } else if (t.status === 'FAILED') {
        stopPolling()
        phase.value = 'failed'
      }
    } catch {
      // 容错继续
    }
  }, 2000)
}

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

onBeforeUnmount(() => {
  stopPolling()
  chart.value?.dispose()
})

watch(() => theme.mode.value, () => { if (task.value?.equityCurveJson) renderChart() })

function renderChart() {
  if (!chartEl.value || !task.value?.equityCurveJson) return
  if (chart.value) chart.value.dispose()
  chart.value = echarts.init(chartEl.value)
  let points: { date: string; equity: string }[] = []
  try { points = JSON.parse(task.value.equityCurveJson || '[]') } catch { points = [] }
  const dates = points.map(p => p.date)
  const eqs = points.map(p => Number(p.equity))
  const initial = Number(task.value.initialBalance)
  const c = readChartColors()
  chart.value.setOption({
    backgroundColor: 'transparent',
    textStyle: { color: c.textRegular },
    grid: { top: 30, left: 70, right: 30, bottom: 36 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: c.tooltipBg,
      borderColor: c.primary,
      textStyle: { color: c.textRegular },
      valueFormatter: (v: any) => '¥' + Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 })
    },
    xAxis: { type: 'category', data: dates, boundaryGap: false, axisLine: { lineStyle: { color: c.border } }, axisLabel: { fontSize: 11, color: c.textMuted } },
    yAxis: {
      type: 'value', scale: true,
      splitLine: { lineStyle: { color: c.borderLight } },
      axisLine: { lineStyle: { color: c.border } },
      axisLabel: { color: c.textMuted, formatter: (v: number) => '¥' + (v / 10000).toFixed(0) + '万' }
    },
    series: [{
      name: '净值', type: 'line', data: eqs, smooth: true, showSymbol: false,
      lineStyle: { color: c.primary, width: 2 },
      areaStyle: { color: c.primary + '26' },
      markLine: {
        silent: true, symbol: 'none',
        data: [{ yAxis: initial, lineStyle: { color: c.textMuted, type: 'dashed' },
          label: { formatter: '初始 ¥' + (initial / 10000).toFixed(0) + '万', color: c.textMuted } }]
      }
    }]
  })
}

const fmtPct = (v: number | null | undefined) => v == null ? '-' : Number(v).toFixed(2) + '%'
const fmtMoney = (v: number | null | undefined) => v == null ? '-' : '¥' + Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 })

function sellPnl(row: BacktestTradeVO): { amt: number; pct: number } | null {
  if (row.side !== 'SELL' || row.costPrice == null || !row.amount) return null
  const cost = Number(row.costPrice)
  const sell = Number(row.price)
  if (!cost) return null
  return { amt: (sell - cost) * row.amount, pct: (sell - cost) / cost * 100 }
}
</script>

<template>
  <el-dialog :model-value="open" @update:model-value="close"
             :title="`回测 - ${trader?.name || ''}`" width="900px"
             :close-on-click-modal="false" destroy-on-close>
    <div v-if="phase === 'form'">
      <div v-if="recentTasks.length" class="recent-block">
        <div class="recent-title">最近回测（点击查看完整报告）</div>
        <div class="recent-list">
          <div v-for="t in recentTasks" :key="t.id" class="recent-row" @click="openHistoryReport(t.id)">
            <span class="r-date">{{ t.startDate }} ~ {{ t.endDate }}</span>
            <el-tag size="small"
                    :type="t.status === 'DONE' ? 'success' : (t.status === 'FAILED' ? 'danger' : 'info')">
              {{ t.status }}
            </el-tag>
            <span class="r-return"
                  :class="(t.totalReturnPct ?? 0) >= 0 ? 'up' : 'down'">
              {{ t.totalReturnPct == null ? '-' : ((t.totalReturnPct >= 0 ? '+' : '') + Number(t.totalReturnPct).toFixed(2) + '%') }}
            </span>
            <span class="r-trades">{{ t.totalTrades ?? 0 }} 笔</span>
            <span class="r-time">{{ t.createdAt }}</span>
            <span class="r-arrow">→</span>
          </div>
        </div>
        <el-divider />
      </div>
      <el-form label-width="100px">
        <el-form-item label="策略">
          <el-tag :type="trader?.strategyType === 'LLM' ? 'warning' : 'info'">{{ trader?.strategyType }}</el-tag>
          <span v-if="trader?.strategyType === 'MA'" class="strategy-meta">
            MA{{ trader?.maShort }}/{{ trader?.maLong }}
          </span>
          <span v-else-if="trader?.strategyType === 'LLM'" class="strategy-meta">
            {{ trader?.llmModel || 'LLM' }} · 决策回放
          </span>
        </el-form-item>
        <el-form-item v-if="trader?.strategyType === 'LLM'" label=" ">
          <div class="llm-notice">
            <strong>📼 LLM 回测使用「决策回放」模式</strong><br>
            读取 trader 在窗口内已经落库的真实 LLM 决策（<code>llm_decision_memory</code>），
            按 <code>price_at_decision</code> 作限价 + 次日 open 撮合，重演资金曲线。<br>
            <span class="muted">如果窗口内 trader 还没有任何决策记录，回测会失败；请先让 trader 在开市时段运行几天积累决策。</span>
          </div>
        </el-form-item>
        <el-form-item label="起始日期">
          <el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="endDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="初始资金">
          <el-input-number v-model="initialBalance" :min="10000" :max="100000000" :step="100000" :precision="2" />
        </el-form-item>
        <el-form-item label=" ">
          <span class="hint">
            ⚠ 回测使用预拉的最近 300 根日 K，建议日期范围在最近 1 年内。<br>
            撮合规则：信号次日按开盘价成交，不计手续费/滑点。
          </span>
        </el-form-item>
      </el-form>
    </div>

    <div v-else-if="phase === 'running'" class="running-wrap">
      <div class="running-title">
        回测进行中…（{{ startDate }} ~ {{ endDate }}）
      </div>
      <el-progress :percentage="task?.progress || 0" :stroke-width="18" />
      <div class="running-meta">
        任务 ID: {{ task?.id }} / 状态: {{ task?.status }}
      </div>
    </div>

    <div v-else-if="phase === 'done'">
      <div class="metrics">
        <div class="metric">
          <div class="label">最终净值</div>
          <div class="value">{{ fmtMoney(task?.finalEquity) }}</div>
        </div>
        <div class="metric">
          <div class="label">总收益</div>
          <div class="value" :class="(task?.totalReturnPct || 0) >= 0 ? 'up' : 'down'">
            {{ (task?.totalReturnPct || 0) >= 0 ? '+' : '' }}{{ fmtPct(task?.totalReturnPct) }}
          </div>
        </div>
        <div class="metric">
          <div class="label">最大回撤</div>
          <div class="value down">{{ fmtPct(task?.maxDrawdownPct) }}</div>
        </div>
        <div class="metric">
          <div class="label">成交笔数</div>
          <div class="value">{{ task?.totalTrades ?? 0 }}</div>
        </div>
      </div>

      <div ref="chartEl" class="chart"></div>

      <el-divider content-position="left">成交明细（{{ trades.length }} 笔）</el-divider>
      <el-table :data="trades" stripe height="240" empty-text="无成交">
        <el-table-column prop="tradeDate" label="日期" width="120" />
        <el-table-column label="股票" min-width="150">
          <template #default="{ row }">
            <div class="stock-cell">
              <span class="stock-name">{{ stockLabel(row.stockCode) }}</span>
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
        <el-table-column label="成交价" width="100" align="right">
          <template #default="{ row }">¥{{ Number(row.price).toFixed(3) }}</template>
        </el-table-column>
        <el-table-column label="成本价" width="100" align="right">
          <template #default="{ row }">
            <span v-if="row.side === 'SELL' && row.costPrice != null">¥{{ Number(row.costPrice).toFixed(3) }}</span>
            <span v-else class="dash">-</span>
          </template>
        </el-table-column>
        <el-table-column label="盈亏" width="150" align="right">
          <template #default="{ row }">
            <template v-if="sellPnl(row)">
              <div :class="sellPnl(row)!.amt >= 0 ? 'up' : 'down'">
                <div class="pnl-amt">{{ sellPnl(row)!.amt >= 0 ? '+' : '' }}{{ fmtMoney(sellPnl(row)!.amt) }}</div>
                <div class="pnl-pct">{{ sellPnl(row)!.pct >= 0 ? '+' : '' }}{{ sellPnl(row)!.pct.toFixed(2) }}%</div>
              </div>
            </template>
            <span v-else class="dash">-</span>
          </template>
        </el-table-column>
        <el-table-column width="140" align="right">
          <template #header>
            <el-tooltip content="该笔成交后的可用现金（不含冻结资金与持仓市值）" placement="top">
              <span>剩余现金 <span class="info-mark">ⓘ</span></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">¥{{ Number(row.balanceAfter).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="信号" show-overflow-tooltip />
      </el-table>
    </div>

    <div v-else-if="phase === 'failed'" class="failed-wrap">
      <el-alert :title="`回测失败: ${task?.error || '未知错误'}`" type="error" :closable="false" />
    </div>

    <template #footer>
      <template v-if="phase === 'form'">
        <el-button @click="close">取消</el-button>
        <el-button type="primary" @click="submit">开始回测</el-button>
      </template>
      <template v-else-if="phase === 'running'">
        <el-button @click="close">后台运行</el-button>
      </template>
      <template v-else-if="phase === 'failed'">
        <el-button @click="reset">返回</el-button>
        <el-button type="primary" @click="close">关闭</el-button>
      </template>
      <template v-else>
        <el-button type="primary" @click="openFullReport">查看完整报告 →</el-button>
        <el-button @click="close">关闭</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint { color: var(--brand-text-placeholder); font-size: 12px; line-height: 1.6; }
.strategy-meta { margin-left: 12px; color: var(--brand-text-secondary); font-size: 13px; }
.running-wrap { padding: 24px 0; text-align: center; }
.running-title { margin-bottom: 16px; color: var(--brand-text-secondary); }
.running-meta { margin-top: 16px; color: var(--brand-text-placeholder); font-size: 12px; }
.failed-wrap { padding: 24px; }
.metrics { display: flex; gap: 12px; margin-bottom: 16px; }
.metric { flex: 1; padding: 12px 16px; background: var(--brand-bg); border-radius: 6px; text-align: center; }
.metric .label { color: var(--brand-text-secondary); font-size: 12px; margin-bottom: 4px; }
.metric .value { font-size: 18px; font-weight: 700; color: var(--brand-text-primary); }
.up { color: var(--brand-up); }
.down { color: var(--brand-down); }
.chart { width: 100%; height: 280px; margin-bottom: 8px; }
.stock-cell { display: flex; flex-direction: column; gap: 2px; line-height: 1.2; }
.stock-name { color: var(--brand-text-primary); font-size: 13px; font-weight: 500; }
.stock-code { color: var(--brand-text-placeholder); font-size: 11px; font-family: 'Consolas', 'Monaco', monospace; }
.recent-block { margin-bottom: 8px; }
.recent-title { color: var(--brand-text-secondary); font-size: 12px; margin-bottom: 8px; }
.recent-list { display: flex; flex-direction: column; gap: 4px; }
.recent-row {
  display: grid;
  grid-template-columns: 1fr 70px 80px 70px 1fr 16px;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: var(--brand-bg);
  border: 1px solid var(--brand-border);
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.recent-row:hover { border-color: var(--brand-primary); background: var(--brand-surface); }
.recent-row .r-return { text-align: right; font-weight: 600; font-variant-numeric: tabular-nums; }
.recent-row .r-trades { text-align: right; color: var(--brand-text-secondary); }
.recent-row .r-time { color: var(--brand-text-placeholder); font-size: 11px; text-align: right; }
.recent-row .r-arrow { color: var(--brand-text-placeholder); }
.pnl-amt { font-weight: 600; font-variant-numeric: tabular-nums; line-height: 1.2; }
.pnl-pct { font-size: 11px; opacity: 0.85; line-height: 1.2; font-variant-numeric: tabular-nums; }
.dash { color: var(--brand-text-placeholder); }
.info-mark { color: var(--brand-text-placeholder); font-size: 11px; margin-left: 2px; cursor: help; }
.llm-notice {
  padding: 10px 12px;
  background: color-mix(in srgb, var(--brand-primary) 8%, transparent);
  border-left: 3px solid var(--brand-primary);
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--brand-text-regular);
}
.llm-notice code { background: var(--brand-surface); padding: 1px 4px; border-radius: 3px; font-size: 11px; }
.llm-notice .muted { color: var(--brand-text-secondary); }
</style>
