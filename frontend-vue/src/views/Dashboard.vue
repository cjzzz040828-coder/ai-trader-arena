<script setup lang="ts">
import { onMounted, onBeforeUnmount, onActivated, onDeactivated, ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type SnapshotItem, type WatchlistItem, type TradeMarker, type PositionVO } from '@/api'
import { useTradeStore } from '@/stores/trade'
import OrderBook from '@/components/OrderBook.vue'
import KLineChart from '@/components/KLineChart.vue'
import MinuteChart from '@/components/MinuteChart.vue'
import LlmActivityPanel from '@/components/LlmActivityPanel.vue'

defineOptions({ name: 'Dashboard' })

const tradeStore = useTradeStore()

// Dashboard 独立的 trader 选择，跟 MyTrader 的 store.currentTrader 解耦
const dashboardTraderId = ref<number | null>(null)
const dashboardTrader = computed(() =>
  tradeStore.traders.find(t => t.id === dashboardTraderId.value) || null)
const showLlmOnDashboard = computed(() => dashboardTrader.value?.strategyType === 'LLM')

// ---------- 下单状态 ----------
const orderPrice = ref<number>(0)
const orderAmount = ref<number>(100)
const submitting = ref(false)
const canSubmit = computed(() =>
  !!dashboardTraderId.value
  && !!selectedCode.value
  && orderPrice.value > 0
  && orderAmount.value >= 100
  && orderAmount.value % 100 === 0)

// ---------- 左栏 tab + 持仓股 ----------
const leftTab = ref<'watchlist' | 'positions'>('watchlist')
const dashboardPositions = ref<PositionVO[]>([])

async function loadDashboardPositions() {
  if (!dashboardTraderId.value) {
    dashboardPositions.value = []
    return
  }
  try {
    dashboardPositions.value = await api.trade.positions(dashboardTraderId.value)
  } catch (e) {
    console.warn('[dashboard-positions]', e)
  }
}

watch(dashboardTraderId, () => loadDashboardPositions(), { immediate: false })

function positionName(code: string): string {
  return snapshots.value.find(s => s.code === code)?.name
      || tradeStore.stockNames[code]
      || code
}

const FALLBACK_WATCHLIST = ['000001', '600519', '000333', '600036', '300750']

const watchlist = ref<WatchlistItem[]>([])
const snapshots = ref<SnapshotItem[]>([])
const selectedCode = ref('')
const health = ref<any>(null)
const errMsg = ref('')
const klineFreq = ref(9)
const refreshMs = ref(1000)
const lastRefreshAt = ref('')
const stockFilter = ref('')
const sortBy = ref<'default' | 'pct-desc' | 'pct-asc' | 'price-desc' | 'price-asc' | 'amount-desc'>('default')
let timer: number | null = null

const selectedSnap = computed(() => snapshots.value.find(s => s.code === selectedCode.value) || null)
const isOpen = computed(() => health.value?.market_status === 'OPEN')

// 当前选中股票的成交点（FILLED 订单），喂给 K 线/分时图做买卖标注
const tradesForSelected = computed<TradeMarker[]>(() =>
  tradeStore.orders
    .filter(o => o.stockCode === selectedCode.value
              && o.status === 'FILLED'
              && o.filledAt
              && o.filledPrice != null)
    .map(o => ({
      filledAt: o.filledAt!,
      side: o.side,
      price: o.filledPrice!,
      amount: o.amount
    }))
)

// 应用过滤（不含排序），queryCodes 基于这个，避免排序时反复触发 refresh
const filteredWatchlist = computed(() => {
  const q = stockFilter.value.trim()
  if (!q) return watchlist.value
  return watchlist.value.filter(w => w.code.includes(q) || w.name.includes(q))
})

// 展示用（含排序）。排序依赖 snapshots 中的实时价 / 涨幅
const visibleWatchlist = computed(() => {
  const list = filteredWatchlist.value
  if (sortBy.value === 'default') return list
  const snapMap = new Map(snapshots.value.map(s => [s.code, s]))
  const get = (code: string) => snapMap.get(code)
  const sorted = [...list]
  sorted.sort((a, b) => {
    const sa = get(a.code), sb = get(b.code)
    switch (sortBy.value) {
      case 'pct-desc':    return (sb?.change_pct ?? -Infinity) - (sa?.change_pct ?? -Infinity)
      case 'pct-asc':     return (sa?.change_pct ?? Infinity) - (sb?.change_pct ?? Infinity)
      case 'price-desc':  return (sb?.price ?? -Infinity) - (sa?.price ?? -Infinity)
      case 'price-asc':   return (sa?.price ?? Infinity) - (sb?.price ?? Infinity)
      case 'amount-desc': return (sb?.amount ?? -Infinity) - (sa?.amount ?? -Infinity)
    }
    return 0
  })
  return sorted
})

// 把持仓股 code 也并入查询集，确保点击持仓时中间栏 selectedSnap 有数据
const queryCodes = computed(() => {
  const set = new Set<string>(filteredWatchlist.value.map(w => w.code))
  for (const p of dashboardPositions.value) set.add(p.stockCode)
  return Array.from(set)
})

// 持仓股：复用同一 filter 输入与 sortBy 下拉
const visiblePositions = computed(() => {
  const q = stockFilter.value.trim()
  let list = dashboardPositions.value
  if (q) {
    list = list.filter(p =>
      p.stockCode.includes(q) || (positionName(p.stockCode) || '').includes(q))
  }
  if (sortBy.value === 'default') return list
  const sorted = [...list]
  sorted.sort((a, b) => {
    switch (sortBy.value) {
      case 'pct-desc':    return (b.profitPct ?? -Infinity) - (a.profitPct ?? -Infinity)
      case 'pct-asc':     return (a.profitPct ?? Infinity) - (b.profitPct ?? Infinity)
      case 'price-desc':  return (b.currentPrice ?? -Infinity) - (a.currentPrice ?? -Infinity)
      case 'price-asc':   return (a.currentPrice ?? Infinity) - (b.currentPrice ?? Infinity)
      case 'amount-desc': return (b.marketValue ?? -Infinity) - (a.marketValue ?? -Infinity)
    }
    return 0
  })
  return sorted
})

function profitColor(pct: number | null | undefined): string {
  if (pct == null || pct === 0) return '#666'
  return pct > 0 ? '#ef4444' : '#10b981'
}

const QUOTE_BATCH = 80  // mootdx/TDX 单次 quotes 上限

async function loadWatchlist(force = false) {
  try {
    const resp = force ? await api.watchlistReload() : await api.watchlist()
    watchlist.value = resp.data || []
    if (!selectedCode.value && watchlist.value.length) {
      selectedCode.value = watchlist.value[0].code
    }
  } catch (e: any) {
    console.error('[watchlist]', e)
    // fallback: 用硬编码
    watchlist.value = FALLBACK_WATCHLIST.map(c => ({
      code: c, name: c, market: 'SH' as const, added_price: 0, added_date: ''
    }))
    if (!selectedCode.value) selectedCode.value = FALLBACK_WATCHLIST[0]
  }
}

async function refreshAll() {
  errMsg.value = ''
  if (!queryCodes.value.length) return
  try {
    // 把代码按 80 一批分块并行拉，避免 mootdx 单次上限
    const batches: string[][] = []
    for (let i = 0; i < queryCodes.value.length; i += QUOTE_BATCH) {
      batches.push(queryCodes.value.slice(i, i + QUOTE_BATCH))
    }
    // 同时拉自选股（后端有 mtime 缓存，文件未变时直接走缓存，开销忽略不计）
    const [h, wl, ...snapResults] = await Promise.all([
      api.gatewayHealth(),
      api.watchlist(),
      ...batches.map(b => api.quote(b.join(',')))
    ])
    health.value = h
    if (wl?.data?.length) watchlist.value = wl.data
    snapshots.value = snapResults.flatMap(s => s.data || [])
    lastRefreshAt.value = new Date().toLocaleTimeString()
  } catch (e: any) {
    errMsg.value = e?.message || '请求失败'
  }
  // 同步刷一次当前 trader 的订单，让买卖点跟着更新（静默，不阻塞主流程）
  tradeStore.fetchOrders().catch(() => {})
  // 顺手刷持仓 —— 撮合引擎 10s tick 后持仓数据可能变化
  loadDashboardPositions().catch(() => {})
}

const effectiveInterval = computed(() => isOpen.value ? refreshMs.value : 30000)

function setupTimer() {
  if (timer) window.clearInterval(timer)
  timer = window.setInterval(refreshAll, effectiveInterval.value)
}

watch(effectiveInterval, () => setupTimer())
watch(stockFilter, () => refreshAll())  // 过滤变了立即拉新代码的行情

function priceColor(item: SnapshotItem) {
  if (!item) return '#666'
  if (item.change > 0) return '#ef4444'
  if (item.change < 0) return '#10b981'
  return '#666'
}

function selectStock(code: string) {
  selectedCode.value = code
}

async function syncFromTHS() {
  await loadWatchlist(true)
  refreshAll()
}

const freqOptions = [
  { label: '分时', value: 8 },
  { label: '15分', value: 1 },
  { label: '60分', value: 3 },
  { label: '日K', value: 9 }
]

const refreshOptions = [
  { label: '1秒', value: 1000 },
  { label: '2秒', value: 2000 },
  { label: '3秒', value: 3000 },
  { label: '5秒', value: 5000 }
]

onMounted(async () => {
  await loadWatchlist(true)  // 首次进入强制 reload 一次，避免后端拿陈旧缓存
  // 确保 tradeStore 拉过 traders（拿到 currentTraderId）+ orders，买卖点才能渲染
  tradeStore.refreshAll().catch(() => {})
  refreshAll()
  setupTimer()
})

// traders 拉到后给 Dashboard 选一个默认 trader（优先 LLM）
watch(() => tradeStore.traders, (list) => {
  if (dashboardTraderId.value == null && list.length > 0) {
    const llm = list.find(t => t.strategyType === 'LLM')
    dashboardTraderId.value = (llm || list[0]).id
  }
}, { immediate: true })

// 切换选中股票时把现价填进下单表单
watch(selectedCode, (code) => {
  const snap = snapshots.value.find(s => s.code === code)
  if (snap?.price) orderPrice.value = Number(snap.price.toFixed(2))
})

function useCurrentPrice() {
  if (selectedSnap.value?.price) {
    orderPrice.value = Number(selectedSnap.value.price.toFixed(2))
  }
}

async function submitOrder(side: 'BUY' | 'SELL') {
  if (!dashboardTraderId.value) { ElMessage.warning('请先选 trader'); return }
  if (!selectedCode.value) { ElMessage.warning('请先在左侧选一只股票'); return }
  if (orderPrice.value <= 0) { ElMessage.warning('价格必须大于 0'); return }
  if (orderAmount.value < 100 || orderAmount.value % 100 !== 0) {
    ElMessage.warning('数量必须是 100 的整数倍')
    return
  }
  submitting.value = true
  try {
    const ord = await api.trade.placeOrder({
      traderId: dashboardTraderId.value,
      stockCode: selectedCode.value,
      side,
      price: orderPrice.value,
      amount: orderAmount.value
    })
    ElMessage.success(`${side === 'BUY' ? '买入' : '卖出'} 已挂单 #${ord.id}`)
    tradeStore.refreshAll().catch(() => {})
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '下单失败')
  } finally {
    submitting.value = false
  }
}

onActivated(() => {
  // 从 MyTrader 切回时：定时器已被 deactivated 清掉，重启 + 立即刷新一次
  if (!timer) {
    refreshAll()
    setupTimer()
  }
})

onDeactivated(() => {
  if (timer) {
    window.clearInterval(timer)
    timer = null
  }
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="dashboard">
    <!-- 顶部状态条 -->
    <div class="topbar">
      <div class="status-item">
        <span class="dot" :style="{ background: isOpen ? '#10b981' : '#9ca3af' }"></span>
        <span>市场：<b>{{ health?.market_status || '--' }}</b></span>
      </div>
      <div class="status-item">服务器：{{ (health?.server_time || '--').replace('T', ' ') }}</div>
      <div class="status-item">心跳：{{ Math.floor(health?.mootdx_last_ok_ago_seconds || 0) }}s 前</div>
      <div class="status-item">上次刷新：{{ lastRefreshAt }}</div>

      <span class="spacer"></span>

      <div v-if="tradeStore.traders.length > 0" class="status-item">
        <span>Trader:</span>
        <el-select v-model="dashboardTraderId" size="small" style="width: 150px;">
          <el-option v-for="t in tradeStore.traders" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <el-tag v-if="dashboardTrader" size="small">{{ dashboardTrader.strategyType }}</el-tag>
      </div>

      <div class="refresh-ctl">
        <span>刷新：</span>
        <select v-model.number="refreshMs">
          <option v-for="opt in refreshOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <span v-if="!isOpen" class="hint">（收盘自动降频 30s）</span>
      </div>

      <button class="refresh-btn" @click="refreshAll">手动刷新</button>

      <div v-if="errMsg" class="error">{{ errMsg }}</div>
    </div>

    <!-- 三栏主体 -->
    <div class="layout">
      <!-- 左：自选股 / 持仓股 -->
      <div class="col left">
        <div class="left-tabs">
          <button
            :class="{ tab: true, active: leftTab === 'watchlist' }"
            @click="leftTab = 'watchlist'"
          >自选股 <span class="cnt">{{ visibleWatchlist.length }}/{{ watchlist.length }}</span></button>
          <button
            :class="{ tab: true, active: leftTab === 'positions' }"
            @click="leftTab = 'positions'"
          >持仓 <span class="cnt">{{ visiblePositions.length }}/{{ dashboardPositions.length }}</span></button>
          <span class="tab-spacer"></span>
          <button v-if="leftTab === 'watchlist'" class="sync-btn" @click="syncFromTHS" title="从同花顺重新同步">↻</button>
        </div>
        <div class="filter-bar">
          <input v-model="stockFilter" placeholder="代码/名称过滤" />
          <select v-model="sortBy" title="排序">
            <option value="default">默认</option>
            <option value="pct-desc">涨幅↓</option>
            <option value="pct-asc">涨幅↑</option>
            <option value="price-desc">价格↓</option>
            <option value="price-asc">价格↑</option>
            <option value="amount-desc">成交额↓</option>
          </select>
        </div>
        <template v-if="leftTab === 'watchlist'">
          <div
            v-for="w in visibleWatchlist"
            :key="w.code"
            class="stock-item"
            :class="{ active: selectedCode === w.code }"
            @click="selectStock(w.code)"
          >
            <div class="line1">
              <span class="name">{{ w.name }}</span>
              <span class="code">{{ w.code }}</span>
            </div>
            <div class="line2" v-if="snapshots.find(s => s.code === w.code)">
              <span class="price" :style="{ color: priceColor(snapshots.find(s => s.code === w.code)!) }">
                {{ snapshots.find(s => s.code === w.code)?.price?.toFixed(2) }}
              </span>
              <span class="pct" :style="{ color: priceColor(snapshots.find(s => s.code === w.code)!) }">
                {{ (snapshots.find(s => s.code === w.code)?.change_pct || 0) >= 0 ? '+' : '' }}{{ snapshots.find(s => s.code === w.code)?.change_pct?.toFixed(2) }}%
              </span>
            </div>
            <div class="line2 muted" v-else>
              <span>加入价 {{ w.added_price }}</span>
              <span>{{ w.added_date }}</span>
            </div>
          </div>
        </template>
        <template v-else>
          <div v-if="!dashboardTraderId" class="empty-tip">请先在顶部选择一个 trader</div>
          <div v-else-if="dashboardPositions.length === 0" class="empty-tip">
            {{ dashboardTrader?.name || '该 trader' }} 暂无持仓
          </div>
          <div
            v-for="p in visiblePositions"
            :key="p.stockCode"
            class="stock-item"
            :class="{ active: selectedCode === p.stockCode }"
            @click="selectStock(p.stockCode)"
          >
            <div class="line1">
              <span class="name">{{ positionName(p.stockCode) }}</span>
              <span class="code">{{ p.stockCode }}</span>
            </div>
            <div class="line2">
              <span class="price" :style="{ color: profitColor(p.profitPct) }">
                {{ (p.currentPrice ?? 0).toFixed(2) }}
              </span>
              <span class="pct" :style="{ color: profitColor(p.profitPct) }">
                {{ (p.profitPct ?? 0) >= 0 ? '+' : '' }}{{ (p.profitPct ?? 0).toFixed(2) }}%
              </span>
            </div>
            <div class="line2 muted">
              <span>持{{ p.amount }} <span v-if="p.frozenAmount > 0" class="frozen">冻{{ p.frozenAmount }}</span></span>
              <span>成本 {{ (p.costPrice ?? 0).toFixed(2) }}</span>
            </div>
          </div>
        </template>
      </div>

      <!-- 中：K线 -->
      <div class="col center">
        <div class="header">
          <div>
            <span class="stock-name">{{ selectedSnap?.name || '--' }}</span>
            <span class="stock-code">{{ selectedCode }}</span>
            <span class="big-price" :style="{ color: selectedSnap ? priceColor(selectedSnap) : '#000' }">
              {{ selectedSnap?.price?.toFixed(2) || '--' }}
            </span>
            <span class="big-change" :style="{ color: selectedSnap ? priceColor(selectedSnap) : '#000' }">
              {{ selectedSnap?.change >= 0 ? '+' : '' }}{{ selectedSnap?.change }}
              ({{ selectedSnap?.change_pct >= 0 ? '+' : '' }}{{ selectedSnap?.change_pct }}%)
            </span>
          </div>
          <div class="freq-tabs">
            <button
              v-for="f in freqOptions"
              :key="f.value"
              :class="{ active: klineFreq === f.value }"
              @click="klineFreq = f.value"
            >{{ f.label }}</button>
          </div>
        </div>

        <MinuteChart
          v-if="klineFreq === 8"
          :code="selectedCode"
          :last-close="selectedSnap?.last_close || 0"
          :current-price="selectedSnap?.price || 0"
          :interval-ms="refreshMs"
          :trades="tradesForSelected"
        />
        <KLineChart
          v-else
          :code="selectedCode"
          :frequency="klineFreq"
          :count="240"
          :trades="tradesForSelected"
        />

        <div class="kpi-grid" v-if="selectedSnap">
          <div><label>今开</label><b>{{ selectedSnap.open?.toFixed(2) }}</b></div>
          <div><label>昨收</label><b>{{ selectedSnap.last_close?.toFixed(2) }}</b></div>
          <div><label>最高</label><b style="color:#ef4444">{{ selectedSnap.high?.toFixed(2) }}</b></div>
          <div><label>最低</label><b style="color:#10b981">{{ selectedSnap.low?.toFixed(2) }}</b></div>
          <div><label>成交量(手)</label><b>{{ selectedSnap.vol?.toLocaleString() }}</b></div>
          <div><label>成交额(亿)</label><b>{{ (selectedSnap.amount / 1e8).toFixed(2) }}</b></div>
          <div><label>外盘</label><b style="color:#ef4444">{{ selectedSnap.b_vol?.toLocaleString() }}</b></div>
          <div><label>内盘</label><b style="color:#10b981">{{ selectedSnap.s_vol?.toLocaleString() }}</b></div>
        </div>
      </div>

      <!-- 右：五档盘口 + 下单 -->
      <div class="col right">
        <div class="col-title">五档盘口</div>
        <OrderBook :snap="selectedSnap" />

        <div class="order-panel">
          <div class="col-title" style="margin-top: 16px;">
            下单
            <span v-if="dashboardTrader" style="font-weight: 400; font-size: 12px; color: #6b7280;">
              → {{ dashboardTrader.name }}
            </span>
          </div>
          <div class="form-row">
            <label>股票</label>
            <input type="text" :value="selectedCode || '请先在左侧选一只'" readonly
                   style="background:#f9fafb; color:#6b7280;" />
          </div>
          <div class="form-row">
            <label>价格</label>
            <input type="number" v-model.number="orderPrice" step="0.01" min="0.001" />
            <button class="mini-btn" @click="useCurrentPrice" title="用现价">现价</button>
          </div>
          <div class="form-row">
            <label>数量</label>
            <input type="number" v-model.number="orderAmount" step="100" min="100" placeholder="100" />
          </div>
          <div class="btn-row">
            <button class="btn buy" :disabled="!canSubmit || submitting"
                    @click="submitOrder('BUY')">买入</button>
            <button class="btn sell" :disabled="!canSubmit || submitting"
                    @click="submitOrder('SELL')">卖出</button>
          </div>
          <div v-if="!dashboardTraderId" style="color:#ef4444;font-size:12px;margin-top:8px;text-align:center;">
            请先在顶部选择一个 trader
          </div>
        </div>

        <div v-if="showLlmOnDashboard && dashboardTraderId" class="dash-llm-section">
          <LlmActivityPanel :trader-id="dashboardTraderId" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard { height: calc(100vh - 48px); display: flex; flex-direction: column; }
.topbar {
  display: flex; align-items: center; gap: 20px;
  padding: 10px 16px;
  background: #fafafa;
  border-bottom: 1px solid #e5e7eb;
  font-size: 13px;
}
.status-item { display: flex; align-items: center; gap: 6px; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.spacer { flex: 1; }
.refresh-ctl select {
  padding: 3px 8px; border: 1px solid #e5e7eb; border-radius: 4px; cursor: pointer;
}
.refresh-ctl .hint { color: #9ca3af; font-size: 12px; margin-left: 4px; }
.refresh-btn {
  padding: 4px 12px; background: #3b82f6; color: #fff;
  border: none; border-radius: 4px; cursor: pointer; font-size: 13px;
}
.refresh-btn:hover { background: #2563eb; }
.error { color: #ef4444; }

.layout {
  flex: 1;
  display: grid;
  grid-template-columns: 220px 1fr 320px;
  gap: 8px;
  padding: 8px;
  background: #f3f4f6;
  overflow: hidden;
}
.col { background: #fff; border-radius: 6px; overflow-y: auto; }
.dash-llm-section {
  margin-top: 12px;
  height: 380px;
  border-top: 1px solid #e5e7eb;
  padding-top: 8px;
}
.col-title {
  padding: 10px 14px;
  font-weight: 600;
  border-bottom: 1px solid #e5e7eb;
  font-size: 14px;
  display: flex; align-items: center; justify-content: space-between;
}
.left-tabs {
  display: flex; align-items: center;
  border-bottom: 1px solid #e5e7eb;
  padding: 0 6px;
}
.left-tabs .tab {
  flex: 1;
  padding: 10px 4px;
  background: transparent; border: none; cursor: pointer;
  font-size: 13px; font-weight: 600; color: #6b7280;
  border-bottom: 2px solid transparent;
}
.left-tabs .tab:hover { color: #3b82f6; }
.left-tabs .tab.active { color: #3b82f6; border-bottom-color: #3b82f6; }
.left-tabs .tab .cnt { font-weight: 400; font-size: 12px; color: #9ca3af; margin-left: 2px; }
.left-tabs .tab.active .cnt { color: #60a5fa; }
.left-tabs .tab-spacer { width: 6px; }
.empty-tip {
  padding: 24px 14px;
  color: #9ca3af;
  font-size: 12px;
  text-align: center;
}
.stock-item .line2 .frozen { color: #f59e0b; font-size: 11px; margin-left: 4px; }
.sync-btn {
  border: 1px solid #e5e7eb; background: #fff;
  padding: 2px 8px; border-radius: 4px;
  cursor: pointer; font-size: 14px;
}
.sync-btn:hover { background: #eff6ff; border-color: #3b82f6; color: #3b82f6; }

.filter-bar {
  padding: 6px 10px;
  display: flex; flex-direction: column; gap: 4px;
  border-bottom: 1px solid #f3f4f6;
}
.filter-bar input {
  width: 100%; box-sizing: border-box; padding: 4px 8px;
  border: 1px solid #e5e7eb; border-radius: 4px; font-size: 12px;
}
.filter-bar select {
  width: 100%; box-sizing: border-box; padding: 4px 6px;
  border: 1px solid #e5e7eb; border-radius: 4px; font-size: 12px;
  cursor: pointer; background: #fff;
}

.stock-item .line2.muted { color: #9ca3af; font-size: 11px; }

.stock-item { padding: 10px 14px; cursor: pointer; border-bottom: 1px solid #f3f4f6; }
.stock-item:hover { background: #f9fafb; }
.stock-item.active { background: #eff6ff; border-left: 3px solid #3b82f6; padding-left: 11px; }
.stock-item .line1 { display: flex; justify-content: space-between; }
.stock-item .name { font-weight: 600; }
.stock-item .code { color: #9ca3af; font-size: 12px; }
.stock-item .line2 { display: flex; justify-content: space-between; margin-top: 4px; }
.stock-item .price { font-family: 'Consolas', monospace; font-weight: 600; }
.stock-item .pct { font-size: 12px; }

.center .header {
  padding: 12px 16px;
  display: flex; justify-content: space-between; align-items: center;
  border-bottom: 1px solid #e5e7eb;
}
.stock-name { font-size: 18px; font-weight: 700; margin-right: 8px; }
.stock-code { color: #9ca3af; margin-right: 16px; }
.big-price { font-size: 24px; font-weight: 700; font-family: 'Consolas', monospace; margin-right: 12px; }
.big-change { font-size: 14px; font-family: 'Consolas', monospace; }
.freq-tabs button {
  border: 1px solid #e5e7eb; background: #fff;
  padding: 4px 12px; margin-left: 4px; cursor: pointer;
  border-radius: 4px; font-size: 12px;
}
.freq-tabs button.active { background: #3b82f6; color: #fff; border-color: #3b82f6; }

.kpi-grid {
  padding: 16px; display: grid; grid-template-columns: repeat(4, 1fr);
  gap: 12px; font-size: 13px; border-top: 1px solid #e5e7eb;
}
.kpi-grid > div { display: flex; flex-direction: column; gap: 4px; }
.kpi-grid label { color: #9ca3af; font-size: 12px; }
.kpi-grid b { font-family: 'Consolas', monospace; font-weight: 600; }

.order-panel { padding: 12px 14px; }
.form-row { display: flex; align-items: center; margin-bottom: 8px; }
.form-row label { width: 50px; color: #6b7280; font-size: 13px; }
.form-row input {
  flex: 1; padding: 6px 10px; border: 1px solid #e5e7eb; border-radius: 4px;
  font-family: 'Consolas', monospace;
}
.btn-row { display: flex; gap: 8px; margin-top: 12px; }
.btn { flex: 1; padding: 10px 0; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; font-weight: 600; color: #fff; }
.btn.buy { background: #ef4444; }
.btn.sell { background: #10b981; }
.btn:disabled { background: #d1d5db; cursor: not-allowed; }
.mini-btn {
  margin-left: 6px; padding: 3px 8px; font-size: 11px; cursor: pointer;
  background: #fff; border: 1px solid #d1d5db; border-radius: 4px; color: #6b7280;
}
.mini-btn:hover { border-color: #3b82f6; color: #3b82f6; }
</style>
