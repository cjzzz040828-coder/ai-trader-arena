<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type LlmActivity, type LlmActivityPhase, type TraderVO } from '@/api'
import {
  PHASE_LABEL,
  decisionStateIcon,
  fmtClock,
  getCurrentDecisionSummary,
  getLatestDecisionStatus,
  groupByDecision,
  isRunning,
  mergeEventInto,
  type DecisionGroup
} from '@/utils/llmActivity'

defineOptions({ name: 'LlmActivityDashboard' })

const router = useRouter()

// ---------- 数据 ----------
const llmTraders = ref<TraderVO[]>([])
// 每个 trader 的决策 map（增量合并）
const decisionsByTrader = reactive<Record<number, Map<number, DecisionGroup>>>({})
// 每个 trader 计算出的派生展示数据
interface CardView {
  currentSummary: string | null
  latestStatus: ReturnType<typeof getLatestDecisionStatus>
  isRunning: boolean
  decisionCount: number
}
const cardViews = reactive<Record<number, CardView>>({})

const connectionState = ref<'connecting' | 'open' | 'closed'>('closed')
const loading = ref(false)

// SSE 启动时序：先订阅 → 事件入 stagingBuffer → HTTP 拉基线 → 合并去重 → 切 live
const stagingBuffer = ref<LlmActivity[]>([])
const liveMode = ref(false)

const tradersWithLlm = computed(() => llmTraders.value)

// ---------- 渲染辅助 ----------
function recomputeCard(traderId: number) {
  const map = decisionsByTrader[traderId]
  if (!map) {
    cardViews[traderId] = { currentSummary: null, latestStatus: null, isRunning: false, decisionCount: 0 }
    return
  }
  const decisions = Array.from(map.values()).sort((a, b) => b.decisionId - a.decisionId)
  cardViews[traderId] = {
    currentSummary: getCurrentDecisionSummary(decisions),
    latestStatus: getLatestDecisionStatus(decisions),
    isRunning: decisions.length > 0 && isRunning(decisions[0].phase),
    decisionCount: decisions.length
  }
}

function applyEvent(ev: LlmActivity) {
  if (ev.phase === 'purged') {
    // 系统事件：traderId 非 null = 清单 trader；null = 清所有
    if (ev.traderId) {
      decisionsByTrader[ev.traderId] = new Map()
      recomputeCard(ev.traderId)
    } else {
      for (const tid of Object.keys(decisionsByTrader)) {
        decisionsByTrader[Number(tid)] = new Map()
        recomputeCard(Number(tid))
      }
    }
    return
  }
  if (!decisionsByTrader[ev.traderId]) {
    decisionsByTrader[ev.traderId] = new Map()
  }
  const merged = mergeEventInto(decisionsByTrader[ev.traderId], ev, 20)
  if (merged) recomputeCard(ev.traderId)
}

function applyBaseline(map: Record<number, LlmActivity[]>) {
  for (const tid of Object.keys(map)) {
    const traderId = Number(tid)
    const groups = groupByDecision(map[traderId])
    const m = new Map<number, DecisionGroup>()
    for (const g of groups) m.set(g.decisionId, g)
    decisionsByTrader[traderId] = m
    recomputeCard(traderId)
  }
  // 没出现在 map 里的 trader 给空 view
  for (const t of llmTraders.value) {
    if (!cardViews[t.id]) recomputeCard(t.id)
  }
}

function applyStaging() {
  // 把订阅期间收到的事件按 (decisionId, seq) 合并去重（mergeEventInto 内部已做）
  for (const ev of stagingBuffer.value) applyEvent(ev)
  stagingBuffer.value = []
  liveMode.value = true
}

// ---------- SSE ----------
let es: EventSource | null = null
let reconnectTimer: any = null
let reconnectDelay = 1500

function connect() {
  closeEs()
  connectionState.value = 'connecting'
  es = new EventSource(api.llm.userStreamUrl())
  es.onopen = () => {
    connectionState.value = 'open'
    reconnectDelay = 1500
  }
  es.onmessage = (e) => {
    try {
      const ev = JSON.parse(e.data) as LlmActivity
      if (liveMode.value) {
        applyEvent(ev)
      } else {
        stagingBuffer.value.push(ev)
      }
    } catch (err) {
      console.warn('[llm-dashboard] parse event failed', err)
    }
  }
  es.onerror = () => {
    connectionState.value = 'closed'
    closeEs()
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = setTimeout(() => { reloadAndConnect() }, reconnectDelay)
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
  liveMode.value = false
  stagingBuffer.value = []
}

// ---------- 数据加载 ----------
async function loadTraders() {
  const all = await api.trade.traders()
  llmTraders.value = all.filter(t => t.strategyType === 'LLM')
  // 初始化每个 trader 的空 view
  for (const t of llmTraders.value) {
    if (!cardViews[t.id]) {
      cardViews[t.id] = { currentSummary: null, latestStatus: null, isRunning: false, decisionCount: 0 }
    }
  }
}

async function loadBaselineAndGoLive() {
  loading.value = true
  try {
    const recent = await api.llm.recent(20)
    applyBaseline(recent as any)
    applyStaging()  // 把订阅期间漏的事件合并进来
  } catch (e: any) {
    console.warn('[llm-dashboard] load baseline failed', e?.message)
  } finally {
    loading.value = false
  }
}

async function reloadAndConnect() {
  liveMode.value = false
  stagingBuffer.value = []
  connect()                      // 先连 SSE 进 staging
  await loadBaselineAndGoLive()  // 再拉基线 + 合并 → 切 live
}

// ---------- 操作 ----------
function gotoDetail(traderId: number) {
  router.push({ name: 'llm-activity-detail', params: { traderId } })
}

async function clearAll() {
  if (llmTraders.value.length === 0) {
    ElMessage.info('当前没有 LLM trader')
    return
  }
  try {
    await ElMessageBox.confirm(
      '清空所有 LLM trader 的活动记录？\n' +
      '· 数据库中已结束的决策会被永久删除（不可恢复）\n' +
      '· 正在进行的决策会继续运行，剩余事件仍会产生',
      '确认清空',
      { confirmButtonText: '确认清空', cancelButtonText: '取消', type: 'warning', dangerouslyUseHTMLString: false }
    )
  } catch { return }
  try {
    const r = await api.llm.clearAll()
    ElMessage.success(`已清空 ${r.deleted} 条活动记录`)
    // 本地立即清（purged 事件也会广播来，会再次幂等清一次）
    for (const t of llmTraders.value) {
      decisionsByTrader[t.id] = new Map()
      recomputeCard(t.id)
    }
  } catch (e: any) {
    ElMessage.error('清空失败: ' + (e?.response?.data?.message || e?.message))
  }
}

async function manualRefresh() {
  await loadTraders()
  await reloadAndConnect()
}

// ---------- 生命周期 ----------
onMounted(async () => {
  await loadTraders()
  await reloadAndConnect()
})

onActivated(async () => {
  // 从详情页返回：重连 SSE + 重新拉基线（补差）
  await loadTraders()
  await reloadAndConnect()
})

onDeactivated(() => {
  // 进详情页时挂起 user-SSE，避免双推 + 节省连接数
  fullStop()
})

onBeforeUnmount(() => fullStop())

// ---------- 渲染 ----------
function dotClass(t: TraderVO): string {
  const v = cardViews[t.id]
  if (!v || !v.latestStatus) return 'idle'
  if (v.isRunning) return 'running'
  const ph = v.latestStatus.phase
  if (ph === 'failed') return 'fail'
  if (ph === 'cancelled') return 'cancel'
  if (ph === 'final') return 'ok'
  return 'idle'
}

function fmtCurrency(n: number | null | undefined): string {
  if (n == null) return '-'
  return '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fmtPct(n: number | null | undefined): string {
  if (n == null) return '-'
  return (n >= 0 ? '+' : '') + n.toFixed(2) + '%'
}
</script>

<template>
  <div class="dash">
    <div class="dash-header">
      <div class="title-row">
        <span class="dot" :class="connectionState"></span>
        <h2>LLM 实时驾驶舱</h2>
        <span class="conn-state">{{
          connectionState === 'open' ? '已连接' :
          connectionState === 'connecting' ? '连接中…' : '已断开'
        }}</span>
        <span class="trader-count">· {{ llmTraders.length }} 个 LLM trader</span>
      </div>
      <div class="actions">
        <el-button size="default" plain :loading="loading" @click="manualRefresh">刷新</el-button>
        <el-button size="default" type="danger" plain @click="clearAll">清空所有记录</el-button>
      </div>
    </div>

    <div v-if="llmTraders.length === 0" class="empty">
      <div class="empty-icon">🤖</div>
      <div class="empty-text">还没有 LLM 交易员</div>
      <div class="empty-sub">
        <router-link to="/traders" class="link">去创建一个 →</router-link>
      </div>
    </div>

    <div v-else class="card-grid">
      <el-card
        v-for="t in tradersWithLlm"
        :key="t.id"
        class="trader-card"
        :class="{ 'trader-card-running': cardViews[t.id]?.isRunning }"
        shadow="hover"
        :body-style="{ padding: '16px', display: 'flex', flexDirection: 'column', height: '100%' }"
        @click="gotoDetail(t.id)"
      >
        <div class="card-head">
          <span class="status-dot" :class="dotClass(t)"></span>
          <div class="card-name">{{ t.name }}</div>
          <el-tag size="small" effect="plain">{{ t.llmModel || 'LLM' }}</el-tag>
        </div>

        <!-- 当前活动 / 最近一次 -->
        <div class="card-activity">
          <template v-if="cardViews[t.id]?.isRunning">
            <div class="thinking">
              <span class="dot-anim"></span>
              <span class="text">{{ cardViews[t.id]?.currentSummary || '正在思考…' }}</span>
            </div>
          </template>
          <template v-else-if="cardViews[t.id]?.latestStatus">
            <div class="last-result">
              <span class="state-icon">{{ cardViews[t.id]?.latestStatus?.icon }}</span>
              <span class="last-label">最近: {{ cardViews[t.id]?.latestStatus?.label }}</span>
            </div>
            <div v-if="cardViews[t.id]?.currentSummary" class="last-msg">
              {{ cardViews[t.id]?.currentSummary }}
            </div>
          </template>
          <template v-else>
            <div class="no-activity">尚无决策记录</div>
          </template>
        </div>

        <div class="card-stats">
          <div class="stat">
            <div class="stat-label">总资产</div>
            <div class="stat-value">{{ fmtCurrency(t.totalAsset) }}</div>
          </div>
          <div class="stat" :class="{ pos: t.totalProfit > 0, neg: t.totalProfit < 0 }">
            <div class="stat-label">收益</div>
            <div class="stat-value">{{ fmtPct(t.profitPct) }}</div>
          </div>
        </div>

        <div class="card-foot">
          <span class="meta">{{ cardViews[t.id]?.decisionCount || 0 }} 个决策</span>
          <span class="link-detail">查看详情 →</span>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.dash {
  height: 100%;
  display: flex; flex-direction: column;
  background: var(--brand-bg);
  padding: 16px;
  gap: 16px;
  overflow: hidden;
}

.dash-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px;
  background: var(--brand-surface); border-radius: 8px;
  box-shadow: var(--brand-shadow-sm);
}
.title-row { display: flex; align-items: center; gap: 10px; }
.title-row h2 { margin: 0; font-size: 17px; color: var(--brand-text-primary); font-weight: 600; }
.title-row .conn-state { font-size: 12px; color: var(--brand-text-secondary); }
.title-row .trader-count { font-size: 12px; color: var(--brand-text-placeholder); }
.title-row .dot {
  width: 9px; height: 9px; border-radius: 50%; background: var(--brand-text-placeholder);
}
.title-row .dot.open { background: #34d399; }
.title-row .dot.connecting { background: #fbbf24; }
.title-row .dot.closed { background: #f87171; }

.empty {
  flex: 1;
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  gap: 8px;
  color: var(--brand-text-placeholder);
}
.empty-icon { font-size: 56px; }
.empty-text { font-size: 16px; color: var(--brand-text-secondary); }
.empty .link { color: var(--brand-primary); text-decoration: none; }

.card-grid {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
  overflow-y: auto;
  padding-right: 4px;
  align-content: start;
}

.trader-card {
  border-radius: 10px;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s, border-color 0.15s;
  min-height: 200px;
}
.trader-card:hover {
  transform: translateY(-3px);
}
.trader-card.trader-card-running {
  border-color: var(--brand-primary);
}

.card-head {
  display: flex; align-items: center; gap: 8px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--brand-border-light);
  margin-bottom: 10px;
}
.card-name {
  flex: 1;
  font-size: 15px; font-weight: 600; color: var(--brand-text-primary);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.status-dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: var(--brand-text-placeholder); flex-shrink: 0;
}
.status-dot.running { background: var(--brand-primary); animation: pulse 1.2s ease-in-out infinite; }
.status-dot.ok { background: #34d399; }
.status-dot.fail { background: #f87171; }
.status-dot.cancel { background: #fbbf24; }
.status-dot.idle { background: var(--brand-border); }
@keyframes pulse {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(59, 130, 246, 0.45); }
  50%      { opacity: 0.85; box-shadow: 0 0 0 6px rgba(59, 130, 246, 0); }
}

.card-activity {
  flex: 1;
  min-height: 64px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--brand-text-regular);
}
.thinking {
  display: flex; align-items: center; gap: 8px;
  color: var(--brand-primary);
  font-weight: 500;
}
.thinking .text {
  font-size: 14px;
  flex: 1;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.dot-anim {
  display: inline-block;
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--brand-primary);
  animation: dot-bounce 0.8s ease-in-out infinite alternate;
}
@keyframes dot-bounce {
  from { transform: translateY(0); opacity: 0.6; }
  to   { transform: translateY(-4px); opacity: 1; }
}

.last-result {
  display: flex; align-items: center; gap: 6px;
  color: var(--brand-text-secondary);
}
.last-result .state-icon { font-size: 14px; }
.last-result .last-label { font-size: 13px; }
.last-msg {
  margin-top: 6px;
  font-size: 12px;
  color: var(--brand-text-regular);
  background: var(--brand-bg);
  padding: 6px 8px;
  border-radius: 4px;
  border-left: 2px solid var(--brand-border);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
.no-activity {
  color: var(--brand-text-placeholder);
  font-style: italic;
  font-size: 13px;
}

.card-stats {
  display: flex; gap: 16px;
  padding: 10px 0;
  border-top: 1px solid var(--brand-border-light);
  margin-top: 10px;
}
.stat { flex: 1; }
.stat-label { font-size: 11px; color: var(--brand-text-placeholder); }
.stat-value {
  font-size: 16px; font-weight: 600; color: var(--brand-text-primary);
  font-family: Consolas, monospace;
  margin-top: 2px;
}
.stat.pos .stat-value { color: var(--brand-up); }
.stat.neg .stat-value { color: var(--brand-down); }

.card-foot {
  display: flex; justify-content: space-between; align-items: center;
  font-size: 12px;
  padding-top: 8px;
  border-top: 1px dashed var(--brand-border-light);
}
.card-foot .meta { color: var(--brand-text-placeholder); }
.card-foot .link-detail { color: var(--brand-primary); font-weight: 500; }
.trader-card:hover .link-detail { text-decoration: underline; }
</style>
