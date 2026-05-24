<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type LlmActivity, type LlmActivityPhase, type TraderVO } from '@/api'
import {
  PHASE_LABEL,
  PHASE_COLOR,
  decisionDurationMs,
  decisionStateIcon,
  extractSymbolActions,
  fmtDateTime,
  isRunning,
  isResultOk,
  mergeEventInto,
  parsePrompt,
  type DecisionGroup,
  type SymbolAction
} from '@/utils/llmActivity'

defineOptions({ name: 'LlmActivity' })

const props = defineProps<{ traderId: number }>()
const router = useRouter()

// ---------- trader 元信息 ----------
const trader = ref<TraderVO | null>(null)

async function loadTrader() {
  try {
    const all = await api.trade.traders()
    trader.value = all.find(t => t.id === props.traderId && t.strategyType === 'LLM') || null
    if (!trader.value) {
      ElMessage.warning('该 trader 不存在或不是 LLM 类型')
      router.replace({ name: 'llm-activity' })
    }
  } catch (e: any) {
    console.warn('[llm-detail] loadTrader failed', e?.message)
  }
}

// ---------- 决策流 ----------
const decisionsMap = ref<Map<number, DecisionGroup>>(new Map())
const connectionState = ref<'connecting' | 'open' | 'closed'>('closed')
const loadingHistory = ref(false)
const HISTORY_LIMIT = 500

const allDecisions = computed<DecisionGroup[]>(() =>
  Array.from(decisionsMap.value.values()).sort((a, b) => b.decisionId - a.decisionId))

// ---------- 筛选：仅保留时间窗口 ----------
type TimeWindow = '1h' | '24h' | '7d' | 'all'
const timeWindow = ref<TimeWindow>('24h')

function windowMillis(): number {
  switch (timeWindow.value) {
    case '1h':  return 3600_000
    case '24h': return 86400_000
    case '7d':  return 7 * 86400_000
    default:    return Number.POSITIVE_INFINITY
  }
}

function decisionInTimeWindow(d: DecisionGroup): boolean {
  if (timeWindow.value === 'all') return true
  const ts = Date.parse(d.startedAt.replace(' ', 'T'))
  if (Number.isNaN(ts)) return true
  return Date.now() - ts <= windowMillis()
}

const decisionsByWindow = computed(() => allDecisions.value.filter(decisionInTimeWindow))

// ---------- 展开状态 ----------
const expandedCycles = ref<Set<number>>(new Set())
const expandedPrompts = reactive<Record<number, boolean>>({})    // 默认 false
const expandedReasoning = reactive<Record<number, boolean>>({})  // 默认 true（getter 判断）

function isCycleExpanded(id: number): boolean {
  return expandedCycles.value.has(id)
}
function toggleCycle(id: number) {
  const s = new Set(expandedCycles.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  expandedCycles.value = s
}
function isReasoningExpanded(id: number): boolean {
  // 默认展开
  return expandedReasoning[id] !== false
}
function toggleReasoning(id: number) {
  expandedReasoning[id] = !isReasoningExpanded(id)
}
function isPromptExpanded(id: number): boolean {
  return expandedPrompts[id] === true
}
function togglePrompt(id: number) {
  expandedPrompts[id] = !isPromptExpanded(id)
}

// ---------- KPI ----------
const kpi = computed(() => {
  const list = decisionsByWindow.value
  let success = 0, failed = 0, cancelled = 0, running = 0
  let placeOrderCalls = 0, placeOrderOk = 0
  for (const d of list) {
    if (d.phase === 'final') success++
    else if (d.phase === 'failed') failed++
    else if (d.phase === 'cancelled') cancelled++
    else if (isRunning(d.phase)) running++

    for (const e of d.events) {
      if (e.phase !== 'tool_result' || e.toolName !== 'place_order') continue
      placeOrderCalls++
      if (isResultOk(e)) placeOrderOk++
    }
  }
  return {
    total: list.length,
    success, failed, cancelled, running,
    placeOrderCalls, placeOrderOk,
    placeOrderRate: placeOrderCalls > 0 ? (placeOrderOk * 100 / placeOrderCalls) : null
  }
})

// ---------- SSE ----------
let es: EventSource | null = null
let reconnectTimer: any = null
let reconnectDelay = 1500

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
      if (ev.phase === 'purged') {
        if (ev.traderId == null || ev.traderId === props.traderId) {
          decisionsMap.value = new Map()
          expandedCycles.value = new Set()
        }
        return
      }
      mergeEventInto(decisionsMap.value, ev, 50)
      decisionsMap.value = new Map(decisionsMap.value)
      if (isRunning(ev.phase)) {
        const s = new Set(expandedCycles.value)
        s.add(ev.decisionId)
        expandedCycles.value = s
      }
    } catch (err) {
      console.warn('[llm-detail] parse event failed', err)
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
  if (es) { es.close(); es = null }
}

function fullStop() {
  closeEs()
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  connectionState.value = 'closed'
}

async function loadHistory() {
  loadingHistory.value = true
  try {
    const rows = await api.llm.activities(props.traderId, HISTORY_LIMIT)
    const fresh = new Map<number, DecisionGroup>()
    for (const ev of rows) mergeEventInto(fresh, ev, HISTORY_LIMIT)
    decisionsMap.value = fresh
    const top = allDecisions.value[0]
    expandedCycles.value = new Set(top ? [top.decisionId] : [])
  } catch (e: any) {
    ElMessage.warning('加载历史失败: ' + (e?.message || e))
  } finally {
    loadingHistory.value = false
  }
}

// ---------- 操作 ----------
async function onCancel() {
  try {
    const r = await api.llm.cancel(props.traderId)
    if (!r.requested) ElMessage.info(r.reason || '当前没有正在运行的决策')
    else ElMessage.success('已发送中断请求')
  } catch (e: any) {
    ElMessage.error('中断请求失败: ' + (e?.response?.data?.message || e?.message))
  }
}

async function clearMyHistory() {
  if (!trader.value) return
  try {
    await ElMessageBox.confirm(
      `清空 ${trader.value.name} 的所有 LLM 活动记录？\n` +
      '· 数据库中已结束的决策会被永久删除（不可恢复）\n' +
      '· 正在进行的决策会继续运行，剩余事件仍会产生',
      '确认清空',
      { confirmButtonText: '确认清空', cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return }
  try {
    const r = await api.llm.clearForTrader(props.traderId)
    ElMessage.success(`已清空 ${r.deleted} 条活动记录`)
    decisionsMap.value = new Map()
    expandedCycles.value = new Set()
  } catch (e: any) {
    ElMessage.error('清空失败: ' + (e?.response?.data?.message || e?.message))
  }
}

const canCancel = computed(() => {
  const top = allDecisions.value[0]
  return top && top.phase !== 'cancel_requested' && isRunning(top.phase)
})

async function manualRefresh() {
  await loadHistory()
}

function backToDashboard() {
  router.push({ name: 'llm-activity' })
}

// ---------- 生命周期 ----------
watch(() => props.traderId, async () => {
  fullStop()
  decisionsMap.value = new Map()
  expandedCycles.value = new Set()
  await loadTrader()
  await loadHistory()
  connect()
})

onMounted(async () => {
  await loadTrader()
  await loadHistory()
  connect()
})

onActivated(() => {
  if (connectionState.value === 'closed') connect()
})

onDeactivated(() => fullStop())

onBeforeUnmount(() => fullStop())

// ---------- 渲染辅助 ----------
function phaseChipType(p: LlmActivityPhase) {
  return PHASE_COLOR[p]
}

function fmtDuration(ms: number | null): string {
  if (ms == null) return '-'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(1)} s (${ms} ms)`
}

function fmtAction(a: SymbolAction): string {
  const sideLabel = a.side === 'BUY' ? '买入' : a.side === 'SELL' ? '卖出' : a.side
  const amount = a.amount != null ? `${a.amount} 股` : ''
  const price = a.price ? `@¥${a.price}` : '@现价'
  return `${sideLabel} ${amount} ${price}`.replace(/\s+/g, ' ').trim()
}

function actionsOf(d: DecisionGroup): SymbolAction[] {
  return extractSymbolActions(d)
}

function promptOf(d: DecisionGroup) {
  return parsePrompt(d.promptJson)
}

function durationOf(d: DecisionGroup): number | null {
  return decisionDurationMs(d)
}
</script>

<template>
  <div class="llm-detail">
    <!-- 顶部 -->
    <div class="section-header">
      <div class="left">
        <el-button size="small" plain @click="backToDashboard">← 返回驾驶舱</el-button>
        <template v-if="trader">
          <span class="dot" :class="connectionState"></span>
          <span class="trader-title">{{ trader.name }}</span>
          <el-tag size="small">{{ trader.llmModel || 'LLM' }}</el-tag>
          <span class="conn-state">{{
            connectionState === 'open' ? '已连接' :
            connectionState === 'connecting' ? '连接中…' : '已断开'
          }}</span>
        </template>
      </div>
      <div class="right">
        <el-button v-if="canCancel" size="small" type="warning" plain @click="onCancel">停手</el-button>
        <el-button size="small" plain :loading="loadingHistory" @click="manualRefresh">刷新</el-button>
        <el-button size="small" type="danger" plain @click="clearMyHistory">清空此 trader 记录</el-button>
      </div>
    </div>

    <!-- KPI 区 -->
    <div class="kpi-bar">
      <div class="kpi-card">
        <div class="kpi-label">总决策数</div>
        <div class="kpi-value">{{ kpi.total }}</div>
        <div class="kpi-sub">窗口: {{ timeWindow === 'all' ? '全部' : timeWindow }}</div>
      </div>
      <div class="kpi-card success">
        <div class="kpi-label">成功</div>
        <div class="kpi-value">{{ kpi.success }}</div>
        <div class="kpi-sub">{{ kpi.total ? ((kpi.success * 100 / kpi.total).toFixed(0) + '%') : '-' }}</div>
      </div>
      <div class="kpi-card warn">
        <div class="kpi-label">失败</div>
        <div class="kpi-value">{{ kpi.failed }}</div>
        <div class="kpi-sub">{{ kpi.total ? ((kpi.failed * 100 / kpi.total).toFixed(0) + '%') : '-' }}</div>
      </div>
      <div class="kpi-card info">
        <div class="kpi-label">中断</div>
        <div class="kpi-value">{{ kpi.cancelled }}</div>
        <div class="kpi-sub">{{ kpi.total ? ((kpi.cancelled * 100 / kpi.total).toFixed(0) + '%') : '-' }}</div>
      </div>
      <div class="kpi-card brand">
        <div class="kpi-label">下单成功率</div>
        <div class="kpi-value">{{ kpi.placeOrderRate == null ? '-' : (kpi.placeOrderRate.toFixed(0) + '%') }}</div>
        <div class="kpi-sub">{{ kpi.placeOrderOk }}/{{ kpi.placeOrderCalls }} 笔</div>
      </div>
      <div v-if="kpi.running > 0" class="kpi-card running">
        <div class="kpi-label">运行中</div>
        <div class="kpi-value">{{ kpi.running }}</div>
        <div class="kpi-sub">正在决策</div>
      </div>
    </div>

    <!-- 筛选 -->
    <div class="filter-bar">
      <span class="filter-label">时间</span>
      <el-radio-group v-model="timeWindow" size="small">
        <el-radio-button label="1h">1 小时</el-radio-button>
        <el-radio-button label="24h">24 小时</el-radio-button>
        <el-radio-button label="7d">7 天</el-radio-button>
        <el-radio-button label="all">全部</el-radio-button>
      </el-radio-group>
      <span class="filter-summary">显示 {{ decisionsByWindow.length }} / {{ allDecisions.length }} 个周期</span>
    </div>

    <!-- 周期卡片列表 -->
    <div class="cycles-wrap">
      <div v-if="loadingHistory && allDecisions.length === 0" class="empty-main">加载历史中…</div>
      <div v-else-if="allDecisions.length === 0" class="empty-main">
        {{ trader?.name || '此 trader' }} 尚无活动记录。下一次调度或手动决策时会自动显示。
      </div>
      <div v-else-if="decisionsByWindow.length === 0" class="empty-main">
        当前时间窗口下没有可显示的周期。试试改大时间窗口。
      </div>
      <div v-else class="cycle-list">
        <div
          v-for="d in decisionsByWindow"
          :key="d.decisionId"
          class="cycle-card"
          :class="[d.phase, { running: isRunning(d.phase), expanded: isCycleExpanded(d.decisionId) }]"
        >
          <!-- 标题行 -->
          <div class="cc-header" @click="toggleCycle(d.decisionId)">
            <div class="cc-badge" :class="d.phase">
              <span class="cc-icon">{{ decisionStateIcon(d) }}</span>
            </div>
            <div class="cc-main">
              <div class="cc-line1">
                <span class="cc-id">周期 #{{ d.decisionId }}</span>
                <span class="cc-time">{{ fmtDateTime(d.startedAt) }}</span>
                <el-tag :type="phaseChipType(d.phase)" size="small" round effect="light">
                  {{ PHASE_LABEL[d.phase] }}
                </el-tag>
                <span class="cc-steps">{{ d.events.length }} 步</span>
              </div>
            </div>
            <span class="cc-toggle">{{ isCycleExpanded(d.decisionId) ? '▼' : '▶' }}</span>
          </div>

          <!-- 折叠状态：只显示 symbol 动作行 -->
          <div v-if="!isCycleExpanded(d.decisionId)" class="cc-collapsed-body">
            <div v-if="actionsOf(d).length === 0" class="no-actions">本周期无下单</div>
            <div v-else class="action-rows compact">
              <div
                v-for="a in actionsOf(d)"
                :key="a.key"
                class="action-row"
                :class="{ ok: a.ok && !a.pending, err: !a.ok && !a.pending, pending: a.pending }"
              >
                <span class="ar-icon">{{ a.pending ? '⏳' : (a.ok ? '✓' : '✗') }}</span>
                <span class="ar-code">{{ a.code }}</span>
                <el-tag
                  :type="a.side === 'BUY' ? 'danger' : (a.side === 'SELL' ? 'success' : 'info')"
                  size="small" effect="plain"
                >{{ a.side || '?' }}</el-tag>
                <span class="ar-amount">{{ fmtAction(a) }}</span>
              </div>
            </div>
          </div>

          <!-- 展开状态 -->
          <div v-else class="cc-expanded-body">
            <!-- 子卡 1: 输入提示 -->
            <div class="sub-card prompt-card">
              <div class="sub-head" @click="togglePrompt(d.decisionId)">
                <span class="sub-icon">📝</span>
                <span class="sub-title">输入提示</span>
                <span class="sub-toggle">{{ isPromptExpanded(d.decisionId) ? '▼ 收起' : '▶ 展开' }}</span>
              </div>
              <div v-if="isPromptExpanded(d.decisionId)" class="sub-body prompt-body">
                <template v-if="promptOf(d)">
                  <div class="prompt-block">
                    <div class="prompt-block-label">system</div>
                    <pre class="prompt-text">{{ promptOf(d)!.system || '(空)' }}</pre>
                  </div>
                  <div class="prompt-block">
                    <div class="prompt-block-label">user</div>
                    <pre class="prompt-text">{{ promptOf(d)!.user || '(空)' }}</pre>
                  </div>
                </template>
                <div v-else class="prompt-empty">（旧数据，未保存输入提示）</div>
              </div>
            </div>

            <!-- 子卡 2: AI 思维链分析 -->
            <div class="sub-card reasoning-card">
              <div class="sub-head" @click="toggleReasoning(d.decisionId)">
                <span class="sub-icon">🧠</span>
                <span class="sub-title">AI 思维链分析</span>
                <span class="sub-toggle">{{ isReasoningExpanded(d.decisionId) ? '▼ 收起' : '▶ 展开' }}</span>
              </div>
              <div v-if="isReasoningExpanded(d.decisionId)" class="sub-body reasoning-body">
                <pre v-if="d.finalMessage" class="reasoning-text">{{ d.finalMessage }}</pre>
                <div v-else-if="isRunning(d.phase)" class="reasoning-pending">
                  <span class="pending-dot"></span>正在思考中…
                </div>
                <div v-else class="reasoning-empty">（暂无）</div>
              </div>
            </div>

            <!-- stats 行 -->
            <div class="stats-row">
              <span class="stat-item">
                <span class="stat-label">AI call duration:</span>
                <span class="stat-value">{{ fmtDuration(durationOf(d)) }}</span>
              </span>
              <span class="stat-item">
                <span class="stat-label">下单:</span>
                <span class="stat-value">{{ actionsOf(d).length }} 笔</span>
              </span>
            </div>

            <!-- per-symbol 动作行 -->
            <div v-if="actionsOf(d).length > 0" class="action-rows">
              <div
                v-for="a in actionsOf(d)"
                :key="a.key"
                class="action-row"
                :class="{ ok: a.ok && !a.pending, err: !a.ok && !a.pending, pending: a.pending }"
              >
                <span class="ar-icon">{{ a.pending ? '⏳' : (a.ok ? '✓' : '✗') }}</span>
                <span class="ar-code">{{ a.code }}</span>
                <el-tag
                  :type="a.side === 'BUY' ? 'danger' : (a.side === 'SELL' ? 'success' : 'info')"
                  size="small" effect="plain"
                >{{ a.side || '?' }}</el-tag>
                <span class="ar-amount">{{ fmtAction(a) }}</span>
                <span class="ar-label" :class="{ err: !a.ok && !a.pending }">{{ a.label }}</span>
              </div>
            </div>
            <div v-else class="no-actions">本周期无下单</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.llm-detail {
  height: 100%;
  display: flex; flex-direction: column;
  background: var(--brand-bg);
  padding: 12px;
  gap: 12px;
  overflow-y: auto;
}

/* ---------- 顶部 ---------- */
.section-header {
  padding: 10px 14px;
  background: var(--brand-surface); border-radius: 8px;
  display: flex; align-items: center; justify-content: space-between;
  box-shadow: var(--brand-shadow-sm);
  flex-shrink: 0;
}
.section-header .left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.section-header .right { display: flex; gap: 6px; }
.section-header .trader-title { font-weight: 600; font-size: 15px; color: var(--brand-text-primary); }
.section-header .conn-state { font-size: 11px; color: var(--brand-text-secondary); }
.section-header .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--brand-text-placeholder); }
.section-header .dot.open { background: #34d399; }
.section-header .dot.connecting { background: #fbbf24; }
.section-header .dot.closed { background: #f87171; }

/* ---------- KPI ---------- */
.kpi-bar { display: flex; gap: 12px; flex-wrap: wrap; flex-shrink: 0; }
.kpi-card {
  flex: 1; min-width: 130px;
  background: var(--brand-surface); border-radius: 8px;
  padding: 10px 14px;
  box-shadow: var(--brand-shadow-sm);
  border-left: 4px solid var(--brand-text-placeholder);
}
.kpi-card.success { border-left-color: #34d399; }
.kpi-card.warn { border-left-color: var(--brand-up); }
.kpi-card.info { border-left-color: #fbbf24; }
.kpi-card.brand { border-left-color: var(--brand-primary); }
.kpi-card.running { border-left-color: #fbbf24; background: rgba(251, 191, 36, 0.10); }
.kpi-label { font-size: 12px; color: var(--brand-text-secondary); }
.kpi-value { font-size: 22px; font-weight: 700; margin-top: 2px; font-family: Consolas, monospace; color: var(--brand-text-primary); }
.kpi-sub { font-size: 11px; color: var(--brand-text-placeholder); margin-top: 2px; }

/* ---------- 筛选 ---------- */
.filter-bar {
  padding: 10px 14px;
  background: var(--brand-bg-soft); border-radius: 8px;
  display: flex; gap: 12px; align-items: center; flex-wrap: wrap;
  font-size: 12px;
  flex-shrink: 0;
}
.filter-label { color: var(--brand-text-secondary); font-weight: 500; }
.filter-summary { color: var(--brand-text-placeholder); margin-left: auto; }

/* ---------- 周期卡片容器 ---------- */
.cycles-wrap {
  background: var(--brand-surface); border-radius: 8px;
  overflow: hidden;
  box-shadow: var(--brand-shadow-sm);
  display: flex; flex-direction: column;
  flex-shrink: 0;
}
.cycle-list {
  padding: 12px 14px;
  display: flex; flex-direction: column;
  gap: 12px;
}

/* ---------- 周期卡片 ---------- */
.cycle-card {
  background: var(--brand-surface);
  border-radius: 10px;
  border: 1px solid var(--brand-border);
  box-shadow: var(--brand-shadow-sm);
  transition: box-shadow 0.18s, border-color 0.18s;
  overflow: hidden;
}
.cycle-card:hover {
  box-shadow: var(--brand-shadow-md);
  border-color: #cbd5e1;
}
.cycle-card.running {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 1px var(--brand-primary), var(--brand-shadow-md);
}
.cycle-card.failed   { border-color: rgba(248, 113, 113, 0.45); }
.cycle-card.final    { border-color: rgba(52, 211, 153, 0.45); }
.cycle-card.cancelled,
.cycle-card.cancel_requested { border-color: rgba(251, 191, 36, 0.45); }

/* 标题行 */
.cc-header {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 16px;
  cursor: pointer;
  transition: background 0.15s;
}
.cc-header:hover { background: var(--brand-bg-soft); }
.cycle-card.running .cc-header {
  background: var(--el-color-primary-light-9);
}
.cc-badge {
  width: 36px; height: 36px;
  border-radius: 50%;
  background: var(--brand-bg);
  border: 2px solid var(--brand-border);
  display: flex; align-items: center; justify-content: center;
  font-size: 16px;
  flex-shrink: 0;
  transition: background 0.2s, border-color 0.2s;
}
.cc-badge.started,
.cc-badge.tool_call,
.cc-badge.tool_result,
.cc-badge.cancel_requested {
  background: var(--el-color-primary-light-9); border-color: var(--brand-primary);
  animation: badgePulse 1.6s ease-in-out infinite;
}
.cc-badge.final     { background: var(--el-color-primary-light-9); border-color: var(--brand-primary); }
.cc-badge.failed    { background: rgba(248, 113, 113, 0.15); border-color: var(--brand-up); }
.cc-badge.cancelled { background: rgba(251, 191, 36, 0.15); border-color: #fbbf24; }
@keyframes badgePulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(59, 130, 246, 0.45); }
  50%      { box-shadow: 0 0 0 8px rgba(59, 130, 246, 0); }
}

.cc-main { flex: 1; min-width: 0; }
.cc-line1 {
  display: flex; align-items: center; gap: 10px;
  flex-wrap: wrap;
}
.cc-id {
  font-weight: 600; color: var(--brand-text-primary);
  font-size: 14px;
  font-family: Consolas, monospace;
  letter-spacing: 0.3px;
}
.cc-time {
  color: var(--brand-text-secondary); font-size: 12px;
  font-family: Consolas, monospace;
}
.cc-steps {
  color: var(--brand-text-placeholder); font-size: 12px;
}
.cc-toggle {
  color: var(--brand-text-placeholder);
  font-size: 12px;
  flex-shrink: 0;
  font-family: Consolas, monospace;
}

/* 折叠态主体 */
.cc-collapsed-body {
  padding: 8px 16px 14px;
  border-top: 1px solid var(--brand-border-light);
}
.no-actions {
  padding: 10px 0;
  color: var(--brand-text-placeholder);
  font-size: 12px;
  font-style: italic;
}

/* 展开态主体 */
.cc-expanded-body {
  padding: 4px 16px 16px;
  border-top: 1px solid var(--brand-border-light);
  display: flex; flex-direction: column;
  gap: 10px;
}

/* ---------- 子卡 ---------- */
.sub-card {
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid var(--brand-border);
}
.prompt-card { background: var(--brand-bg); }
.reasoning-card { background: var(--brand-primary-soft); border-color: var(--brand-primary); }

.sub-head {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  transition: background 0.15s;
}
.prompt-card .sub-head:hover { background: var(--brand-bg-soft); }
.reasoning-card .sub-head:hover { background: var(--brand-primary-soft); }
.sub-icon { font-size: 14px; }
.sub-title { flex: 1; color: var(--brand-text-regular); }
.reasoning-card .sub-title { color: var(--brand-primary); }
.sub-toggle {
  color: var(--brand-text-placeholder); font-size: 11px;
  font-family: Consolas, monospace;
}

.sub-body { padding: 0 12px 12px; }

/* prompt 子内容 */
.prompt-body { display: flex; flex-direction: column; gap: 8px; }
.prompt-block-label {
  font-size: 11px; color: var(--brand-text-secondary);
  font-weight: 600;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  margin-bottom: 4px;
}
.prompt-text {
  background: var(--brand-bg-soft);
  border: 1px solid var(--brand-border);
  border-radius: 4px;
  padding: 8px 10px;
  margin: 0;
  font-family: Consolas, Menlo, monospace;
  font-size: 11px;
  line-height: 1.55;
  color: var(--brand-text-regular);
  white-space: pre-wrap;
  word-break: break-word;
}
.prompt-empty {
  color: var(--brand-text-placeholder);
  font-size: 12px;
  font-style: italic;
  padding: 4px 0;
}

/* reasoning 子内容 */
.reasoning-text {
  background: var(--brand-bg);
  border: 1px solid var(--brand-primary);
  border-radius: 4px;
  padding: 10px 12px;
  margin: 0;
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.7;
  color: var(--brand-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.reasoning-pending {
  display: flex; align-items: center; gap: 8px;
  color: var(--brand-primary);
  font-size: 13px;
}
.reasoning-empty {
  color: var(--brand-text-placeholder);
  font-size: 12px;
  font-style: italic;
}
.pending-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  background: var(--brand-primary);
  animation: dotBlink 1s ease-in-out infinite alternate;
}
@keyframes dotBlink {
  from { opacity: 0.3; }
  to   { opacity: 1; }
}

/* ---------- stats 行 ---------- */
.stats-row {
  display: flex; gap: 18px;
  padding: 4px 4px 0;
  font-size: 12px;
  flex-wrap: wrap;
}
.stat-item {
  display: inline-flex; align-items: center; gap: 6px;
}
.stat-label { color: var(--brand-text-secondary); }
.stat-value {
  color: var(--brand-text-primary);
  font-family: Consolas, monospace;
  font-weight: 500;
}

/* ---------- 动作行 ---------- */
.action-rows {
  display: flex; flex-direction: column;
  gap: 6px;
}
.action-rows.compact { gap: 4px; }
.action-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px;
  background: var(--brand-surface);
  border: 1px solid var(--brand-border);
  border-radius: 6px;
  font-size: 12px;
  transition: border-color 0.15s, background 0.15s;
}
.action-row.ok      { border-color: rgba(52, 211, 153, 0.30); background: rgba(52, 211, 153, 0.08); }
.action-row.err     { border-color: rgba(248, 113, 113, 0.30); background: rgba(248, 113, 113, 0.08); }
.action-row.pending { border-color: rgba(251, 191, 36, 0.30); background: rgba(251, 191, 36, 0.08); }
.ar-icon {
  width: 16px; text-align: center;
  font-family: Consolas, monospace;
  flex-shrink: 0;
}
.action-row.ok .ar-icon  { color: #34d399; }
.action-row.err .ar-icon { color: #f87171; }
.action-row.pending .ar-icon { color: #fbbf24; }
.ar-code {
  font-weight: 600;
  color: var(--brand-text-primary);
  font-family: Consolas, monospace;
  letter-spacing: 0.5px;
  flex-shrink: 0;
}
.ar-amount {
  color: var(--brand-text-regular);
  font-family: Consolas, monospace;
  flex-shrink: 0;
}
.ar-label {
  flex: 1;
  color: var(--brand-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
.ar-label.err { color: #f87171; }

.empty-main { padding: 60px 20px; text-align: center; color: var(--brand-text-placeholder); font-size: 13px; }
</style>
