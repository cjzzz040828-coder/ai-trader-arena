<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { api, type LeaderboardItem } from '@/api'

defineOptions({ name: 'Leaderboard' })

const auth = useAuthStore()
const loading = ref(false)
const items = ref<LeaderboardItem[]>([])
let timer: number | null = null

const myUserId = computed(() => auth.user?.id ?? null)

async function refresh() {
  loading.value = true
  try {
    items.value = await api.leaderboard(100)
  } catch (e: any) {
    ElMessage.error('排行榜加载失败: ' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

function fmt(n: number | null | undefined, digits = 2): string {
  if (n == null) return '-'
  return Number(n).toFixed(digits)
}

function fmtTime(s: string | null | undefined): string {
  if (!s) return '-'
  return s.replace('T', ' ').replace(/\.\d+$/, '')
}

function rowClass({ row }: { row: LeaderboardItem }) {
  return row.userId === myUserId.value ? 'mine' : ''
}

function strategyTag(t: string) {
  if (t === 'MA') return 'success'
  if (t === 'LLM') return 'warning'
  return 'info'
}

onMounted(() => {
  refresh()
  timer = window.setInterval(refresh, 5000)
})

onUnmounted(() => {
  if (timer != null) clearInterval(timer)
})
</script>

<template>
  <div class="leaderboard">
    <div class="header">
      <h2>全站排行榜</h2>
      <span class="hint">所有用户的 trader 按总盈亏排序，本人 trader 高亮，5 秒刷新</span>
      <span class="spacer"></span>
      <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
    </div>
    <el-table :data="items" :row-class-name="rowClass" v-loading="loading" empty-text="暂无 trader" stripe>
      <el-table-column label="#" width="60">
        <template #default="{ row }">
          <span :class="row.rank <= 3 ? 'rank-top' : ''">{{ row.rank }}</span>
        </template>
      </el-table-column>
      <el-table-column label="用户" min-width="120">
        <template #default="{ row }">
          <span>{{ row.userNickname }}</span>
          <el-tag v-if="row.userId === myUserId" size="small" type="primary" style="margin-left: 6px;">我</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Trader" min-width="160">
        <template #default="{ row }">
          <span style="font-weight: 600;">{{ row.traderName }}</span>
        </template>
      </el-table-column>
      <el-table-column label="策略" width="90">
        <template #default="{ row }">
          <el-tag :type="strategyTag(row.strategyType)" size="small">{{ row.strategyType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="总资产" width="140" align="right">
        <template #default="{ row }">¥{{ fmt(row.totalAsset) }}</template>
      </el-table-column>
      <el-table-column label="可用" width="120" align="right">
        <template #default="{ row }">¥{{ fmt(row.balance) }}</template>
      </el-table-column>
      <el-table-column label="持仓市值" width="120" align="right">
        <template #default="{ row }">¥{{ fmt(row.marketValue) }}</template>
      </el-table-column>
      <el-table-column label="总盈亏" width="140" align="right">
        <template #default="{ row }">
          <span :class="row.totalProfit >= 0 ? 'up' : 'down'">
            {{ row.totalProfit >= 0 ? '+' : '' }}¥{{ fmt(row.totalProfit) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="收益率" width="100" align="right">
        <template #default="{ row }">
          <span :class="row.profitPct >= 0 ? 'up' : 'down'">
            {{ row.profitPct >= 0 ? '+' : '' }}{{ fmt(row.profitPct) }}%
          </span>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="170">
        <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.leaderboard { padding: 16px; height: 100%; overflow: auto; background: #f3f4f6; }
.header { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.header h2 { margin: 0; font-size: 18px; }
.header .hint { color: #6b7280; font-size: 13px; }
.header .spacer { flex: 1; }
.up { color: #dc2626; font-weight: 600; }
.down { color: #16a34a; font-weight: 600; }
.rank-top { color: #f59e0b; font-weight: 700; font-size: 16px; }
:deep(.el-table .mine) { background: #fef9c3 !important; }
:deep(.el-table .mine:hover > td) { background: #fde68a !important; }
</style>
