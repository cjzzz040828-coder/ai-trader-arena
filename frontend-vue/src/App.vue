<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, RouterLink, useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const showHeader = computed(() => route.path !== '/login')

async function onLogout() {
  await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' })
  auth.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="app">
    <header v-if="showHeader" class="app-header">
      <h2>aiTrade</h2>
      <span class="sub">· AI量化平台</span>
      <span class="spacer"></span>
      <RouterLink to="/" class="link" :class="{ active: route.path === '/' }">Dashboard</RouterLink>
      <RouterLink to="/my-trader" class="link" :class="{ active: route.path === '/my-trader' }">我的Trader</RouterLink>
      <RouterLink to="/traders" class="link" :class="{ active: route.path === '/traders' }">Trader管理</RouterLink>
      <RouterLink to="/leaderboard" class="link" :class="{ active: route.path === '/leaderboard' }">排行榜</RouterLink>
      <span v-if="auth.user" class="user">{{ auth.user.nickname || auth.user.username }}</span>
      <a v-if="auth.isAuthenticated" class="link" @click="onLogout">退出</a>
    </header>
    <main class="app-main">
      <RouterView v-slot="{ Component }">
        <keep-alive :include="['Dashboard', 'MyTrader']">
          <component :is="Component" />
        </keep-alive>
      </RouterView>
    </main>
  </div>
</template>

<style scoped>
.app {
  height: 100vh;
  display: flex;
  flex-direction: column;
}
.app-header {
  height: 48px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  background: #1f2937;
  color: #fff;
}
.app-header h2 { margin: 0; font-size: 18px; }
.app-header .sub { color: #9ca3af; margin-left: 8px; font-size: 13px; }
.app-header .spacer { flex: 1; }
.app-header .user { color: #d1d5db; margin: 0 12px; font-size: 13px; }
.app-header .link {
  color: #fff; text-decoration: none; padding: 6px 12px; border-radius: 4px;
  cursor: pointer; font-size: 14px; margin-right: 4px;
}
.app-header .link:hover { background: #2d3748; }
.app-header .link.active { background: #374151; color: #fbbf24; }
.app-main { flex: 1; overflow: hidden; }
</style>
