<script setup lang="ts">
import { computed, onMounted, onUnmounted, onActivated, onDeactivated, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTradeStore } from '@/stores/trade'
import { api, type OrderVO } from '@/api'
import LlmActivityPanel from '@/components/LlmActivityPanel.vue'

defineOptions({ name: 'MyTrader' })

const store = useTradeStore()
const dialogOpen = ref(false)
const submitting = ref(false)
const quoteLoading = ref(false)
const currentQuote = ref<{ code: string; name: string; price: number; change_pct: number } | null>(null)
let timer: number | null = null
let quoteDebounce: number | null = null

const showLlmPanel = ref(true)

const form = reactive({
  stockCode: '',
  side: 'BUY' as 'BUY' | 'SELL',
  price: 0,
  amount: 100
})

const trader = computed(() => store.currentTrader)
const showSide = computed(() => showLlmPanel.value && trader.value?.strategyType === 'LLM')

function fmt(n: number | null | undefined, digits = 2): string {
  if (n == null) return '-'
  return Number(n).toFixed(digits)
}

function fmtTime(s: string | null | undefined): string {
  if (!s) return '-'
  return s.replace('T', ' ').replace(/\.\d+$/, '')
}

function statusType(s: OrderVO['status']) {
  return s === 'FILLED' ? 'success' :
         s === 'PENDING' ? 'warning' :
         s === 'CANCELLED' ? 'info' : 'danger'
}

const STATUS_LABEL: Record<OrderVO['status'], string> = {
  PENDING: '已委托',
  FILLED: '已成交',
  CANCELLED: '已撤单',
  REJECTED: '已废单'
}

function openDialog(side: 'BUY' | 'SELL') {
  form.stockCode = ''
  form.side = side
  form.price = 0
  form.amount = 100
  currentQuote.value = null
  dialogOpen.value = true
}

async function fetchQuote(code: string) {
  quoteLoading.value = true
  try {
    const resp = await api.quote(code)
    const item = resp.data?.[0]
    if (item && item.code) {
      currentQuote.value = {
        code: item.code,
        name: item.name,
        price: item.price,
        change_pct: item.change_pct
      }
      // 用户没改过限价（=0）的话，自动填入现价
      if (!form.price || form.price === 0) {
        form.price = Number(item.price.toFixed(3))
      }
      // 同时更新名字缓存供持仓/订单表显示
      if (item.name) store.stockNames[item.code] = item.name
    } else {
      currentQuote.value = null
    }
  } catch (e) {
    currentQuote.value = null
  } finally {
    quoteLoading.value = false
  }
}

watch(() => form.stockCode, (code) => {
  if (quoteDebounce != null) clearTimeout(quoteDebounce)
  if (!/^\d{6}$/.test(code)) {
    currentQuote.value = null
    return
  }
  quoteDebounce = window.setTimeout(() => fetchQuote(code), 400)
})

async function submit() {
  if (!trader.value) {
    ElMessage.error('未选择 trader')
    return
  }
  if (!/^\d{6}$/.test(form.stockCode)) {
    ElMessage.warning('股票代码须为 6 位数字')
    return
  }
  if (form.price <= 0) {
    ElMessage.warning('价格必须大于 0')
    return
  }
  if (form.amount <= 0 || form.amount % 100 !== 0) {
    ElMessage.warning('数量必须是 100 的整数倍')
    return
  }
  submitting.value = true
  try {
    await store.placeOrder({
      traderId: trader.value.id,
      stockCode: form.stockCode,
      side: form.side,
      price: form.price,
      amount: form.amount
    })
    ElMessage.success('下单成功，已挂 PENDING')
    dialogOpen.value = false
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '下单失败')
  } finally {
    submitting.value = false
  }
}

async function onCancel(row: OrderVO) {
  try {
    const sideLabel = row.side === 'BUY' ? '买入' : '卖出'
    const stockLabel = store.stockName(row.stockCode) || row.stockCode
    await ElMessageBox.confirm(`撤销${sideLabel} ${stockLabel} x ${row.amount} ?`, '确认', { type: 'warning' })
    await store.cancelOrder(row.id)
    ElMessage.success('已撤单')
  } catch { /* cancelled */ }
}

function startPolling() {
  if (timer != null) return
  timer = window.setInterval(() => {
    store.fetchTraders().catch(() => {})
    store.fetchPositions().catch(() => {})
  }, 5000)
}

function stopPolling() {
  if (timer != null) {
    clearInterval(timer)
    timer = null
  }
}

onMounted(async () => {
  await store.refreshAll()
  startPolling()
})

onActivated(() => {
  // 从 Dashboard 切回时：定时器已被 deactivated 清掉，重新拉一次 + 启定时器
  if (timer == null) {
    store.refreshAll().catch(() => {})
    startPolling()
  }
})

onDeactivated(() => {
  stopPolling()
})

onUnmounted(() => {
  stopPolling()
  if (quoteDebounce != null) clearTimeout(quoteDebounce)
})
</script>

<template>
  <div class="my-trader" :class="{ 'with-side': showSide }">
    <div v-if="!trader" class="empty">加载中...</div>

    <template v-else>
      <div class="main-col">
      <div class="header-card">
        <div class="title">
          <el-select v-if="store.traders.length > 1"
                     :model-value="store.currentTraderId"
                     @change="(v: number) => store.setCurrentTrader(v)"
                     size="small" style="width: 180px;">
            <el-option v-for="t in store.traders" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
          <span v-else class="name">{{ trader.name }}</span>
          <el-tag size="small">{{ trader.strategyType }}</el-tag>
        </div>
        <div class="metrics">
          <div class="metric">
            <div class="label">总资产</div>
            <div class="value">¥{{ fmt(trader.totalAsset) }}</div>
          </div>
          <div class="metric">
            <div class="label">可用现金</div>
            <div class="value">¥{{ fmt(trader.balance) }}</div>
          </div>
          <div class="metric">
            <div class="label">冻结</div>
            <div class="value">¥{{ fmt(trader.frozenBalance) }}</div>
          </div>
          <div class="metric">
            <div class="label">持仓市值</div>
            <div class="value">¥{{ fmt(trader.marketValue) }}</div>
          </div>
          <div class="metric">
            <div class="label">总盈亏</div>
            <div class="value" :class="trader.totalProfit >= 0 ? 'up' : 'down'">
              {{ trader.totalProfit >= 0 ? '+' : '' }}¥{{ fmt(trader.totalProfit) }}
              ({{ fmt(trader.profitPct) }}%)
            </div>
          </div>
        </div>
        <div class="actions">
          <el-button type="danger" @click="openDialog('BUY')">买入</el-button>
          <el-button type="success" @click="openDialog('SELL')">卖出</el-button>
          <el-button @click="store.refreshAll()">刷新</el-button>
          <el-button v-if="trader.strategyType === 'LLM'" plain
                     @click="showLlmPanel = !showLlmPanel">
            {{ showLlmPanel ? '隐藏 AI 面板' : '显示 AI 面板' }}
          </el-button>
        </div>
      </div>

      <div class="section">
        <h3>持仓 ({{ store.positions.length }})</h3>
        <el-table :data="store.positions" size="small" empty-text="暂无持仓">
          <el-table-column label="代码" width="180">
            <template #default="{ row }">
              <span style="font-weight: 600;">{{ store.stockName(row.stockCode) || '-' }}</span>
              <span style="color: #9ca3af; margin-left: 6px; font-family: Consolas, monospace;">{{ row.stockCode }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="amount" label="数量" width="100" />
          <el-table-column prop="frozenAmount" label="冻结" width="80" />
          <el-table-column label="成本价" width="100">
            <template #default="{ row }">{{ fmt(row.costPrice, 3) }}</template>
          </el-table-column>
          <el-table-column label="现价" width="100">
            <template #default="{ row }">{{ fmt(row.currentPrice, 3) }}</template>
          </el-table-column>
          <el-table-column label="市值" width="120">
            <template #default="{ row }">¥{{ fmt(row.marketValue) }}</template>
          </el-table-column>
          <el-table-column label="盈亏" width="120">
            <template #default="{ row }">
              <span :class="row.profitPct >= 0 ? 'up' : 'down'">
                {{ row.profitPct >= 0 ? '+' : '' }}¥{{ fmt((row.currentPrice - row.costPrice) * row.amount) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="盈亏%" width="100">
            <template #default="{ row }">
              <span :class="row.profitPct >= 0 ? 'up' : 'down'">
                {{ row.profitPct >= 0 ? '+' : '' }}{{ fmt(row.profitPct) }}%
              </span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间">
            <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="section">
        <h3>订单流水 ({{ store.orders.length }})</h3>
        <el-table :data="store.orders" size="small" empty-text="暂无订单" max-height="400">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column label="股票" width="170">
            <template #default="{ row }">
              <span style="font-weight: 600;">{{ store.stockName(row.stockCode) || '-' }}</span>
              <span style="color: #9ca3af; margin-left: 6px; font-family: Consolas, monospace;">{{ row.stockCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="方向" width="70">
            <template #default="{ row }">
              <el-tag :type="row.side === 'BUY' ? 'danger' : 'success'" size="small">
                {{ row.side === 'BUY' ? '买' : '卖' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="价格" width="90">
            <template #default="{ row }">{{ fmt(row.price, 3) }}</template>
          </el-table-column>
          <el-table-column prop="amount" label="数量" width="80" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ STATUS_LABEL[row.status] || row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="成交价" width="90">
            <template #default="{ row }">{{ row.filledPrice ? fmt(row.filledPrice, 3) : '-' }}</template>
          </el-table-column>
          <el-table-column label="时间">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PENDING'" size="small" type="warning"
                         @click="onCancel(row)">撤单</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      </div>
      <aside v-if="showSide" class="side-col">
        <LlmActivityPanel :trader-id="trader.id" />
      </aside>
    </template>

    <el-dialog v-model="dialogOpen" :title="form.side === 'BUY' ? '买入' : '卖出'" width="400px">
      <el-form label-width="80px">
        <el-form-item label="方向">
          <el-radio-group v-model="form.side">
            <el-radio-button label="BUY">买入</el-radio-button>
            <el-radio-button label="SELL">卖出</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="股票代码">
          <el-input v-model="form.stockCode" maxlength="6" placeholder="6位数字，如 000001" />
        </el-form-item>
        <div v-if="form.stockCode.length === 6" class="quote-tip">
          <template v-if="quoteLoading">查询行情中...</template>
          <template v-else-if="currentQuote">
            <span class="q-name">{{ currentQuote.name }}</span>
            <span class="q-price" :class="currentQuote.change_pct >= 0 ? 'up' : 'down'">
              现价 ¥{{ fmt(currentQuote.price, 3) }}
            </span>
            <span :class="currentQuote.change_pct >= 0 ? 'up' : 'down'">
              ({{ currentQuote.change_pct >= 0 ? '+' : '' }}{{ fmt(currentQuote.change_pct) }}%)
            </span>
          </template>
          <template v-else>
            <span style="color: #9ca3af;">未取到行情，请检查代码是否正确或网关是否运行</span>
          </template>
        </div>
        <el-form-item label="限价">
          <el-input-number v-model="form.price" :min="0.001" :precision="3" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="form.amount" :min="100" :step="100" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button :type="form.side === 'BUY' ? 'danger' : 'success'" :loading="submitting" @click="submit">
          确认{{ form.side === 'BUY' ? '买入' : '卖出' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.my-trader { padding: 16px; height: 100%; overflow: auto; background: #f3f4f6; }
.my-trader.with-side { display: grid; grid-template-columns: 1fr 380px; gap: 16px; align-items: start; }
.main-col { min-width: 0; }
.side-col { position: sticky; top: 0; align-self: start; height: calc(100vh - 32px); min-width: 0; }
@media (max-width: 1200px) {
  .my-trader.with-side { grid-template-columns: 1fr; }
  .side-col { position: static; height: auto; max-height: 600px; }
}
.empty { text-align: center; padding: 60px; color: #6b7280; }
.header-card {
  background: #fff; padding: 16px 20px; border-radius: 8px;
  display: flex; align-items: center; gap: 20px; margin-bottom: 16px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.05);
}
.title { display: flex; align-items: center; gap: 8px; min-width: 140px; }
.title .name { font-size: 16px; font-weight: 600; }
.metrics { display: flex; flex: 1; gap: 24px; }
.metric .label { font-size: 12px; color: #6b7280; }
.metric .value { font-size: 16px; font-weight: 600; margin-top: 2px; }
.up { color: #dc2626; }
.down { color: #16a34a; }
.actions { display: flex; gap: 8px; }
.section { background: #fff; padding: 12px 16px; border-radius: 8px; margin-bottom: 16px;
           box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
.section h3 { margin: 0 0 8px; font-size: 14px; color: #374151; }
.quote-tip {
  margin: -8px 0 12px 80px; padding: 6px 10px;
  background: #f9fafb; border-radius: 4px; font-size: 13px;
}
.quote-tip .q-name { font-weight: 600; margin-right: 10px; }
.quote-tip .q-price { font-family: Consolas, monospace; font-weight: 600; margin-right: 4px; }
</style>
