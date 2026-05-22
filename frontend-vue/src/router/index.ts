import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '@/views/Dashboard.vue'
import Login from '@/views/Login.vue'
import MyTrader from '@/views/MyTrader.vue'
import Leaderboard from '@/views/Leaderboard.vue'
import TraderManage from '@/views/TraderManage.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: Dashboard, meta: { requiresAuth: true } },
    { path: '/my-trader', name: 'my-trader', component: MyTrader, meta: { requiresAuth: true } },
    { path: '/leaderboard', name: 'leaderboard', component: Leaderboard, meta: { requiresAuth: true } },
    { path: '/traders', name: 'traders', component: TraderManage, meta: { requiresAuth: true } },
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
