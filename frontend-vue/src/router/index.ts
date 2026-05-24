import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '@/views/Dashboard.vue'
import Login from '@/views/Login.vue'
import MyTrader from '@/views/MyTrader.vue'
import Leaderboard from '@/views/Leaderboard.vue'
import TraderManage from '@/views/TraderManage.vue'
import LlmActivity from '@/views/LlmActivity.vue'
import LlmActivityDashboard from '@/views/LlmActivityDashboard.vue'
import StrategyMarket from '@/views/StrategyMarket.vue'
import BacktestReport from '@/views/BacktestReport.vue'
import BacktestHistory from '@/views/BacktestHistory.vue'
import PoolManage from '@/views/PoolManage.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: Dashboard, meta: { requiresAuth: true } },
    { path: '/my-trader', name: 'my-trader', component: MyTrader, meta: { requiresAuth: true } },
    { path: '/llm-activity', name: 'llm-activity', component: LlmActivityDashboard, meta: { requiresAuth: true } },
    {
      path: '/llm-activity/:traderId(\\d+)',
      name: 'llm-activity-detail',
      component: LlmActivity,
      props: route => ({ traderId: Number(route.params.traderId) }),
      meta: { requiresAuth: true }
    },
    { path: '/leaderboard', name: 'leaderboard', component: Leaderboard, meta: { requiresAuth: true } },
    { path: '/strategies', name: 'strategies', component: StrategyMarket, meta: { requiresAuth: true } },
    { path: '/traders', name: 'traders', component: TraderManage, meta: { requiresAuth: true } },
    { path: '/pools', name: 'pools', component: PoolManage, meta: { requiresAuth: true } },
    { path: '/backtests', name: 'backtests', component: BacktestHistory, meta: { requiresAuth: true } },
    {
      path: '/backtest/:id(\\d+)',
      name: 'backtest-report',
      component: BacktestReport,
      props: route => ({ id: Number(route.params.id) }),
      meta: { requiresAuth: true }
    },
    { path: '/login', name: 'login', component: Login }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && auth.isAuthenticated) {
    return { path: '/' }
  }
})

export default router
