<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { api, type CreateTraderReq, type DecideNowResult, type PoolDefinition, type StrategyTemplateVO, type TraderVO } from '@/api'
import BacktestDialog from '@/components/BacktestDialog.vue'
import IndicatorRuleEditor from '@/components/IndicatorRuleEditor.vue'
import ScriptEditor from '@/components/ScriptEditor.vue'

defineOptions({ name: 'TraderManage' })

const router = useRouter()

const loading = ref(false)
const traders = ref<TraderVO[]>([])
const templateNameById = ref<Map<number, string>>(new Map())
const pools = ref<PoolDefinition[]>([])

const dialogOpen = ref(false)
const submitting = ref(false)
const editId = ref<number | null>(null)
const editingFlat = ref(true)  // 编辑时该 trader 是否处于"无持仓+无冻结"状态
const form = reactive({
  name: '',
  strategyType: 'MANUAL',
  enabled: true,
  initialBalance: 1000000,
  maShort: 5,
  maLong: 20,
  llmBaseUrl: '',
  llmApiKey: '',
  llmModel: '',
  llmPrompt: '',
  indicatorConfigJson: '',
  scriptCode: '',
  poolName: ''
})

const isEdit = computed(() => editId.value != null)
const showEnabled = computed(() => form.strategyType !== 'MANUAL')
const showMA = computed(() => form.strategyType === 'MA')
const showLLM = computed(() => form.strategyType === 'LLM')
const showIndicator = computed(() => form.strategyType === 'INDICATOR')
const showScript = computed(() => form.strategyType === 'SCRIPT')
const initialBalanceDisabled = computed(() => isEdit.value && !editingFlat.value)

async function refresh() {
  loading.value = true
  try {
    traders.value = await api.trade.traders()
  } catch (e: any) {
    ElMessage.error('加载失败: ' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function loadTemplates() {
  try {
    const list = await api.strategyTemplates.list()
    const map = new Map<number, string>()
    for (const t of list) map.set(t.id, t.name)
    templateNameById.value = map
  } catch {
    // 模板加载失败不阻塞主流程，列表里就不显示模板来源
  }
}

async function loadPools() {
  try {
    const r = await api.pool.list()
    pools.value = r.pools || []
  } catch {
    // 池子加载失败不阻塞主流程，下拉框就只有"默认 watchlist"
  }
}

function openCreate() {
  editId.value = null
  editingFlat.value = true
  form.name = ''
  form.strategyType = 'MANUAL'
  form.enabled = true
  form.initialBalance = 1000000
  form.maShort = 5
  form.maLong = 20
  form.llmBaseUrl = ''
  form.llmApiKey = ''
  form.llmModel = ''
  form.llmPrompt = ''
  form.indicatorConfigJson = ''
  form.scriptCode = ''
  form.poolName = ''
  dialogOpen.value = true
}

function openEdit(t: TraderVO) {
  editId.value = t.id
  // marketValue > 0 或冻结资金 > 0 时禁止改 initial_balance
  editingFlat.value = (t.marketValue || 0) <= 0 && (t.frozenBalance || 0) <= 0
  form.name = t.name
  form.strategyType = t.strategyType
  form.enabled = t.enabled
  form.initialBalance = t.initialBalance ?? 1000000
  form.maShort = t.maShort ?? 5
  form.maLong = t.maLong ?? 20
  form.llmBaseUrl = t.llmBaseUrl ?? ''
  form.llmApiKey = ''  // 留空表示不更新 key
  form.llmModel = t.llmModel ?? ''
  form.llmPrompt = t.llmPrompt ?? ''
  form.indicatorConfigJson = t.indicatorConfigJson ?? ''
  form.scriptCode = t.scriptCode ?? ''
  form.poolName = t.poolName ?? ''
  dialogOpen.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入 trader 名称')
    return
  }
  if (form.strategyType === 'MA') {
    if (form.maShort >= form.maLong) {
      ElMessage.warning('ma_short 必须小于 ma_long')
      return
    }
  }
  if (form.strategyType === 'LLM') {
    if (!form.llmBaseUrl.trim() || !form.llmModel.trim()) {
      ElMessage.warning('LLM 策略需要填写 base_url 和 model')
      return
    }
    if (!isEdit.value && !form.llmApiKey.trim()) {
      ElMessage.warning('LLM 策略需要填写 api_key')
      return
    }
  }
  if (form.strategyType === 'INDICATOR') {
    if (!form.indicatorConfigJson || !form.indicatorConfigJson.trim()) {
      ElMessage.warning('请配置至少一个指标和买卖规则')
      return
    }
    try {
      const cfg = JSON.parse(form.indicatorConfigJson)
      if (!cfg.indicators?.length || !cfg.buyRules?.length || !cfg.sellRules?.length) {
        ElMessage.warning('指标 / 买入规则 / 卖出规则均不能为空')
        return
      }
    } catch {
      ElMessage.error('指标配置 JSON 解析失败')
      return
    }
  }
  if (form.strategyType === 'SCRIPT') {
    if (!form.scriptCode || !form.scriptCode.trim()) {
      ElMessage.warning('请编写脚本代码')
      return
    }
    if (!form.scriptCode.includes('decide')) {
      ElMessage.warning('脚本必须定义 function decide()')
      return
    }
  }

  submitting.value = true
  try {
    const payload: CreateTraderReq = {
      name: form.name.trim(),
      strategyType: form.strategyType,
      enabled: form.enabled,
      initialBalance: form.initialBalance,
      maShort: form.maShort,
      maLong: form.maLong,
      llmBaseUrl: form.llmBaseUrl.trim() || undefined,
      llmApiKey: form.llmApiKey.trim() || undefined,
      llmModel: form.llmModel.trim() || undefined,
      llmPrompt: form.llmPrompt.trim() || undefined,
      indicatorConfigJson: form.indicatorConfigJson || undefined,
      scriptCode: form.scriptCode || undefined,
      // 空串表示沿用默认 watchlist；显式选了池就传过去
      poolName: form.poolName ? form.poolName : (isEdit.value ? '' : undefined)
    }
    if (isEdit.value) {
      // 编辑：禁用态下不传 initialBalance，避免后端校验拒绝
      const updatePayload = { ...payload }
      if (initialBalanceDisabled.value) delete (updatePayload as any).initialBalance
      await api.trade.updateTrader(editId.value!, updatePayload)
      ElMessage.success('已更新')
    } else {
      await api.trade.createTrader(payload)
      ElMessage.success('已创建')
    }
    dialogOpen.value = false
    await refresh()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    submitting.value = false
  }
}

async function onToggleEnabled(t: TraderVO) {
  try {
    await api.trade.updateTrader(t.id, { enabled: t.enabled })
    ElMessage.success(t.enabled ? '已启用' : '已停用')
  } catch (e: any) {
    t.enabled = !t.enabled
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

async function onReset(t: TraderVO) {
  try {
    const initial = t.initialBalance ?? 1000000
    await ElMessageBox.confirm(
      `确定重置 [${t.name}]？将撤销所有 PENDING 单、清空持仓，资金归位 ¥${initial.toLocaleString()}。`,
      '确认重置',
      { type: 'warning' }
    )
    await api.trade.resetTrader(t.id)
    ElMessage.success('已重置')
    await refresh()
  } catch (e: any) {
    if (e?.message) ElMessage.error(e?.response?.data?.message || '重置失败')
  }
}

async function onDelete(t: TraderVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除 [${t.name}]？删除后将撤销所有 PENDING 单。历史持仓/订单保留但 trader 不再可见。`,
      '确认删除',
      { type: 'warning' }
    )
    await api.trade.deleteTrader(t.id)
    ElMessage.success('已删除')
    await refresh()
  } catch (e: any) {
    if (e?.message) ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

const testingId = ref<number | null>(null)
async function onTestLlm(t: TraderVO) {
  testingId.value = t.id
  try {
    const r = await api.trade.testLlm(t.id)
    if (r.ok) {
      ElNotification.success({
        title: '✓ LLM 连接成功',
        message: `${r.message}\n模型回复: ${r.reply || '(空)'}`,
        duration: 6000,
        dangerouslyUseHTMLString: false
      })
    } else {
      ElNotification.error({
        title: '✗ LLM 测试失败',
        message: r.message + (r.error ? `\n\n详情: ${r.error.slice(0, 300)}` : ''),
        duration: 10000,
        dangerouslyUseHTMLString: false
      })
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '测试请求失败，请检查后端日志')
  } finally {
    testingId.value = null
  }
}

const decidingId = ref<number | null>(null)
async function onDecideNow(t: TraderVO) {
  if (!t.enabled) {
    ElMessage.warning('请先启用该 trader 的调度（操作列上方启用开关）')
    return
  }

  // LLM 策略可能跑 5-60 秒，直接跳转到 /llm-activity/{id} 实时观察决策过程（SSE 已就绪）
  // 跳转前立刻发起后端决策请求，结果落到 trader 的活动流里，用户在新页面会看到一切发生
  if (t.strategyType === 'LLM') {
    ElMessage.info({ message: `已发起 LLM 决策，跳转到活动页实时观察...`, duration: 3000 })
    // 不 await：发起后立刻跳页，让用户看 SSE 推送
    api.trade.decideNow(t.id).catch(e => {
      ElNotification.error({
        title: '决策请求失败',
        message: e?.response?.data?.message || '请检查 LLM 配置和后端日志',
        duration: 8000
      })
    })
    router.push(`/llm-activity/${t.id}`)
    return
  }

  // 非 LLM 策略：同步等结果（通常 1-10 秒），期间按钮 loading + 顶部提示
  decidingId.value = t.id
  ElMessage.info({ message: `[${t.name}] 决策中，通常 5-15 秒...`, duration: 4000 })
  try {
    const r = await api.trade.decideNow(t.id)
    await refresh()
    showDecideResultDialog(t, r)
  } catch (e: any) {
    ElMessageBox.alert(
      e?.response?.data?.message || e?.message || '决策请求失败，请查看后端日志',
      `✗ 决策失败 - ${t.name}`,
      { type: 'error', confirmButtonText: '我知道了' }
    ).catch(() => {})
  } finally {
    decidingId.value = null
  }
}

/** 展示决策结果。同步策略走这里；LLM 不走这里（直接跳活动页）。 */
function showDecideResultDialog(t: TraderVO, r: DecideNowResult) {
  const headerColor = r.newOrders > 0 ? 'var(--brand-up)' : 'var(--brand-text-secondary)'
  const titleEmoji = r.newOrders > 0 ? '✓' : 'ℹ'
  const title = `${titleEmoji} 决策完成 - ${t.name}`

  // 构造结果内容（用 h() 让我们能放表格 + 跳转按钮）
  const summaryLines = [
    `策略: ${r.strategy}`,
    `市场: ${r.actualMarketOpen ? '开盘中（10s 内撮合）' : '已收盘（挂到下个交易时段）'}`,
    `Watchlist: ${r.watchlistSize} 只 · 耗时 ${r.elapsedMs}ms`,
    `本轮: ${r.newOrders} 单（已成交 ${r.filledNow}，待成交 ${r.totalPending}）`
  ]

  const content = h('div', { style: 'font-size: 13px; line-height: 1.6;' }, [
    h('div', { style: `color: ${headerColor}; font-weight: 600; margin-bottom: 8px;` }, r.message),
    h('div', { style: 'color: var(--brand-text-secondary); margin-bottom: 10px;' },
      summaryLines.map(l => h('div', l))),
    r.newOrderDetails.length > 0
      ? h('div', [
          h('div', { style: 'margin: 8px 0 4px; font-weight: 600;' }, '本轮订单明细：'),
          h('table', { style: 'width:100%; border-collapse: collapse; font-size: 12px;' }, [
            h('thead', h('tr', { style: 'background: var(--brand-surface, #f5f7fa);' }, [
              h('th', { style: 'text-align:left; padding: 4px 8px;' }, '代码'),
              h('th', { style: 'text-align:left; padding: 4px 8px;' }, '名称'),
              h('th', { style: 'text-align:center; padding: 4px 8px;' }, '方向'),
              h('th', { style: 'text-align:right; padding: 4px 8px;' }, '数量'),
              h('th', { style: 'text-align:right; padding: 4px 8px;' }, '价格'),
              h('th', { style: 'text-align:center; padding: 4px 8px;' }, '状态')
            ])),
            h('tbody', r.newOrderDetails.map(o => h('tr', {
              style: 'border-top: 1px solid var(--brand-border, #ebeef5);'
            }, [
              h('td', { style: 'padding: 4px 8px; font-family: monospace;' }, o.code),
              h('td', { style: 'padding: 4px 8px;' }, o.name || '-'),
              h('td', {
                style: `text-align:center; padding: 4px 8px; font-weight:600; color: ${o.side === 'BUY' ? 'var(--brand-up)' : 'var(--brand-down)'};`
              }, o.side === 'BUY' ? '买' : '卖'),
              h('td', { style: 'text-align:right; padding: 4px 8px;' }, String(o.amount)),
              h('td', { style: 'text-align:right; padding: 4px 8px; font-variant-numeric: tabular-nums;' },
                Number(o.price).toFixed(2)),
              h('td', { style: `text-align:center; padding: 4px 8px; color: ${o.status === 'FILLED' ? 'var(--brand-up)' : 'var(--brand-text-secondary)'};` },
                o.status === 'PENDING' ? '待成交' : o.status === 'FILLED' ? '已成交' : o.status)
            ])))
          ])
        ])
      : h('div', { style: 'color: var(--brand-text-placeholder); margin-top: 8px; font-style: italic;' },
          '无新增订单 — 策略未触发任何买卖信号'),
    h('div', { style: 'margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--brand-border, #ebeef5); color: var(--brand-text-secondary); font-size: 12px;' },
      '可前往「我的交易员」查看完整持仓与订单流水')
  ])

  ElMessageBox({
    title,
    message: content,
    showCancelButton: r.newOrders > 0,
    confirmButtonText: r.newOrders > 0 ? '查看我的交易员' : '知道了',
    cancelButtonText: '关闭',
    customStyle: { maxWidth: '640px', width: '92vw' }
  }).then(() => {
    if (r.newOrders > 0) router.push('/my-trader')
  }).catch(() => { /* 用户点取消/关闭，正常关闭对话框 */ })
}

function fmt(n: number | null | undefined): string {
  if (n == null) return '-'
  return Number(n).toFixed(2)
}

const backtestOpen = ref(false)
const backtestTrader = ref<TraderVO | null>(null)
function onBacktest(t: TraderVO) {
  backtestTrader.value = t
  backtestOpen.value = true
}

function strategyTag(t: string) {
  if (t === 'MA') return 'success'
  if (t === 'LLM') return 'warning'
  if (t === 'INDICATOR') return 'primary'
  if (t === 'SCRIPT') return 'danger'
  return 'info'
}

onMounted(() => {
  refresh()
  loadTemplates()
  loadPools()
})
</script>

<template>
  <div class="trader-manage">
    <div class="header">
      <h2>Trader 管理</h2>
      <span class="hint">每个 trader 独立 ¥1,000,000 虚拟账户，MA/LLM 策略每分钟自动决策</span>
      <span class="spacer"></span>
      <el-button type="primary" @click="openCreate">+ 新建 Trader</el-button>
      <el-button @click="refresh" :loading="loading">刷新</el-button>
    </div>

    <el-table :data="traders" v-loading="loading" empty-text="还没有 trader" stripe>
      <el-table-column prop="id" label="ID" width="55" />
      <el-table-column label="名称" width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="name-cell">
            <span class="name-text">{{ row.name }}</span>
            <span v-if="row.templateId && templateNameById.get(row.templateId)" class="tpl-from">
              📋 来自：{{ templateNameById.get(row.templateId) }}
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="策略" width="80">
        <template #default="{ row }">
          <el-tag :type="strategyTag(row.strategyType)" size="small">{{ row.strategyType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="70">
        <template #default="{ row }">
          <el-switch
            v-if="row.strategyType !== 'MANUAL'"
            v-model="row.enabled"
            @change="onToggleEnabled(row)"
          />
          <span v-else class="dash-placeholder">-</span>
        </template>
      </el-table-column>
      <el-table-column label="选股池" width="130">
        <template #default="{ row }">
          <el-tag v-if="row.poolName" size="small" type="info" effect="plain">{{ row.poolName }}</el-tag>
          <span v-else class="dash-placeholder">默认</span>
        </template>
      </el-table-column>
      <el-table-column label="总资产" width="140" align="right">
        <template #default="{ row }">¥{{ fmt(row.totalAsset) }}</template>
      </el-table-column>
      <el-table-column label="可用" width="125" align="right">
        <template #default="{ row }">¥{{ fmt(row.balance) }}</template>
      </el-table-column>
      <el-table-column label="持仓市值" width="125" align="right">
        <template #default="{ row }">¥{{ fmt(row.marketValue) }}</template>
      </el-table-column>
      <el-table-column label="总盈亏" min-width="160" align="right">
        <template #default="{ row }">
          <span :class="row.totalProfit >= 0 ? 'up' : 'down'">
            {{ row.totalProfit >= 0 ? '+' : '' }}¥{{ fmt(row.totalProfit) }}
            ({{ fmt(row.profitPct) }}%)
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="400" fixed="right" align="center">
        <template #default="{ row }">
          <el-button v-if="row.strategyType === 'MA' || row.strategyType === 'LLM' || row.strategyType === 'INDICATOR' || row.strategyType === 'SCRIPT'"
                     size="small" type="success" plain
                     :loading="decidingId === row.id"
                     @click="onDecideNow(row)">决策</el-button>
          <el-button v-if="row.strategyType === 'MA' || row.strategyType === 'INDICATOR' || row.strategyType === 'SCRIPT' || row.strategyType === 'LLM'"
                     size="small" type="info" plain
                     @click="onBacktest(row)">回测</el-button>
          <el-button v-if="row.strategyType === 'LLM'"
                     size="small" type="primary" plain
                     :loading="testingId === row.id"
                     @click="onTestLlm(row)">测试</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="warning" @click="onReset(row)">重置</el-button>
          <el-button size="small" type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" :title="isEdit ? '编辑 Trader' : '新建 Trader'" :width="(showIndicator || showScript) ? '880px' : '560px'" :close-on-click-modal="false">
      <el-form label-width="100px">
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="64" placeholder="如：MA双均线 / LLM-DeepSeek" />
        </el-form-item>
        <el-form-item label="策略类型">
          <el-radio-group v-model="form.strategyType">
            <el-radio-button label="MANUAL">手动</el-radio-button>
            <el-radio-button label="MA">MA双均线</el-radio-button>
            <el-radio-button label="INDICATOR">指标组合</el-radio-button>
            <el-radio-button label="SCRIPT">脚本</el-radio-button>
            <el-radio-button label="LLM">LLM</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="showEnabled" label="启用调度">
          <el-switch v-model="form.enabled" />
          <span class="form-hint">关闭后每分钟调度不会执行此 trader 的策略</span>
        </el-form-item>
        <el-form-item label="初始资产">
          <el-input-number v-model="form.initialBalance"
                           :min="1000" :max="100000000" :step="100000" :precision="2"
                           :disabled="initialBalanceDisabled"
                           style="width: 220px;" />
          <span class="form-hint">
            <template v-if="!isEdit">虚拟账户起始资金，重置时会归位到这个值。范围 ¥1,000 ~ ¥1 亿</template>
            <template v-else-if="initialBalanceDisabled">⚠ 当前持仓非空，需先"重置"清仓后才能修改初始资产</template>
            <template v-else>修改后会同步把可用余额、总盈亏归位</template>
          </span>
        </el-form-item>

        <el-form-item label="选股池">
          <el-select v-model="form.poolName" placeholder="默认 watchlist（不选池子）" clearable style="width: 280px;">
            <el-option label="默认 watchlist（同花顺自选股）" value="" />
            <el-option v-for="p in pools" :key="p.name" :label="`${p.displayName} (${p.name})`" :value="p.name" />
          </el-select>
          <span class="form-hint">选定后调度只在该池子里挑股；可在"选股池"页面管理</span>
        </el-form-item>

        <template v-if="showMA">
          <el-divider content-position="left">MA 参数</el-divider>
          <el-form-item label="短周期">
            <el-input-number v-model="form.maShort" :min="2" :max="60" :step="1" />
            <span class="form-hint">日 K 短均线，常用 5</span>
          </el-form-item>
          <el-form-item label="长周期">
            <el-input-number v-model="form.maLong" :min="3" :max="250" :step="1" />
            <span class="form-hint">日 K 长均线，常用 20。金叉买入、死叉卖出</span>
          </el-form-item>
        </template>

        <template v-if="showIndicator">
          <el-divider content-position="left">指标组合配置</el-divider>
          <el-form-item label="规则" label-position="top" class="indicator-config-item">
            <IndicatorRuleEditor v-model="form.indicatorConfigJson" />
          </el-form-item>
        </template>

        <template v-if="showScript">
          <el-divider content-position="left">JavaScript 脚本</el-divider>
          <el-form-item label="代码" label-position="top" class="indicator-config-item">
            <ScriptEditor v-model="form.scriptCode" />
          </el-form-item>
        </template>

        <template v-if="showLLM">
          <el-divider content-position="left">LLM 配置（OpenAI 兼容）</el-divider>
          <el-form-item label="Base URL">
            <el-input v-model="form.llmBaseUrl" placeholder="https://api.deepseek.com" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input v-model="form.llmApiKey" type="password" show-password
                      :placeholder="isEdit ? '留空表示不修改' : '必填'" />
          </el-form-item>
          <el-form-item label="Model">
            <el-input v-model="form.llmModel" placeholder="如 deepseek-chat / gpt-4o-mini" />
          </el-form-item>
          <el-form-item label="投资策略">
            <el-input v-model="form.llmPrompt" type="textarea" :rows="6"
                      :maxlength="8000" show-word-limit
                      placeholder="在此描述你的投资策略，例如：偏好低估值蓝筹股，回避高波动小盘股；满仓不超过 5 只；单只持仓亏损 8% 止损。LLM 会基于此 prompt + 持仓 + 行情每分钟决策。" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
    <BacktestDialog v-model:open="backtestOpen" :trader="backtestTrader" />
  </div>
</template>

<style scoped>
.trader-manage { padding: 16px; height: 100%; overflow: auto; background: var(--brand-bg); }
.header { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.header h2 { margin: 0; font-size: 18px; color: var(--brand-text-primary); }
.header .hint { color: var(--brand-text-secondary); font-size: 13px; }
.header .spacer { flex: 1; }
.up { color: var(--brand-up); font-weight: 600; }
.down { color: var(--brand-down); font-weight: 600; }
.form-hint { margin-left: 12px; font-size: 12px; color: var(--brand-text-placeholder); }
.dash-placeholder { color: var(--brand-text-placeholder); }
.name-cell { display: flex; flex-direction: column; gap: 2px; line-height: 1.2; }
.name-text { font-weight: 600; color: var(--brand-text-primary); }
.tpl-from { font-size: 11px; color: var(--brand-text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.indicator-config-item :deep(.el-form-item__content) { width: 100%; margin-left: 0 !important; }
</style>
