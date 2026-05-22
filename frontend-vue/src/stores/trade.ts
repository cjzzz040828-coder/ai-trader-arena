import { defineStore } from 'pinia'
import { api, type OrderVO, type PlaceOrderReq, type PositionVO, type TraderVO } from '@/api'

interface State {
  traders: TraderVO[]
  currentTraderId: number | null
  positions: PositionVO[]
  orders: OrderVO[]
  stockNames: Record<string, string>
}

export const useTradeStore = defineStore('trade', {
  state: (): State => ({
    traders: [],
    currentTraderId: null,
    positions: [],
    orders: [],
    stockNames: {}
  }),
  getters: {
    currentTrader: (s): TraderVO | undefined =>
      s.traders.find(t => t.id === s.currentTraderId),
    stockName: (s) => (code: string) => s.stockNames[code] || ''
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
      await this.ensureStockNames()
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
      await this.ensureStockNames()
    },
    async ensureStockNames() {
      const codes = new Set<string>()
      for (const p of this.positions) codes.add(p.stockCode)
      for (const o of this.orders) codes.add(o.stockCode)
      const missing = [...codes].filter(c => !this.stockNames[c])
      if (missing.length === 0) return
      try {
        const resp = await api.quote(missing.join(','))
        for (const item of resp.data || []) {
          if (item.code && item.name) this.stockNames[item.code] = item.name
        }
      } catch (e) {
        console.warn('[trade] fetch stock names failed', e)
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
