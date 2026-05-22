<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type LlmActivity, type LlmActivityPhase } from '@/api'

const props = defineProps<{ traderId: number }>()

interface DecisionGroup {
  decisionId: number
  events: LlmActivity[]
  phase: LlmActivityPhase
  startedAt: string
  finalMessage: string
}

const decisionsMap = ref<Map<number, DecisionGroup>>(new Map())
const connectionState = ref<'connecting' | 'open' | 'closed'>('closed')
const expandedIds = ref<number[]>([])

let es: EventSource | null = null
let reconnectTimer: any = null
let reconnectDelay = 1500

const decisions = computed<DecisionGroup[]>(() => {
  return Array.from(decisionsMap.value.values()).sort((a, b) => b.decisionId - a.decisionId)
})
const currentDecision = computed<DecisionGroup | null>(() => {
  for (const d of decisions.value) {
    if (['started', 'tool_call', 'tool_result', 'cancel_requested'].includes(d.phase)) return d
  }
  return null
})
const canCancel = computed(() =>
  currentDecision.value && currentDecision.value.phase !== 'cancel_requested'
)

function mergeEvent(ev: LlmActivity) {
  let g = decisionsMap.value.get(ev.decisionId)
  if (!g) {
    g = {
      decisionId: ev.decisionId,
      events: [],
      phase: ev.phase,
      startedAt: ev.createdAt,
      finalMessage: ''
    }
    decisionsMap.value.set(ev.decisionId, g)
    // 默认展开最新决策
    if (!expandedIds.value.includes(ev.decisionId)) {
      expandedIds.value = [ev.decisionId, ...expandedIds.value.slice(0, 1)]
    }
  }
  // 去重:同 decisionId+seq 不重复加(回放可能重复)
  if (g.events.some(e => e.seq === ev.seq)) return
  g.events.push(ev)
  g.events.sort((a, b) => a.seq - b.seq)
  g.phase = ev.phase
  if (ev.phase === 'final' || ev.phase === 'failed' || ev.phase === 'cancelled') {
    g.finalMessage = ev.message || ''
  }

  // 内存上限:只保留最近 5 个决策
  if (decisionsMap.value.size > 5) {
    const ids = Array.from(decisionsMap.value.keys()).sort((a, b) => a - b)
    while (decisionsMap.value.size > 5) {
      const oldest = ids.shift()
      if (oldest != null) decisionsMap.value.delete(oldest)
    }
  }
}

async function loadHistory() {
  try {
    const rows = await api.llm.activities(props.traderId, 100)
    decisionsMap.value.clear()
    rows.forEach(mergeEvent)
  } catch (e: any) {
    console.warn('[llm-panel] load history failed', e?.message)
  }
}

function connect() {
  closeEs()
  connectionState.value = 'connecting'
  es = new EventSource(api.llm.streamUrl(props.traderId))
  es.onopen = () => {
    connectionState.value = 'open'
    reconnectDelay = 1500
  }
  es.onmessage = (e) => {
    try {
      const ev = JSON.parse(e.data) as LlmActivity
      mergeEvent(ev)
    } catch (err) {
      console.warn('[llm-panel] parse event failed', err)
    }
  }
  es.onerror = () => {
    connectionState.value = 'closed'
    closeEs()
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = setTimeout(connect, reconnectDelay)
    reconnectDelay = Math.min(reconnectDelay * 2, 30000)
  }
}

function closeEs() {
  if (es) {
    es.close()
    es = null
  }
}

function fullStop() {
  closeEs()
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  connectionState.value = 'closed'
}

async function onCancel() {
  if (!canCancel.value) return
  try {
    const r = await api.llm.cancel(props.traderId)
    if (!r.requested) {
      ElMessage.info(r.reason || '当前没有正在运行的决策')
    } else {
      ElMessage.success('已发送中断请求')
    }
  } catch (e: any) {
    ElMessage.error('中断请求失败: ' + (e?.response?.data?.message || e?.message))
  }
}

function clearDisplay() {
  decisionsMap.value.clear()
  expandedIds.value = []
}

watch(() => props.traderId, async (newId) => {
  if (!newId) return
  fullStop()
  decisionsMap.value.clear()
  expandedIds.value = []
  await loadHistory()
  connect()
})

onMounted(async () => {
  await loadHistory()
  connect()
})
onActivated(() => {
  if (connectionState.value === 'closed' && !es) connect()
})
onDeactivated(() => fullStop())
onBeforeUnmount(() => fullStop())

// ---------- UI 辅助 ----------
const phaseLabel: Record<LlmActivityPhase, string> = {
  started: '开始',
  tool_call: '调用工具',
  tool_result: '工具返回',
  final: '完成',
  failed: '失败',
  cancel_requested: '中断中…',
  cancelled: '已中断'
}
function phaseColor(p: LlmActivityPhase) {
  return ({
    started: 'primary',
    tool_call: 'warning',
    tool_result: 'success',
    final: 'info',
    failed: 'danger',
    cancel_requested: 'warning',
    cancelled: 'info'
  } as const)[p]
}
function phaseIcon(p: LlmActivityPhase) {
  return ({
    started: '▶',
    tool_call: '→',
    tool_result: '✓',
    final: '✓✓',
    failed: '✗',
    cancel_requested: '⏸',
    cancelled: '⏹'
  } as const)[p]
}
function fmtTime(s: string): string {
  return s.replace('T', ' ').replace(/\.\d+$/, '').slice(11, 19)
}
function fmtArgs(json: string | null): string {
  if (!json) return ''
  try { return JSON.stringify(JSON.parse(json), null, 2) }
  catch { return json }
}

// ---------- 人话摘要 ----------
const TOOL_LABELS: Record<string, string> = {
  get_stock_analysis: '股票分析',
  get_minute_chart: '分时图',
  get_recent_trades: '历史成交',
  place_order: '下单'
}
const TOOL_ICONS: Record<string, string> = {
  get_stock_analysis: '🔍',
  get_minute_chart: '📈',
  get_recent_trades: '📋',
  place_order: '💰'
}
function toolLabel(name: string | null): string {
  if (!name) return ''
  return TOOL_LABELS[name] || name
}
function toolIcon(name: string | null): string {
  if (!name) return ''
  return TOOL_ICONS[name] || '🔧'
}
function pct(v: any): string {
  if (v == null) return '-'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return (n >= 0 ? '+' : '') + n.toFixed(2) + '%'
}
function safeParse(json: string | null): any {
  if (!json) return null
  try { return JSON.parse(json) } catch { return null }
}
function argsSummary(e: LlmActivity): string {
  if (e.phase !== 'tool_call') return ''
  const a = safeParse(e.argsJson)
  if (!a) return ''
  switch (e.toolName) {
    case 'get_stock_analysis':
      return `分析 ${a.code || '?'}`
    case 'get_minute_chart':
      return `查 ${a.code || '?'} 分时`
    case 'get_recent_trades':
      return `查最近 ${a.limit || 20} 笔成交`
    case 'place_order': {
      const side = a.side === 'BUY' ? '买入' : a.side === 'SELL' ? '卖出' : a.side
      const price = a.price ? `@¥${a.price}` : '@现价'
      return `${side} ${a.code || '?'} ${a.amount || 0} 股 ${price}`
    }
    default:
      return JSON.stringify(a).slice(0, 120)
  }
}
function resultSummary(e: LlmActivity): string {
  if (e.phase !== 'tool_result') return ''
  const r = safeParse(e.resultJson)
  if (!r) return ''
  if (r.ok === false || r.error) return `失败: ${r.error || r.message || '未知错误'}`
  switch (e.toolName) {
    case 'get_stock_analysis': {
      const parts: string[] = []
      if (r.name) parts.push(r.name)
      if (r.current_price != null) parts.push(`现价 ¥${r.current_price}`)
      if (r.ma5 != null && r.ma10 != null && r.ma20 != null) parts.push(`MA5=${r.ma5} MA10=${r.ma10} MA20=${r.ma20}`)
      const dyn: string[] = []
      if (r.change_5d_pct != null) dyn.push(`5日 ${pct(r.change_5d_pct)}`)
      if (r.change_20d_pct != null) dyn.push(`20日 ${pct(r.change_20d_pct)}`)
      if (r.vol_ratio_vs_5d_avg != null) dyn.push(`量比 ${r.vol_ratio_vs_5d_avg}`)
      if (dyn.length) parts.push(dyn.join(' / '))
      return parts.join(' · ')
    }
    case 'get_minute_chart':
      return `${r.name || r.code || ''} - ${r.samples?.length || 0} 个采样`
    case 'get_recent_trades':
      return `共 ${r.count || 0} 笔历史成交`
    case 'place_order':
      if (r.ok) return `挂单成功 #${r.order_id} (${r.status || ''}) ${r.message || ''}`.trim()
      return `挂单失败: ${r.error || '未知'}`
    default:
      return JSON.stringify(r).slice(0, 200)
  }
}
function isResultOk(e: LlmActivity): boolean {
  if (e.phase !== 'tool_result') return true
  const r = safeParse(e.resultJson)
  if (!r) return true
  return r.ok !== false && !r.error
}
function hasRawJson(e: LlmActivity): boolean {
  return !!(e.argsJson || e.resultJson)
}
const decisionStateIcon = (d: DecisionGroup) => {
  if (['started', 'tool_call', 'tool_result'].includes(d.phase)) return '🔄'
  if (d.phase === 'cancel_requested') return '⏸'
  if (d.phase === 'cancelled') return '⏹'
  if (d.phase === 'failed') return '✗'
  return '✓'
}
</script>

<template>
  <div class="llm-panel">
    <div class="panel-header">
      <div class="title">
        <span class="dot" :class="connectionState"></span>
        LLM 活动监控
        <span class="conn-state">{{
          connectionState === 'open' ? '已连接' :
          connectionState === 'connecting' ? '连接中…' : '已断开'
        }}</span>
      </div>
      <div class="actions">
        <el-button v-if="canCancel" size="small" type="warning" plain @click="onCancel">停手</el-button>
        <el-button size="small" plain @click="clearDisplay">清空</el-button>
      </div>
    </div>

    <div v-if="decisions.length === 0" class="empty">
      暂无活动记录。LLM trader 在每分钟调度或手动决策时会在此显示过程。
    </div>

    <el-collapse v-model="expandedIds" class="decision-list">
      <el-collapse-item
        v-for="d in decisions"
        :key="d.decisionId"
        :name="d.decisionId">
        <template #title>
          <div class="decision-title">
            <span class="state-icon">{{ decisionStateIcon(d) }}</span>
            <span class="decision-id">#{{ d.decisionId }}</span>
            <span class="started-at">{{ fmtTime(d.startedAt) }}</span>
            <el-tag :type="phaseColor(d.phase)" size="small">{{ phaseLabel[d.phase] }}</el-tag>
            <span class="events-count">{{ d.events.length }} 步</span>
          </div>
        </template>
        <div class="event-timeline">
          <div v-for="(e, i) in d.events" :key="i" class="event-row" :class="[e.phase, { 'tool-event': e.toolName }]">
            <div class="event-line">
              <span class="phase-icon">{{ phaseIcon(e.phase) }}</span>
              <span v-if="e.toolName" class="tool-chip">
                <span class="tool-emoji">{{ toolIcon(e.toolName) }}</span>
                <span class="tool-label">{{ toolLabel(e.toolName) }}</span>
              </span>
              <span v-else class="phase-name">{{ phaseLabel[e.phase] }}</span>
              <span v-if="e.round != null" class="round">R{{ e.round + 1 }}</span>
              <span class="ev-time">{{ fmtTime(e.createdAt) }}</span>
            </div>
            <div v-if="argsSummary(e)" class="summary args">{{ argsSummary(e) }}</div>
            <div v-if="resultSummary(e)" class="summary result" :class="isResultOk(e) ? 'ok' : 'err'">
              {{ resultSummary(e) }}
            </div>
            <div v-if="e.message && e.phase !== 'tool_call' && e.phase !== 'tool_result'" class="msg">{{ e.message }}</div>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.llm-panel { background: #fff; border-radius: 8px; padding: 12px; height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.panel-header { display: flex; align-items: center; gap: 10px; padding-bottom: 8px; border-bottom: 1px solid #e5e7eb; margin-bottom: 8px; }
.title { display: flex; align-items: center; gap: 6px; font-weight: 600; flex: 1; }
.conn-state { font-size: 11px; color: #6b7280; font-weight: 400; margin-left: 4px; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: #9ca3af; }
.dot.open { background: #10b981; }
.dot.connecting { background: #f59e0b; }
.dot.closed { background: #ef4444; }
.actions { display: flex; gap: 6px; }
.empty { padding: 32px 12px; text-align: center; color: #9ca3af; font-size: 13px; line-height: 1.6; }
.decision-list { flex: 1; overflow-y: auto; }
.decision-title { display: flex; align-items: center; gap: 8px; font-size: 13px; width: 100%; }
.decision-title .state-icon { font-size: 14px; }
.decision-title .decision-id { font-weight: 600; color: #374151; }
.decision-title .started-at { color: #9ca3af; font-size: 11px; }
.decision-title .events-count { margin-left: auto; color: #6b7280; font-size: 11px; }
.event-timeline { padding-left: 8px; border-left: 2px solid #e5e7eb; }
.event-row { padding: 8px 0 8px 10px; position: relative; }
.event-row:not(:last-child) { border-bottom: 1px dashed #f3f4f6; }
.event-row.tool-event { background: #fafafa; border-radius: 4px; margin: 4px 0; padding: 8px 10px; }
.event-line { display: flex; align-items: center; gap: 8px; font-size: 12px; flex-wrap: wrap; }
.phase-icon { display: inline-block; width: 16px; text-align: center; font-weight: 700; font-size: 13px; }
.event-row.tool_call .phase-icon { color: #f59e0b; }
.event-row.tool_result .phase-icon { color: #10b981; }
.event-row.started .phase-icon { color: #3b82f6; }
.event-row.final .phase-icon { color: #6b7280; }
.event-row.failed .phase-icon { color: #ef4444; }
.event-row.cancelled .phase-icon, .event-row.cancel_requested .phase-icon { color: #f97316; }
.tool-chip { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px; background: #eff6ff; border-radius: 10px; color: #1d4ed8; font-weight: 500; font-size: 12px; }
.tool-emoji { font-size: 13px; }
.phase-name { color: #374151; font-weight: 500; }
.round { color: #fff; background: #9ca3af; font-size: 10px; padding: 1px 6px; border-radius: 8px; font-family: monospace; }
.ev-time { margin-left: auto; color: #9ca3af; font-size: 11px; font-family: monospace; }
.summary { margin-top: 6px; padding: 4px 8px; border-radius: 4px; font-size: 12px; line-height: 1.5; word-break: break-word; }
.summary.args { background: #fef3c7; color: #92400e; border-left: 3px solid #f59e0b; }
.summary.result { background: #d1fae5; color: #065f46; border-left: 3px solid #10b981; }
.summary.result.err { background: #fee2e2; color: #991b1b; border-left-color: #ef4444; }
.msg { margin-top: 6px; padding: 4px 8px; color: #4b5563; font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; background: #f9fafb; border-radius: 4px; }
.event-row.final .msg { background: #e0e7ff; color: #3730a3; border-left: 3px solid #6366f1; }
.event-row.failed .msg { background: #fee2e2; color: #991b1b; border-left: 3px solid #ef4444; }
.event-row.cancelled .msg, .event-row.cancel_requested .msg { background: #fed7aa; color: #9a3412; border-left: 3px solid #f97316; }
.json-block { margin-top: 4px; }
.json-block summary { cursor: pointer; color: #9ca3af; font-size: 11px; user-select: none; padding: 2px 0; }
.json-block summary:hover { color: #6b7280; }
.json-block pre { margin: 4px 0 0; padding: 6px 8px; background: #1f2937; color: #d1d5db; border-radius: 4px; font-size: 10px; line-height: 1.4; max-height: 200px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
.json-block pre b { color: #fbbf24; }
:deep(.el-collapse-item__header) { font-size: 13px; padding: 0 4px; height: 36px; }
:deep(.el-collapse-item__content) { padding: 4px 4px 12px; }
</style>
