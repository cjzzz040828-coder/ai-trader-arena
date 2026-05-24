<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type BacktestTaskVO } from '@/api'

defineOptions({ name: 'BacktestHistory' })

const router = useRouter()
const loading = ref(false)
const all = ref<BacktestTaskVO[]>([])
const traderFilter = ref<number | null>(null)
const statusFilter = ref<string>('')

async function refresh() {
  loading.value = true
  try {
    all.value = await api.backtest.list()
  } catch (e: any) {
    ElMessage.error('加载回测历史失败: ' + (e?.response?.data?.message || e?.message || e))
  } finally {
    loading.value = false
  }
}

const traderOptions = computed(() => {
  const m = new Map<number, string>()
  all.value.forEach(t => m.set(t.traderId, t.traderName))
  return Array.from(m.entries()).map(([id, name]) => ({ id, name }))
})

const filtered = computed(() => {
  return all.value.filter(t => {
    if (traderFilter.value != null && t.traderId !== traderFilter.value) return false
    if (statusFilter.value && t.status !== statusFilter.value) return false
    return true
  })
})

function fmtPct(v: number | null | undefined): string {
  if (v == null) return '-'
  const s = Number(v).toFixed(2) + '%'
  return v >= 0 ? '+' + s : s
}

function fmtTime(s: string | null | undefined): string {
  if (!s) return '-'
  return s.replace('T', ' ').replace(/\.\d+$/, '')
}

function statusTag(s: string) {
  if (s === 'DONE') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'RUNNING') return 'warning'
  return 'info'
}

function strategyTag(t: string) {
  if (t === 'MA') return 'success'
  if (t === 'LLM') return 'warning'
  return 'info'
}

function openReport(id: number) {
  router.push(`/backtest/${id}`)
}

onMounted(refresh)
</script>

<template>
  <div class="bt-history">
    <div class="header">
      <h2>回测历史</h2>
      <span class="hint">点击任意一条进入完整报告</span>
      <span class="spacer"></span>
      <el-select v-model="traderFilter" placeholder="全部 trader" clearable size="small" style="width: 180px; margin-right: 8px;">
        <el-option v-for="o in traderOptions" :key="o.id" :label="o.name" :value="o.id" />
      </el-select>
      <el-select v-model="statusFilter" placeholder="全部状态" clearable size="small" style="width: 130px; margin-right: 8px;">
        <el-option label="DONE" value="DONE" />
        <el-option label="RUNNING" value="RUNNING" />
        <el-option label="PENDING" value="PENDING" />
        <el-option label="FAILED" value="FAILED" />
      </el-select>
      <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
    </div>

    <el-table :data="filtered" v-loading="loading" empty-text="还没有回测记录" stripe
              @row-click="(row: BacktestTaskVO) => openReport(row.id)"
              row-class-name="clickable-row">
      <el-table-column label="ID" prop="id" width="80" />
      <el-table-column label="Trader" min-width="160">
        <template #default="{ row }">
          <span style="font-weight: 600;">{{ row.traderName }}</span>
          <el-tag :type="strategyTag(row.strategyType)" size="small" style="margin-left: 8px;">
            {{ row.strategyType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="回测区间" width="220">
        <template #default="{ row }">
          {{ row.startDate }} ~ {{ row.endDate }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag>
          <el-progress v-if="row.status === 'RUNNING'"
                       :percentage="row.progress || 0"
                       :stroke-width="4"
                       :show-text="false"
                       style="margin-top: 4px;" />
        </template>
      </el-table-column>
      <el-table-column label="总收益" width="120" align="right">
        <template #default="{ row }">
          <span :class="(row.totalReturnPct ?? 0) >= 0 ? 'up' : 'down'">
            {{ fmtPct(row.totalReturnPct) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="最大回撤" width="120" align="right">
        <template #default="{ row }">
          <span class="down">{{ fmtPct(row.maxDrawdownPct) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="成交笔数" prop="totalTrades" width="100" align="right" />
      <el-table-column label="创建时间" min-width="170">
        <template #default="{ row }">
          <span class="time">{{ fmtTime(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="50">
        <template #default>
          <span class="arrow">→</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.bt-history { padding: 16px 20px; }
.header { display: flex; align-items: center; margin-bottom: 12px; }
.header h2 { margin: 0; font-size: 18px; color: var(--brand-text-primary); }
.header .hint { color: var(--brand-text-secondary); font-size: 12px; margin-left: 12px; }
.header .spacer { flex: 1; }
.up { color: var(--brand-up); font-weight: 600; }
.down { color: var(--brand-down); font-weight: 600; }
.time { color: var(--brand-text-placeholder); font-size: 12px; }
.arrow { color: var(--brand-text-placeholder); }
:deep(.clickable-row) { cursor: pointer; }
:deep(.clickable-row:hover) { background: var(--brand-bg) !important; }
</style>
