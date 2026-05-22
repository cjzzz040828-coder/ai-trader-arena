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

export interface DecideNowResult {
  ok: boolean
  strategy: string
  actualMarketOpen: boolean
  watchlistSize: number
  elapsedMs: number
  newOrders: number
  totalPending: number
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
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface BacktestTradeVO {
  id: number
  tradeDate: string
  stockCode: string
  side: 'BUY' | 'SELL'
  amount: number
  price: number
  balanceAfter: number
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
  createdAt: string
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
    decideNow: (id: number) =>
      http.post(`/traders/${id}/decide-now`, null, { timeout: 120000 }) as unknown as Promise<DecideNowResult>,
    positions: (traderId: number) =>
      http.get(`/traders/${traderId}/positions`) as unknown as Promise<PositionVO[]>,
    orders: (traderId: number, limit = 50) =>
      http.get(`/traders/${traderId}/orders`, { params: { limit } }) as unknown as Promise<OrderVO[]>,
    placeOrder: (req: PlaceOrderReq) =>
      http.post('/orders', req) as unknown as Promise<OrderVO>,
    cancelOrder: (id: number) =>
      http.post(`/orders/${id}/cancel`) as unknown as Promise<OrderVO>
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
    }
  }
}
