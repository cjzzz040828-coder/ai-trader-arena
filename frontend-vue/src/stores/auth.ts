import { defineStore } from 'pinia'
import { api, type AuthUser } from '@/api'

interface State {
  token: string
  user: AuthUser | null
}

export const useAuthStore = defineStore('auth', {
  state: (): State => ({
    token: localStorage.getItem('token') || '',
    user: JSON.parse(localStorage.getItem('user') || 'null')
  }),
  getters: {
    isAuthenticated: (s) => !!s.token
  },
  actions: {
    async login(username: string, password: string) {
      const resp = await api.auth.login(username, password)
      this.persist(resp.token, { id: resp.id, username: resp.username, nickname: resp.nickname })
    },
    async register(username: string, password: string, nickname?: string) {
      const resp = await api.auth.register(username, password, nickname)
      this.persist(resp.token, { id: resp.id, username: resp.username, nickname: resp.nickname })
    },
    async fetchMe() {
      const u = await api.auth.me()
      this.user = u
      localStorage.setItem('user', JSON.stringify(u))
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('token')
      localStorage.removeItem('user')
    },
    persist(token: string, user: AuthUser) {
      this.token = token
      this.user = user
      localStorage.setItem('token', token)
      localStorage.setItem('user', JSON.stringify(user))
    }
  }
})
