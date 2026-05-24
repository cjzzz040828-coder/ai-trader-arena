<script setup lang="ts">
import { computed, onMounted, onUnmounted, onActivated, onDeactivated, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTradeStore } from '@/stores/trade'
import { api, type OrderVO } from '@/api'

defineOptions({ name: 'MyTrader' })

const store = useTradeStore()
const dialogOpen = ref(false)
const submitting = ref(false)
const quoteLoading = ref(false)
const currentQuote = ref<{ code: string; name: string; price: number; change_pct: number } | null>(null)
let timer: number | null = null
let quoteDebounce: number | null = null

const form = reactive({
  stockCode: '',
  side: 'BUY' as 'BUY' | 'SELL',
  price: 0,
  amount: 100
})

const trader = computed(() => store.currentTrader)

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
  <div class="my-trader">
    <div v-if="!trader" class="empty">加载中...</div>

    <template v-else>
      <div class="main-col">
      <el-card class="header-card" :body-style="{ padding: '16px 20px', display: 'flex', alignItems: 'center', gap: '20px' }">
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
          <el-button class="btn-buy" @click="openDialog('BUY')">买入</el-button>
          <el-button class="btn-sell" @click="openDialog('SELL')">卖出</el-button>
          <el-button @click="store.refreshAll()">刷新</el-button>
        </div>
      </el-card>

      <el-card class="section" :body-style="{ padding: '12px 16px' }">
        <template #header>
          <h3>持仓 ({{ store.positions.length }})</h3>
        </template>
        <el-table :data="store.positions" size="small" empty-text="暂无持仓">
          <el-table-column label="代码" width="180">
            <template #default="{ row }">
              <span style="font-weight: 600;">{{ store.stockName(row.stockCode) || '-' }}</span>
              <span class="stock-code">{{ row.stockCode }}</span>
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
      </el-card>

      <el-card class="section" :body-style="{ padding: '12px 16px' }">
        <template #header>
          <h3>订单流水 ({{ store.orders.length }})</h3>
        </template>
        <el-table :data="store.orders" size="small" empty-text="暂无订单" max-height="400">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column label="股票" width="170">
            <template #default="{ row }">
              <span style="font-weight: 600;">{{ store.stockName(row.stockCode) || '-' }}</span>
              <span class="stock-code">{{ row.stockCode }}</span>
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
            <template #default="{ row }: { row: OrderVO }">
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
      </el-card>
      </div>
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
            <span class="placeholder">未取到行情，请检查代码是否正确或网关是否运行</span>
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
        <el-button :class="form.side === 'BUY' ? 'btn-buy' : 'btn-sell'" :loading="submitting" @click="submit">
          确认{{ form.side === 'BUY' ? '买入' : '卖出' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.my-trader { padding: 16px; height: 100%; overflow: auto; background: var(--brand-bg); }
.main-col { min-width: 0; }
.empty { text-align: center; padding: 60px; color: var(--brand-text-secondary); }

.header-card { border-radius: 8px; margin-bottom: 16px; }
.title { display: flex; align-items: center; gap: 8px; min-width: 140px; }
.title .name { font-size: 16px; font-weight: 600; color: var(--brand-text-primary); }
.metrics { display: flex; flex: 1; gap: 24px; }
.metric .label { font-size: 12px; color: var(--brand-text-secondary); }
.metric .value { font-size: 16px; font-weight: 600; margin-top: 2px; color: var(--brand-text-primary); }
/* 显式覆盖：.metric .value (0,2,0) 比 .up/.down (0,1,0) 更具体，会盖住红/绿色。
   用 .value.up/.value.down 把 specificity 提到 0,2,0，且写在后面赢平局。 */
.metric .value.up { color: var(--brand-up); }
.metric .value.down { color: var(--brand-down); }
.up { color: var(--brand-up); }
.down { color: var(--brand-down); }
.actions { display: flex; gap: 8px; }

.section { border-radius: 8px; margin-bottom: 16px; }
.section :deep(.el-card__header) { padding: 10px 16px; }
.section h3 { margin: 0; font-size: 14px; color: var(--brand-text-regular); font-weight: 600; }

.stock-code {
  color: var(--brand-text-placeholder);
  margin-left: 6px;
  font-family: Consolas, monospace;
}

.quote-tip {
  margin: -8px 0 12px 80px; padding: 6px 10px;
  background: var(--brand-bg-soft); border-radius: 4px; font-size: 13px;
}
.quote-tip .q-name { font-weight: 600; margin-right: 10px; }
.quote-tip .q-price { font-family: Consolas, monospace; font-weight: 600; margin-right: 4px; }
.quote-tip .placeholder { color: var(--brand-text-placeholder); }

/* A 股语义按钮：买红卖绿（暗色科技感版） */
.btn-buy {
  --el-button-bg-color: var(--brand-up);
  --el-button-border-color: var(--brand-up);
  --el-button-hover-bg-color: #fca5a5;
  --el-button-hover-border-color: #fca5a5;
  --el-button-text-color: #fff;
  --el-button-active-bg-color: #ef4444;
  --el-button-active-border-color: #ef4444;
  box-shadow: 0 0 10px rgba(248, 113, 113, 0.22);
}
.btn-sell {
  --el-button-bg-color: var(--brand-down);
  --el-button-border-color: var(--brand-down);
  --el-button-hover-bg-color: #6ee7b7;
  --el-button-hover-border-color: #6ee7b7;
  --el-button-text-color: #fff;
  --el-button-active-bg-color: #10b981;
  --el-button-active-border-color: #10b981;
  box-shadow: 0 0 10px rgba(52, 211, 153, 0.22);
}
</style>
