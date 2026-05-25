import { defineStore } from 'pinia'
import { api, type OrderVO, type PlaceOrderReq, type PositionVO, type TraderVO } from '@/api'

interface State {
  traders: TraderVO[]
  currentTraderId: number | null
  positions: PositionVO[]
  orders: OrderVO[]
  stockNames: Record<string, string>
  stockChangePct: Record<string, number>
}

export const useTradeStore = defineStore('trade', {
  state: (): State => ({
    traders: [],
    currentTraderId: null,
    positions: [],
    orders: [],
    stockNames: {},
    stockChangePct: {}
  }),
  getters: {
    currentTrader: (s): TraderVO | undefined =>
      s.traders.find(t => t.id === s.currentTraderId),
    stockName: (s) => (code: string) => s.stockNames[code] || '',
    changePctOf: (s) => (code: string) => s.stockChangePct[code]
  },
  actions: {
    async fetchTraders() {
      this.traders = await api.trade.traders()
      if (this.currentTraderId == null && this.traders.length > 0) {
        this.currentTraderId = this.traders[0].id
      } else if (this.currentTraderId != null && !this.traders.some(t => t.id === this.currentTraderId)) {
        // 当前 trader 被删了，切回第一个
        this.currentTraderId = this.traders[0]?.id ?? null
      }
    },
    async setCurrentTrader(id: number) {
      if (this.currentTraderId === id) return
      this.currentTraderId = id
      this.positions = []
      this.orders = []
      await Promise.all([this.fetchPositions(), this.fetchOrders()])
      await this.refreshQuotes()
    },
    async fetchPositions() {
      if (this.currentTraderId == null) return
      this.positions = await api.trade.positions(this.currentTraderId)
    },
    async fetchOrders() {
      if (this.currentTraderId == null) return
      this.orders = await api.trade.orders(this.currentTraderId)
    },
    async refreshAll() {
      await this.fetchTraders()
      await Promise.all([this.fetchPositions(), this.fetchOrders()])
      await this.refreshQuotes()
    },
    /**
     * 拉持仓/订单涉及的所有股票快照，更新 stockNames + stockChangePct。
     * 跟 fetchPositions 同步调用，保证表格里 "今日涨跌幅" 列跟 currentPrice 同样新鲜。
     */
    async refreshQuotes() {
      const codes = new Set<string>()
      for (const p of this.positions) codes.add(p.stockCode)
      for (const o of this.orders) codes.add(o.stockCode)
      if (codes.size === 0) return
      try {
        const resp = await api.quote([...codes].join(','))
        for (const item of resp.data || []) {
          if (!item.code) continue
          if (item.name) this.stockNames[item.code] = item.name
          if (typeof item.change_pct === 'number') this.stockChangePct[item.code] = item.change_pct
        }
      } catch (e) {
        console.warn('[trade] refreshQuotes failed', e)
      }
    },
    async placeOrder(req: PlaceOrderReq) {
      await api.trade.placeOrder(req)
      await this.refreshAll()
    },
    async cancelOrder(id: number) {
      await api.trade.cancelOrder(id)
      await this.refreshAll()
    }
  }
})
