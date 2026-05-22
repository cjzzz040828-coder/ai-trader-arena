<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { api, type BacktestTaskVO, type BacktestTradeVO, type TraderVO } from '@/api'

const props = defineProps<{ open: boolean; trader: TraderVO | null }>()
const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>()

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
let pollTimer: any = null

const chartEl = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)

function close() {
  stopPolling()
  emit('update:open', false)
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
  }
})

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

function renderChart() {
  if (!chartEl.value || !task.value?.equityCurveJson) return
  if (chart.value) chart.value.dispose()
  chart.value = echarts.init(chartEl.value)
  let points: { date: string; equity: string }[] = []
  try { points = JSON.parse(task.value.equityCurveJson || '[]') } catch { points = [] }
  const dates = points.map(p => p.date)
  const eqs = points.map(p => Number(p.equity))
  const initial = Number(task.value.initialBalance)
  chart.value.setOption({
    backgroundColor: '#fff',
    grid: { top: 30, left: 70, right: 30, bottom: 36 },
    tooltip: { trigger: 'axis', valueFormatter: (v: any) => '¥' + Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 }) },
    xAxis: { type: 'category', data: dates, boundaryGap: false, axisLabel: { fontSize: 11 } },
    yAxis: {
      type: 'value', scale: true,
      axisLabel: { formatter: (v: number) => '¥' + (v / 10000).toFixed(0) + '万' }
    },
    series: [{
      name: '净值', type: 'line', data: eqs, smooth: true, showSymbol: false,
      lineStyle: { color: '#3b82f6', width: 2 },
      areaStyle: { color: 'rgba(59,130,246,0.1)' },
      markLine: {
        silent: true, symbol: 'none',
        data: [{ yAxis: initial, lineStyle: { color: '#9ca3af', type: 'dashed' },
          label: { formatter: '初始 ¥' + (initial / 10000).toFixed(0) + '万', color: '#6b7280' } }]
      }
    }]
  })
}

const fmtPct = (v: number | null | undefined) => v == null ? '-' : Number(v).toFixed(2) + '%'
const fmtMoney = (v: number | null | undefined) => v == null ? '-' : '¥' + Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 })
</script>

<template>
  <el-dialog :model-value="open" @update:model-value="close"
             :title="`回测 - ${trader?.name || ''}`" width="900px"
             :close-on-click-modal="false" destroy-on-close>
    <div v-if="phase === 'form'">
      <el-form label-width="100px">
        <el-form-item label="策略">
          <el-tag>{{ trader?.strategyType }}</el-tag>
          <span style="margin-left: 12px; color: #6b7280; font-size: 13px;">
            MA{{ trader?.maShort }}/{{ trader?.maLong }}
          </span>
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

    <div v-else-if="phase === 'running'" style="padding: 24px 0; text-align: center;">
      <div style="margin-bottom: 16px; color: #6b7280;">
        回测进行中…（{{ startDate }} ~ {{ endDate }}）
      </div>
      <el-progress :percentage="task?.progress || 0" :stroke-width="18" />
      <div style="margin-top: 16px; color: #9ca3af; font-size: 12px;">
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
        <el-table-column prop="stockCode" label="代码" width="100" />
        <el-table-column label="方向" width="80">
          <template #default="{ row }">
            <el-tag :type="row.side === 'BUY' ? 'danger' : 'success'" size="small">{{ row.side }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="数量" width="100" align="right" />
        <el-table-column label="成交价" width="100" align="right">
          <template #default="{ row }">¥{{ Number(row.price).toFixed(3) }}</template>
        </el-table-column>
        <el-table-column label="余额" width="140" align="right">
          <template #default="{ row }">¥{{ Number(row.balanceAfter).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="信号" show-overflow-tooltip />
      </el-table>
    </div>

    <div v-else-if="phase === 'failed'" style="padding: 24px;">
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
        <el-button type="primary" @click="close">关闭</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint { color: #9ca3af; font-size: 12px; line-height: 1.6; }
.metrics { display: flex; gap: 12px; margin-bottom: 16px; }
.metric { flex: 1; padding: 12px 16px; background: #f3f4f6; border-radius: 6px; text-align: center; }
.metric .label { color: #6b7280; font-size: 12px; margin-bottom: 4px; }
.metric .value { font-size: 18px; font-weight: 700; }
.up { color: #dc2626; }
.down { color: #16a34a; }
.chart { width: 100%; height: 280px; margin-bottom: 8px; }
</style>
