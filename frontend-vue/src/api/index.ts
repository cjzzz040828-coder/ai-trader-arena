import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 10000
})

http.interceptors.request.use(cfg => {
  const token = localStorage.getItem('token')
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

http.interceptors.response.use(
  res => res.data,
  err => {
    const status = err?.response?.status
    if (status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      if (location.pathname !== '/login') {
        location.href = '/login'
      }
    }
    console.error('[api]', status, err?.message)
    return Promise.reject(err)
  }
)

export interface SnapshotItem {
  code: string
  name: string
  price: number
  last_close: number
  open: number
  high: number
  low: number
  change: number
  change_pct: number
  vol: number
  amount: number
  bid1: number; bid2: number; bid3: number; bid4: number; bid5: number
  ask1: number; ask2: number; ask3: number; ask4: number; ask5: number
  bid_vol1: number; bid_vol2: number; bid_vol3: number; bid_vol4: number; bid_vol5: number
  ask_vol1: number; ask_vol2: number; ask_vol3: number; ask_vol4: number; ask_vol5: number
  s_vol: number
  b_vol: number
  fetched_at: string
}

export interface SnapshotResp {
  market_status: string
  last_ok_ago_seconds: number
  count: number
  data: SnapshotItem[]
}

export interface BarItem {
  datetime: string
  open: number
  close: number
  high: number
  low: number
  vol: number
  amount: number
}

export interface BarsResp {
  code: string
  frequency: number
  count: number
  data: BarItem[]
}

export interface WatchlistItem {
  code: string
  name: string
  market: 'SH' | 'SZ'
  added_price: number
  added_date: string
}

export interface WatchlistResp {
  count: number
  data: WatchlistItem[]
  /** 分组字典：key=分组名（.sel 文件 stem 或 stockblock.ini 分组名），value=该组的代码列表 */
  groups?: Record<string, string[]>
}

export interface AuthUser {
  id: number
  username: string
  nickname: string
}

export interface AuthResp extends AuthUser {
  token: string
}

export interface TraderVO {
  id: number
  name: string
  strategyType: string
  enabled: boolean
  initialBalance: number
  balance: number
  frozenBalance: number
  marketValue: number
  totalAsset: number
  totalProfit: number
  profitPct: number
  maShort: number | null
  maLong: number | null
  llmBaseUrl: string | null
  llmModel: string | null
  llmPrompt: string | null
  llmApiKeySet: boolean
  indicatorConfigJson: string | null
  scriptCode: string | null
  poolName: string | null
  templateId: number | null
}

export interface CreateTraderReq {
  name: string
  strategyType: string
  enabled?: boolean
  initialBalance?: number
  maShort?: number
  maLong?: number
  llmBaseUrl?: string
  llmApiKey?: string
  llmModel?: string
  llmPrompt?: string
  indicatorConfigJson?: string
  scriptCode?: string
  poolName?: string
}

export interface UpdateTraderReq {
  name?: string
  strategyType?: string
  enabled?: boolean
  initialBalance?: number
  maShort?: number
  maLong?: number
  llmBaseUrl?: string
  llmApiKey?: string
  llmModel?: string
  llmPrompt?: string
  indicatorConfigJson?: string
  scriptCode?: string
  poolName?: string
}

export interface ScriptTestResult {
  ok: boolean
  message: string
  sample?: string
}

export interface LeaderboardItem {
  rank: number
  traderId: number
  traderName: string
  userId: number
  userNickname: string
  strategyType: string
  balance: number
  frozenBalance: number
  marketValue: number
  totalAsset: number
  totalProfit: number
  profitPct: number
  updatedAt: string
}

export interface TestLlmResult {
  ok: boolean
  message: string
  reply: string | null
  error: string | null
}

export interface DecideOrderDetail {
  id: number
  code: string
  name: string | null
  side: 'BUY' | 'SELL'
  amount: number
  price: number
  status: 'PENDING' | 'FILLED' | 'CANCELLED' | 'REJECTED'
}

export interface DecideNowResult {
  ok: boolean
  strategy: string
  actualMarketOpen: boolean
  watchlistSize: number
  elapsedMs: number
  newOrders: number
  filledNow: number
  totalPending: number
  newOrderDetails: DecideOrderDetail[]
  message: string
}

export interface PositionVO {
  id: number
  stockCode: string
  amount: number
  frozenAmount: number
  costPrice: number
  currentPrice: number
  marketValue: number
  profitPct: number
  updatedAt: string
}

export interface OrderVO {
  id: number
  traderId: number
  stockCode: string
  side: 'BUY' | 'SELL'
  price: number
  amount: number
  status: 'PENDING' | 'FILLED' | 'CANCELLED' | 'REJECTED'
  filledPrice: number | null
  filledAt: string | null
  createdAt: string
}

export interface PlaceOrderReq {
  traderId: number
  stockCode: string
  side: 'BUY' | 'SELL'
  price: number
  amount: number
}

/** 图表买卖点标注用的轻量结构，由 OrderVO 投影得到 */
export interface TradeMarker {
  filledAt: string
  side: 'BUY' | 'SELL'
  price: number
  amount: number
}

export interface BacktestRequest {
  traderId: number
  startDate: string
  endDate: string
  initialBalance: number
}

export interface BacktestTaskVO {
  id: number
  traderId: number
  traderName: string
  strategyType: string
  strategyParams: string
  startDate: string
  endDate: string
  initialBalance: number
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'
  progress: number
  error: string | null
  finalEquity: number | null
  totalReturnPct: number | null
  totalTrades: number | null
  maxDrawdownPct: number | null
  equityCurveJson: string | null
  sharpeRatio: number | null
  sortinoRatio: number | null
  calmarRatio: number | null
  annualReturnPct: number | null
  winRatePct: number | null
  profitLossRatio: number | null
  benchmarkCurveJson: string | null
  monthlyReturnsJson: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface BacktestTradeVO {
  id: number
  tradeDate: string
  stockCode: string
  stockName: string | null
  side: 'BUY' | 'SELL'
  amount: number
  price: number
  balanceAfter: number
  costPrice: number | null
  reason: string
}

export type LlmActivityPhase =
  | 'started'
  | 'tool_call'
  | 'tool_result'
  | 'final'
  | 'failed'
  | 'cancel_requested'
  | 'cancelled'
  | 'purged'

export interface LlmActivity {
  id?: number
  traderId: number
  userId: number
  decisionId: number
  seq: number
  phase: LlmActivityPhase
  round: number | null
  toolName: string | null
  toolCallId: string | null
  argsJson: string | null
  resultJson: string | null
  message: string | null
  promptJson: string | null
  createdAt: string
}

export interface StrategyTemplateMetrics {
  instanceCount: number
  avgReturnPct: number
  totalTrades: number
  winRate: number
}

export interface StrategyTemplateVO {
  id: number
  code: string
  name: string
  description: string
  strategyType: 'MA' | 'LLM'
  params: Record<string, any>
  tags: string[]
  isOfficial: boolean
  sortOrder: number
  metrics: StrategyTemplateMetrics
}

export interface InstantiateTemplateReq {
  traderName: string
  llmApiKey?: string
}

// ============ 选股池（pool） ============
export interface PoolRules {
  markets: string[]
  exclude_st: boolean
  exclude_delisting: boolean
  min_price: number
  max_price: number
  min_market_cap: number
  max_market_cap: number
}

export interface PoolDefinition {
  name: string
  displayName: string
  rules: PoolRules
  autoRefresh: boolean
  createdAt: string
}

export interface PoolStockEntry {
  code: string
  name: string
  market?: string
  segment?: string
  price?: number
  liutongshizhi?: number
  liutongguben?: number
}

export interface PoolSnapshotStats {
  step1_after_market_filter: number
  step2_after_price_filter: number
  step3_after_mktcap_filter: number
  elapsed_seconds: number
}

export interface PoolStatusResp {
  exists: boolean
  pool_name: string
  definition: PoolDefinition | null
  updated_at?: string
  count?: number
  rules?: PoolRules
  stats?: PoolSnapshotStats
  building: boolean
  is_stale?: boolean
}

export interface PoolHistoryItem {
  date: string
  updated_at: string
  count: number
}

export interface PoolHistorySnapshot {
  pool_name: string
  updated_at: string
  count: number
  rules: PoolRules
  stats: PoolSnapshotStats
  codes: PoolStockEntry[]
}

export interface CreatePoolReq {
  name: string
  displayName?: string
  rules: PoolRules
  autoRefresh?: boolean
}

export interface UpdatePoolReq {
  displayName?: string
  rules?: PoolRules
  autoRefresh?: boolean
}

export const api = {
  gatewayHealth: () => http.get('/quote/gateway-health') as unknown as Promise<any>,
  quote: (codes: string) => http.get(`/quote/snapshot/${codes}`) as unknown as Promise<SnapshotResp>,
  bars: (code: string, frequency = 9, count = 240) =>
    http.get(`/quote/bars/${code}`, { params: { frequency, count } }) as unknown as Promise<BarsResp>,
  watchlist: () => http.get('/quote/watchlist') as unknown as Promise<WatchlistResp>,
  watchlistReload: () => http.get('/quote/watchlist/reload') as unknown as Promise<WatchlistResp>,
  auth: {
    register: (username: string, password: string, nickname?: string) =>
      http.post('/auth/register', { username, password, nickname }) as unknown as Promise<AuthResp>,
    login: (username: string, password: string) =>
      http.post('/auth/login', { username, password }) as unknown as Promise<AuthResp>,
    me: () => http.get('/auth/me') as unknown as Promise<AuthUser>
  },
  trade: {
    traders: () => http.get('/traders') as unknown as Promise<TraderVO[]>,
    createTrader: (req: CreateTraderReq) =>
      http.post('/traders', req) as unknown as Promise<TraderVO>,
    updateTrader: (id: number, req: UpdateTraderReq) =>
      http.put(`/traders/${id}`, req) as unknown as Promise<TraderVO>,
    deleteTrader: (id: number) =>
      http.delete(`/traders/${id}`) as unknown as Promise<{ deleted: boolean; cancelledOrders: number }>,
    resetTrader: (id: number) =>
      http.post(`/traders/${id}/reset`) as unknown as Promise<TraderVO>,
    testLlm: (id: number) =>
      http.post(`/traders/${id}/test-llm`, null, { timeout: 120000 }) as unknown as Promise<TestLlmResult>,
    decideNow: (id: number, timeout = 120000) =>
      http.post(`/traders/${id}/decide-now`, null, { timeout }) as unknown as Promise<DecideNowResult>,
    positions: (traderId: number) =>
      http.get(`/traders/${traderId}/positions`) as unknown as Promise<PositionVO[]>,
    orders: (traderId: number, limit = 50) =>
      http.get(`/traders/${traderId}/orders`, { params: { limit } }) as unknown as Promise<OrderVO[]>,
    placeOrder: (req: PlaceOrderReq) =>
      http.post('/orders', req) as unknown as Promise<OrderVO>,
    cancelOrder: (id: number) =>
      http.post(`/orders/${id}/cancel`) as unknown as Promise<OrderVO>,
    testScript: (scriptCode: string) =>
      http.post('/traders/test-script', { scriptCode }, { timeout: 10000 }) as unknown as Promise<ScriptTestResult>
  },
  leaderboard: (limit = 100) =>
    http.get('/leaderboard', { params: { limit } }) as unknown as Promise<LeaderboardItem[]>,
  backtest: {
    create: (req: BacktestRequest) =>
      http.post('/backtest/tasks', req) as unknown as Promise<BacktestTaskVO>,
    get: (id: number) =>
      http.get(`/backtest/tasks/${id}`) as unknown as Promise<BacktestTaskVO>,
    list: () =>
      http.get('/backtest/tasks') as unknown as Promise<BacktestTaskVO[]>,
    trades: (id: number) =>
      http.get(`/backtest/tasks/${id}/trades`) as unknown as Promise<BacktestTradeVO[]>
  },
  llm: {
    activities: (traderId: number, limit = 200) =>
      http.get(`/traders/${traderId}/llm-activities`, { params: { limit } }) as unknown as Promise<LlmActivity[]>,
    cancel: (traderId: number) =>
      http.post(`/traders/${traderId}/llm-cancel`, null) as unknown as Promise<{ requested: boolean; decisionId?: number; reason?: string }>,
    streamUrl: (traderId: number) => {
      const token = localStorage.getItem('token') || ''
      return `/api/traders/${traderId}/llm-stream?token=${encodeURIComponent(token)}`
    },
    /** dashboard 用：订阅当前用户所有 LLM trader 的活动流（用户级 SSE） */
    userStreamUrl: () => {
      const token = localStorage.getItem('token') || ''
      return `/api/llm-activity/stream?token=${encodeURIComponent(token)}`
    },
    /** dashboard 基线：一次拉所有 LLM trader 最近 N 条活动，按 traderId 分组 */
    recent: (perTrader = 20) =>
      http.get('/llm-activity/recent', { params: { perTrader } }) as unknown as Promise<Record<number, LlmActivity[]>>,
    /** 清空单 trader 的全部 LLM 活动（不可恢复），进行中决策不会被中断 */
    clearForTrader: (traderId: number) =>
      http.delete(`/traders/${traderId}/llm-activities`) as unknown as Promise<{ deleted: number; traderId: number }>,
    /** 清空当前用户全部 LLM 活动（不可恢复） */
    clearAll: () =>
      http.delete('/llm-activity') as unknown as Promise<{ deleted: number }>
  },
  strategyTemplates: {
    list: () =>
      http.get('/strategy-templates') as unknown as Promise<StrategyTemplateVO[]>,
    get: (id: number) =>
      http.get(`/strategy-templates/${id}`) as unknown as Promise<StrategyTemplateVO>,
    instantiate: (id: number, req: InstantiateTemplateReq) =>
      http.post(`/strategy-templates/${id}/instantiate`, req) as unknown as Promise<TraderVO>
  },
  pool: {
    list: () =>
      http.get('/pool') as unknown as Promise<{ pools: PoolDefinition[] }>,
    create: (req: CreatePoolReq) =>
      http.post('/pool', req) as unknown as Promise<PoolDefinition>,
    update: (name: string, req: UpdatePoolReq) =>
      http.put(`/pool/${name}`, req) as unknown as Promise<PoolDefinition>,
    delete: (name: string) =>
      http.delete(`/pool/${name}`) as unknown as Promise<{ ok: boolean }>,
    status: (name: string) =>
      http.get(`/pool/${name}/status`) as unknown as Promise<PoolStatusResp>,
    rebuild: (name: string) =>
      http.post(`/pool/${name}/rebuild`, null, { timeout: 5000 }) as unknown as Promise<{ ok: boolean; message: string }>,
    history: (name: string) =>
      http.get(`/pool/${name}/history`) as unknown as Promise<{ name: string; history: PoolHistoryItem[] }>,
    historyDetail: (name: string, date: string) =>
      http.get(`/pool/${name}/history/${date}`) as unknown as Promise<PoolHistorySnapshot>
  }
}
