<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{
  modelValue: string
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
}>()

type IndType = 'RSI' | 'MACD' | 'BOLL' | 'KDJ'
type Op = '<' | '>' | '<=' | '>=' | '==' | 'CROSS_UP' | 'CROSS_DOWN'

interface IndicatorDef {
  id: string
  type: IndType
  params: Record<string, number>
}
interface Rule {
  left: string
  op: Op
  rightMode: 'const' | 'ref'
  rightNumber: number
  rightRef: string
}
interface ConfigState {
  indicators: IndicatorDef[]
  buyRules: Rule[]
  sellRules: Rule[]
  logic: 'AND' | 'OR'
}

const INDICATOR_FIELDS: Record<IndType, string[]> = {
  RSI: ['value'],
  MACD: ['macd', 'signal', 'hist'],
  BOLL: ['upper', 'middle', 'lower'],
  KDJ: ['k', 'd', 'j']
}

const DEFAULT_PARAMS: Record<IndType, Record<string, number>> = {
  RSI: { period: 14 },
  MACD: { fast: 12, slow: 26, signal: 9 },
  BOLL: { period: 20, k: 2 },
  KDJ: { n: 9, k: 3, d: 3 }
}

const OPS: { value: Op; label: string }[] = [
  { value: '<', label: '<  小于' },
  { value: '>', label: '>  大于' },
  { value: '<=', label: '≤  不大于' },
  { value: '>=', label: '≥  不小于' },
  { value: '==', label: '=  等于' },
  { value: 'CROSS_UP', label: '↗ 上穿（金叉）' },
  { value: 'CROSS_DOWN', label: '↘ 下穿（死叉）' }
]

function defaultConfig(): ConfigState {
  return {
    indicators: [{ id: 'rsi1', type: 'RSI', params: { ...DEFAULT_PARAMS.RSI } }],
    buyRules: [{ left: 'rsi1.value', op: '<', rightMode: 'const', rightNumber: 30, rightRef: '' }],
    sellRules: [{ left: 'rsi1.value', op: '>', rightMode: 'const', rightNumber: 70, rightRef: '' }],
    logic: 'AND'
  }
}

const state = ref<ConfigState>(defaultConfig())

watch(() => props.modelValue, (json) => {
  if (!json) {
    state.value = defaultConfig()
    return
  }
  try {
    const parsed = JSON.parse(json)
    state.value = {
      indicators: (parsed.indicators || []).map((d: any) => ({
        id: d.id || '',
        type: (d.type || 'RSI').toUpperCase(),
        params: { ...(DEFAULT_PARAMS[d.type as IndType] || {}), ...(d.params || {}) }
      })),
      buyRules: (parsed.buyRules || []).map(toRuleState),
      sellRules: (parsed.sellRules || []).map(toRuleState),
      logic: parsed.logic === 'OR' ? 'OR' : 'AND'
    }
  } catch {
    state.value = defaultConfig()
  }
}, { immediate: true })

function toRuleState(r: any): Rule {
  const right = r.right
  const isString = typeof right === 'string' && isNaN(Number(right))
  return {
    left: String(r.left || ''),
    op: (r.op || '<') as Op,
    rightMode: isString ? 'ref' : 'const',
    rightNumber: isString ? 0 : Number(right) || 0,
    rightRef: isString ? String(right) : ''
  }
}

// 已定义指标的所有可引用字段 (用于规则下拉)
const fieldOptions = computed(() => {
  const opts: { value: string; label: string }[] = []
  for (const d of state.value.indicators) {
    if (!d.id) continue
    for (const f of INDICATOR_FIELDS[d.type] || []) {
      opts.push({ value: `${d.id}.${f}`, label: `${d.id}.${f}` })
    }
  }
  // 暴露收盘价作为隐含字段
  opts.push({ value: '_close.value', label: '_close.value（收盘价）' })
  return opts
})

const indicatorTypeOptions: { value: IndType; label: string }[] = [
  { value: 'RSI', label: 'RSI 相对强弱' },
  { value: 'MACD', label: 'MACD 异同移动平均' },
  { value: 'BOLL', label: '布林带 BOLL' },
  { value: 'KDJ', label: 'KDJ 随机' }
]

function addIndicator() {
  const used = new Set(state.value.indicators.map(d => d.id))
  let i = 1
  while (used.has(`ind${i}`)) i++
  state.value.indicators.push({ id: `ind${i}`, type: 'RSI', params: { ...DEFAULT_PARAMS.RSI } })
}
function removeIndicator(idx: number) {
  if (state.value.indicators.length <= 1) return
  state.value.indicators.splice(idx, 1)
}
function onTypeChange(d: IndicatorDef, t: IndType) {
  d.type = t
  d.params = { ...DEFAULT_PARAMS[t] }
}

function addRule(list: Rule[]) {
  const left = fieldOptions.value[0]?.value || ''
  list.push({ left, op: '<', rightMode: 'const', rightNumber: 0, rightRef: '' })
}
function removeRule(list: Rule[], idx: number) {
  list.splice(idx, 1)
}

const paramSchema: Record<IndType, { key: string; label: string; min: number; max: number }[]> = {
  RSI: [{ key: 'period', label: '周期', min: 2, max: 100 }],
  MACD: [
    { key: 'fast', label: '快线', min: 2, max: 60 },
    { key: 'slow', label: '慢线', min: 5, max: 100 },
    { key: 'signal', label: '信号', min: 2, max: 60 }
  ],
  BOLL: [
    { key: 'period', label: '周期', min: 2, max: 100 },
    { key: 'k', label: '宽度倍数', min: 0.5, max: 5 }
  ],
  KDJ: [
    { key: 'n', label: 'RSV 周期', min: 2, max: 50 },
    { key: 'k', label: 'K 平滑', min: 1, max: 20 },
    { key: 'd', label: 'D 平滑', min: 1, max: 20 }
  ]
}

// 同步 state → JSON
watch(state, (s) => {
  const dump = {
    indicators: s.indicators.map(d => ({ id: d.id, type: d.type, params: d.params })),
    buyRules: s.buyRules.map(toRuleJson),
    sellRules: s.sellRules.map(toRuleJson),
    logic: s.logic
  }
  emit('update:modelValue', JSON.stringify(dump))
}, { deep: true })

function toRuleJson(r: Rule) {
  return {
    left: r.left,
    op: r.op,
    right: r.rightMode === 'ref' ? r.rightRef : r.rightNumber
  }
}

function isCross(op: Op) { return op === 'CROSS_UP' || op === 'CROSS_DOWN' }
</script>

<template>
  <div class="ind-editor">
    <div class="block">
      <div class="block-head">
        <span class="title">① 选择指标</span>
        <span class="hint">每个指标给一个 id，用于规则里引用其字段</span>
        <span class="spacer"></span>
        <el-button size="small" type="primary" plain @click="addIndicator">+ 添加指标</el-button>
      </div>
      <div v-for="(d, idx) in state.indicators" :key="idx" class="row">
        <el-input v-model="d.id" placeholder="id 如 rsi1" class="cell-id" maxlength="16" />
        <el-select v-model="d.type" class="cell-type" @change="(t: IndType) => onTypeChange(d, t)">
          <el-option v-for="o in indicatorTypeOptions" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
        <div class="params">
          <div v-for="p in paramSchema[d.type]" :key="p.key" class="param">
            <span class="param-label">{{ p.label }}</span>
            <el-input-number v-model="d.params[p.key]" :min="p.min" :max="p.max"
                             :step="p.key === 'k' && d.type === 'BOLL' ? 0.1 : 1"
                             :precision="p.key === 'k' && d.type === 'BOLL' ? 2 : 0"
                             controls-position="right" size="small" />
          </div>
        </div>
        <el-button size="small" type="danger" plain :disabled="state.indicators.length <= 1"
                   @click="removeIndicator(idx)">删除</el-button>
      </div>
    </div>

    <div class="block">
      <div class="block-head">
        <span class="title">② 买入规则</span>
        <span class="hint">满足全部 / 任一规则时买入</span>
        <span class="spacer"></span>
        <el-button size="small" type="primary" plain @click="addRule(state.buyRules)">+ 加规则</el-button>
      </div>
      <div v-for="(r, idx) in state.buyRules" :key="idx" class="row rule-row">
        <el-select v-model="r.left" class="cell-left">
          <el-option v-for="o in fieldOptions" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
        <el-select v-model="r.op" class="cell-op">
          <el-option v-for="o in OPS" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
        <el-radio-group v-model="r.rightMode" size="small" class="cell-mode">
          <el-radio-button label="const" :disabled="isCross(r.op)">常量</el-radio-button>
          <el-radio-button label="ref">字段</el-radio-button>
        </el-radio-group>
        <el-input-number v-if="r.rightMode === 'const' && !isCross(r.op)"
                         v-model="r.rightNumber" :step="0.5" :precision="2"
                         controls-position="right" class="cell-right" />
        <el-select v-else v-model="r.rightRef" class="cell-right">
          <el-option v-for="o in fieldOptions" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
        <el-button size="small" type="danger" plain :disabled="state.buyRules.length <= 1"
                   @click="removeRule(state.buyRules, idx)">×</el-button>
      </div>
    </div>

    <div class="block">
      <div class="block-head">
        <span class="title">③ 卖出规则</span>
        <span class="hint">满足全部 / 任一规则时卖出（仅当有持仓）</span>
        <span class="spacer"></span>
        <el-button size="small" type="primary" plain @click="addRule(state.sellRules)">+ 加规则</el-button>
      </div>
      <div v-for="(r, idx) in state.sellRules" :key="idx" class="row rule-row">
        <el-select v-model="r.left" class="cell-left">
          <el-option v-for="o in fieldOptions" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
        <el-select v-model="r.op" class="cell-op">
          <el-option v-for="o in OPS" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
        <el-radio-group v-model="r.rightMode" size="small" class="cell-mode">
          <el-radio-button label="const" :disabled="isCross(r.op)">常量</el-radio-button>
          <el-radio-button label="ref">字段</el-radio-button>
        </el-radio-group>
        <el-input-number v-if="r.rightMode === 'const' && !isCross(r.op)"
                         v-model="r.rightNumber" :step="0.5" :precision="2"
                         controls-position="right" class="cell-right" />
        <el-select v-else v-model="r.rightRef" class="cell-right">
          <el-option v-for="o in fieldOptions" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
        <el-button size="small" type="danger" plain :disabled="state.sellRules.length <= 1"
                   @click="removeRule(state.sellRules, idx)">×</el-button>
      </div>
    </div>

    <div class="block">
      <div class="block-head">
        <span class="title">④ 多规则关系</span>
        <span class="hint">同一组规则之间的逻辑：AND=全部满足，OR=任一满足</span>
        <span class="spacer"></span>
        <el-radio-group v-model="state.logic" size="small">
          <el-radio-button label="AND">AND 全部满足</el-radio-button>
          <el-radio-button label="OR">OR 任一满足</el-radio-button>
        </el-radio-group>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ind-editor { display: flex; flex-direction: column; gap: 12px; }
.block { border: 1px solid var(--brand-border, #e5e7eb); border-radius: 6px; padding: 10px 12px; background: var(--brand-bg-elev, #fafafa); }
.block-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.block-head .title { font-weight: 600; color: var(--brand-text-primary, #1f2937); font-size: 13px; }
.block-head .hint { color: var(--brand-text-secondary, #6b7280); font-size: 12px; }
.block-head .spacer { flex: 1; }
.row { display: flex; align-items: center; gap: 8px; padding: 6px 0; flex-wrap: wrap; }
.row + .row { border-top: 1px dashed var(--brand-border, #e5e7eb); }
.cell-id { width: 100px; }
.cell-type { width: 170px; }
.params { display: flex; gap: 10px; flex-wrap: wrap; flex: 1; }
.param { display: flex; align-items: center; gap: 4px; }
.param-label { font-size: 12px; color: var(--brand-text-secondary, #6b7280); }
.rule-row .cell-left { width: 180px; }
.rule-row .cell-op { width: 160px; }
.rule-row .cell-mode { flex-shrink: 0; }
.rule-row .cell-right { width: 160px; }
</style>
