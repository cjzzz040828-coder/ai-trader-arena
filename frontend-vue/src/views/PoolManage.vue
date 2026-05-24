<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type PoolDefinition, type PoolHistoryItem, type PoolStatusResp, type PoolStockEntry } from '@/api'

defineOptions({ name: 'PoolManage' })

const MARKETS = [
  { key: 'MAIN_SH', label: '沪市主板 (600/601/603/605)' },
  { key: 'MAIN_SZ', label: '深市主板 (000)' },
  { key: 'SME', label: '中小板 (002)' },
  { key: 'GEM', label: '创业板 (300)' },
  { key: 'STAR', label: '科创板 (688)' }
]

const loading = ref(false)
const pools = ref<PoolDefinition[]>([])
// 每个池子的最新状态（updated_at / count / is_stale / building）
const statusByName = reactive<Record<string, PoolStatusResp>>({})

// ============ 创建/编辑表单 ============
const dialogOpen = ref(false)
const submitting = ref(false)
const editName = ref<string | null>(null)  // null 表示新建
const form = reactive({
  name: '',
  displayName: '',
  markets: ['MAIN_SH', 'MAIN_SZ'] as string[],
  exclude_st: true,
  exclude_delisting: true,
  min_price: 2,
  max_price: 50,
  min_market_cap_yi: 20,    // 单位：亿元
  max_market_cap_yi: 500,
  autoRefresh: true
})

const isEdit = computed(() => editName.value != null)

// ============ 历史快照 ============
const historyDialogOpen = ref(false)
const historyPoolName = ref('')
const historyList = ref<PoolHistoryItem[]>([])
const historyLoading = ref(false)

// ============ 快照详情 ============
const snapshotDialogOpen = ref(false)
const snapshotPoolName = ref('')
const snapshotDate = ref('')
const snapshotCodes = ref<PoolStockEntry[]>([])
const snapshotLoading = ref(false)

async function refresh() {
  loading.value = true
  try {
    const r = await api.pool.list()
    pools.value = r.pools || []
    // 并发拉所有池子的状态
    await Promise.all(pools.value.map(async p => {
      try { statusByName[p.name] = await api.pool.status(p.name) } catch { /* 忽略单个失败 */ }
    }))
  } catch (e: any) {
    ElMessage.error('加载池子列表失败: ' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editName.value = null
  form.name = ''
  form.displayName = ''
  form.markets = ['MAIN_SH', 'MAIN_SZ']
  form.exclude_st = true
  form.exclude_delisting = true
  form.min_price = 2
  form.max_price = 50
  form.min_market_cap_yi = 20
  form.max_market_cap_yi = 500
  form.autoRefresh = true
  dialogOpen.value = true
}

function openEdit(p: PoolDefinition) {
  editName.value = p.name
  form.name = p.name
  form.displayName = p.displayName
  form.markets = [...(p.rules.markets || [])]
  form.exclude_st = p.rules.exclude_st !== false
  form.exclude_delisting = p.rules.exclude_delisting !== false
  form.min_price = p.rules.min_price
  form.max_price = p.rules.max_price
  form.min_market_cap_yi = (p.rules.min_market_cap || 0) / 1e8
  form.max_market_cap_yi = (p.rules.max_market_cap || 0) / 1e8
  form.autoRefresh = p.autoRefresh !== false
  dialogOpen.value = true
}

async function submit() {
  if (!isEdit.value) {
    if (!form.name || !/^[a-z0-9][a-z0-9_-]{0,31}$/.test(form.name)) {
      ElMessage.warning('英文 slug 必须 1-32 位，小写字母/数字/下划线/横线，首位字母数字')
      return
    }
  }
  if (form.markets.length === 0) {
    ElMessage.warning('至少选一个板块')
    return
  }
  if (form.min_price < 0 || form.max_price <= form.min_price) {
    ElMessage.warning('价格区间不合法（min ≥ 0 且 max > min）')
    return
  }
  if (form.min_market_cap_yi < 0 || form.max_market_cap_yi <= form.min_market_cap_yi) {
    ElMessage.warning('流通市值区间不合法')
    return
  }

  const rules = {
    markets: form.markets,
    exclude_st: form.exclude_st,
    exclude_delisting: form.exclude_delisting,
    min_price: form.min_price,
    max_price: form.max_price,
    min_market_cap: form.min_market_cap_yi * 1e8,
    max_market_cap: form.max_market_cap_yi * 1e8
  }
  submitting.value = true
  try {
    if (isEdit.value) {
      await api.pool.update(editName.value!, {
        displayName: form.displayName || undefined,
        rules,
        autoRefresh: form.autoRefresh
      })
      ElMessage.success('已更新')
    } else {
      await api.pool.create({
        name: form.name,
        displayName: form.displayName || form.name,
        rules,
        autoRefresh: form.autoRefresh
      })
      ElMessage.success('已创建，可点"立即构建"生成首份快照')
    }
    dialogOpen.value = false
    await refresh()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.detail || e?.response?.data?.message || '保存失败')
  } finally {
    submitting.value = false
  }
}

async function onRebuild(p: PoolDefinition) {
  try {
    await api.pool.rebuild(p.name)
    ElMessage.success(`池子 ${p.name} 构建已启动，60-120 秒后完成`)
    // 立即拉一次状态把 building 标志反映出来
    statusByName[p.name] = await api.pool.status(p.name)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '触发构建失败')
  }
}

async function onDelete(p: PoolDefinition) {
  if (p.name === 'default') {
    ElMessage.warning('默认池子不能删除')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除池子 [${p.displayName}] (${p.name})？将一并清理快照和历史归档。`,
      '确认删除',
      { type: 'warning' }
    )
    await api.pool.delete(p.name)
    ElMessage.success('已删除')
    delete statusByName[p.name]
    await refresh()
  } catch (e: any) {
    if (e?.message) ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

async function openHistory(p: PoolDefinition) {
  historyPoolName.value = p.name
  historyDialogOpen.value = true
  historyLoading.value = true
  historyList.value = []
  try {
    const r = await api.pool.history(p.name)
    historyList.value = r.history || []
  } catch (e: any) {
    ElMessage.error('加载历史失败: ' + (e?.message || e))
  } finally {
    historyLoading.value = false
  }
}

async function openSnapshot(name: string, date: string) {
  snapshotPoolName.value = name
  snapshotDate.value = date
  snapshotDialogOpen.value = true
  snapshotLoading.value = true
  snapshotCodes.value = []
  try {
    const r = await api.pool.historyDetail(name, date)
    snapshotCodes.value = r.codes || []
  } catch (e: any) {
    ElMessage.error('加载快照详情失败: ' + (e?.message || e))
  } finally {
    snapshotLoading.value = false
  }
}

async function openCurrentSnapshot(name: string) {
  // "今天的快照"等价于直接读 status 里能展示的；但 status 不返回 codes
  // 这里曲线救国：用 history 里最新一条；没有就提示。
  try {
    const r = await api.pool.history(name)
    if (!r.history || r.history.length === 0) {
      ElMessage.info('该池子还没有历史快照，请先点"立即构建"')
      return
    }
    await openSnapshot(name, r.history[0].date)
  } catch (e: any) {
    ElMessage.error(e?.message || '加载快照失败')
  }
}

function rulesSummary(p: PoolDefinition): string {
  const r = p.rules
  const mkt = (r.markets || []).map(m => MARKETS.find(x => x.key === m)?.key.replace('MAIN_', '') || m).join('/')
  const cap = `${(r.min_market_cap / 1e8).toFixed(0)}-${(r.max_market_cap / 1e8).toFixed(0)}亿`
  return `${mkt} · 价 ${r.min_price}-${r.max_price} · 市值 ${cap}`
}

function statusBadge(name: string): { text: string; type: 'success' | 'info' | 'warning' | 'danger' } {
  const s = statusByName[name]
  if (!s) return { text: '加载中', type: 'info' }
  if (s.building) return { text: '构建中', type: 'warning' }
  if (!s.exists) return { text: '无快照', type: 'danger' }
  if (s.is_stale) return { text: '已过期', type: 'warning' }
  return { text: '已就绪', type: 'success' }
}

function statusText(name: string): string {
  const s = statusByName[name]
  if (!s || !s.exists) return '—'
  const ts = (s.updated_at || '').replace('T', ' ').slice(0, 16)
  return `${s.count ?? 0} 只 · ${ts}`
}

onMounted(() => {
  refresh()
})
</script>

<template>
  <div class="pool-manage">
    <div class="header">
      <h2>选股池管理</h2>
      <span class="hint">为不同的 Trader 配置不同的候选股票池；每周五 15:30 自动重建 autoRefresh 池</span>
      <span class="spacer"></span>
      <el-button type="primary" @click="openCreate">+ 新建池子</el-button>
      <el-button @click="refresh" :loading="loading">刷新</el-button>
    </div>

    <el-table :data="pools" v-loading="loading" empty-text="还没有池子" stripe>
      <el-table-column label="名称 / slug" width="220">
        <template #default="{ row }">
          <div class="name-cell">
            <span class="name-text">{{ row.displayName }}</span>
            <span class="slug">{{ row.name }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="规则" min-width="260">
        <template #default="{ row }">{{ rulesSummary(row) }}</template>
      </el-table-column>
      <el-table-column label="自动重建" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.autoRefresh ? 'success' : 'info'" size="small">
            {{ row.autoRefresh ? '周五' : '关闭' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="statusBadge(row.name).type" size="small">{{ statusBadge(row.name).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="快照" min-width="180">
        <template #default="{ row }">
          <a class="snapshot-link" @click="openCurrentSnapshot(row.name)">{{ statusText(row.name) }}</a>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="320" fixed="right" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" plain @click="onRebuild(row)">立即构建</el-button>
          <el-button size="small" @click="openHistory(row)">历史</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" :disabled="row.name === 'default'" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- ========== 创建 / 编辑对话框 ========== -->
    <el-dialog v-model="dialogOpen" :title="isEdit ? `编辑池子 - ${editName}` : '新建池子'" width="640px" :close-on-click-modal="false">
      <el-form label-width="110px">
        <el-form-item label="英文 slug" v-if="!isEdit">
          <el-input v-model="form.name" maxlength="32" placeholder="如：smallcap_growth，1-32 位小写字母/数字/_/-" />
          <span class="form-hint">创建后不可改；将作为 trader 选池的引用 ID</span>
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" maxlength="64" placeholder="如：小盘成长股池" />
        </el-form-item>
        <el-form-item label="板块">
          <el-checkbox-group v-model="form.markets">
            <el-checkbox v-for="m in MARKETS" :key="m.key" :label="m.key">{{ m.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="排除 ST">
          <el-switch v-model="form.exclude_st" />
          <span class="form-hint">名称含 ST/*ST 的股票排除（风险标记）</span>
        </el-form-item>
        <el-form-item label="排除退市">
          <el-switch v-model="form.exclude_delisting" />
          <span class="form-hint">名称含"退"的股票排除</span>
        </el-form-item>
        <el-form-item label="股价区间">
          <el-input-number v-model="form.min_price" :min="0" :max="9999" :step="1" :precision="2" /> 元
          <span style="margin: 0 6px;">~</span>
          <el-input-number v-model="form.max_price" :min="0" :max="9999" :step="1" :precision="2" /> 元
        </el-form-item>
        <el-form-item label="流通市值">
          <el-input-number v-model="form.min_market_cap_yi" :min="0" :max="100000" :step="10" :precision="0" /> 亿
          <span style="margin: 0 6px;">~</span>
          <el-input-number v-model="form.max_market_cap_yi" :min="0" :max="100000" :step="10" :precision="0" /> 亿
        </el-form-item>
        <el-form-item label="自动重建">
          <el-switch v-model="form.autoRefresh" />
          <span class="form-hint">开启后每周五 15:30 自动重建快照</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">{{ isEdit ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <!-- ========== 历史快照列表 ========== -->
    <el-dialog v-model="historyDialogOpen" :title="`历史快照 - ${historyPoolName}`" width="520px">
      <el-table :data="historyList" v-loading="historyLoading" empty-text="没有历史快照（首次构建后才有归档）" stripe>
        <el-table-column prop="date" label="日期" width="120" />
        <el-table-column prop="count" label="入选数量" width="100" align="right" />
        <el-table-column label="构建时间">
          <template #default="{ row }">{{ (row.updated_at || '').replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button size="small" link @click="openSnapshot(historyPoolName, row.date)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- ========== 快照详情（具体股票列表） ========== -->
    <el-dialog v-model="snapshotDialogOpen" :title="`${snapshotPoolName} · ${snapshotDate} · ${snapshotCodes.length} 只`" width="720px">
      <el-table :data="snapshotCodes" v-loading="snapshotLoading" max-height="500" stripe size="small">
        <el-table-column prop="code" label="代码" width="90" />
        <el-table-column prop="name" label="名称" width="120" />
        <el-table-column prop="segment" label="板块" width="100" />
        <el-table-column label="价格" width="90" align="right">
          <template #default="{ row }">{{ row.price?.toFixed(2) ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="流通市值（亿）" align="right">
          <template #default="{ row }">{{ row.liutongshizhi != null ? (row.liutongshizhi / 1e8).toFixed(2) : '-' }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.pool-manage { padding: 16px; height: 100%; overflow: auto; background: var(--brand-bg); }
.header { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.header h2 { margin: 0; font-size: 18px; color: var(--brand-text-primary); }
.header .hint { color: var(--brand-text-secondary); font-size: 13px; }
.header .spacer { flex: 1; }
.form-hint { margin-left: 12px; font-size: 12px; color: var(--brand-text-placeholder); }
.name-cell { display: flex; flex-direction: column; gap: 2px; line-height: 1.2; }
.name-text { font-weight: 600; color: var(--brand-text-primary); }
.slug { font-size: 11px; color: var(--brand-text-secondary); font-family: monospace; }
.snapshot-link { color: var(--brand-primary); cursor: pointer; text-decoration: none; }
.snapshot-link:hover { text-decoration: underline; }
</style>
