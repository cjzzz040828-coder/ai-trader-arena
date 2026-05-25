<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, RouterLink, useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/theme'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const theme = useTheme()

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
      <div class="header-left">
        <h2>aiTrade</h2>
        <span class="sub">· AI量化平台</span>
      </div>
      <nav class="header-center">
        <RouterLink to="/" class="link" :class="{ active: route.path === '/' }">Dashboard</RouterLink>
        <RouterLink to="/my-trader" class="link" :class="{ active: route.path === '/my-trader' }">我的Trader</RouterLink>
        <RouterLink to="/llm-activity" class="link" :class="{ active: route.path === '/llm-activity' }">LLM监控</RouterLink>
        <RouterLink to="/strategies" class="link" :class="{ active: route.path === '/strategies' }">策略库</RouterLink>
        <RouterLink to="/traders" class="link" :class="{ active: route.path === '/traders' }">Trader管理</RouterLink>
        <RouterLink to="/pools" class="link" :class="{ active: route.path === '/pools' }">选股池</RouterLink>
        <RouterLink to="/news" class="link" :class="{ active: route.path === '/news' }">新闻</RouterLink>
        <RouterLink to="/leaderboard" class="link" :class="{ active: route.path === '/leaderboard' }">排行榜</RouterLink>
        <RouterLink to="/backtests" class="link" :class="{ active: route.path === '/backtests' }">回测历史</RouterLink>
      </nav>
      <div class="header-right">
        <button class="theme-toggle" :title="theme.mode.value === 'dark' ? '切换到日间' : '切换到夜间'" @click="theme.toggle()">
          <svg v-if="theme.mode.value === 'dark'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
          </svg>
          <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
          </svg>
        </button>
        <span v-if="auth.user" class="user">{{ auth.user.nickname || auth.user.username }}</span>
        <a v-if="auth.isAuthenticated" class="link" @click="onLogout">退出</a>
      </div>
    </header>
    <main class="app-main">
      <RouterView v-slot="{ Component }">
        <keep-alive :include="['Dashboard', 'MyTrader', 'LlmActivityDashboard']">
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
  background: var(--brand-bg);
}
.app-header {
  height: 48px;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 0 20px;
  background: var(--brand-header-bg);
  color: var(--brand-header-text);
  box-shadow: var(--brand-shadow-sm);
}
.header-left { display: flex; align-items: center; justify-self: start; }
.header-center { display: flex; align-items: center; justify-self: center; }
.header-right { display: flex; align-items: center; justify-self: end; }
.app-header h2 {
  margin: 0; font-size: 18px; font-weight: 700; letter-spacing: 1px;
  color: var(--brand-primary);
}
.app-header .sub { color: var(--brand-header-text-muted); margin-left: 8px; font-size: 13px; }
.app-header .user { color: var(--brand-header-text-muted); margin: 0 12px; font-size: 13px; }
.app-header .link {
  color: var(--brand-header-text); text-decoration: none; padding: 6px 12px; border-radius: 4px;
  cursor: pointer; font-size: 14px; margin-right: 4px;
  transition: background 0.15s, color 0.15s;
}
.app-header .link:hover { background: var(--brand-header-hover); }
.app-header .link.active { background: var(--brand-header-active); color: #fff; }
.theme-toggle {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; margin-right: 8px;
  border: 1px solid var(--brand-border); border-radius: 6px;
  background: transparent; color: var(--brand-header-text);
  cursor: pointer; transition: background 0.15s, border-color 0.15s;
}
.theme-toggle:hover { background: var(--brand-header-hover); border-color: var(--brand-primary); color: var(--brand-primary); }
.app-main { flex: 1; overflow: hidden; }
</style>
