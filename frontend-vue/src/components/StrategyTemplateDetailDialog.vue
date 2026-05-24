<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { api, type StrategyTemplateVO } from '@/api'

const props = defineProps<{
  open: boolean
  template: StrategyTemplateVO | null
}>()
const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'instantiated'): void
}>()

const router = useRouter()
const visible = computed({
  get: () => props.open,
  set: v => emit('update:open', v)
})

const isLLM = computed(() => props.template?.strategyType === 'LLM')
const isMA = computed(() => props.template?.strategyType === 'MA')

const form = reactive({
  traderName: '',
  llmApiKey: ''
})
const submitting = ref(false)

watch(() => props.template, t => {
  if (t) {
    const suffix = new Date().toISOString().slice(5, 16).replace(/[-T:]/g, '')
    form.traderName = `${t.name}-${suffix}`
    form.llmApiKey = ''
  }
}, { immediate: true })

const paramSummary = computed(() => {
  const t = props.template
  if (!t) return ''
  const p = t.params || {}
  if (isMA.value) return `MA 短: ${p.maShort ?? '?'} / 长: ${p.maLong ?? '?'}`
  if (isLLM.value) return `Base URL: ${p.llmBaseUrl || '(待用户填写)'} · Model: ${p.llmModel || '?'}`
  return ''
})

const promptText = computed(() => {
  const p = props.template?.params || {}
  return typeof p.llmPrompt === 'string' ? p.llmPrompt : ''
})

async function submit() {
  if (!props.template) return
  if (!form.traderName.trim()) {
    ElMessage.warning('请填写 Trader 名称')
    return
  }
  if (isLLM.value && !form.llmApiKey.trim()) {
    ElMessage.warning('LLM 模板需要填写 API Key')
    return
  }
  submitting.value = true
  try {
    await api.strategyTemplates.instantiate(props.template.id, {
      traderName: form.traderName.trim(),
      llmApiKey: isLLM.value ? form.llmApiKey.trim() : undefined
    })
    ElMessage.success(`已创建 Trader「${form.traderName.trim()}」，前往启用调度`)
    emit('update:open', false)
    emit('instantiated')
    router.push('/traders')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '创建失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="template?.name || '策略详情'"
    width="640px"
    :close-on-click-modal="false">
    <div v-if="template" class="tpl-detail">
      <div class="head">
        <span class="tag" :class="template.strategyType.toLowerCase()">{{ template.strategyType }}</span>
        <span v-for="t in template.tags" :key="t" class="tag-chip">{{ t }}</span>
      </div>

      <div class="section">
        <div class="section-label">策略说明</div>
        <p class="desc">{{ template.description }}</p>
      </div>

      <div class="section">
        <div class="section-label">默认参数</div>
        <div class="param-summary">{{ paramSummary }}</div>
        <pre v-if="isLLM && promptText" class="prompt-box">{{ promptText }}</pre>
      </div>

      <div class="section">
        <div class="section-label">运行指标（基于此模板创建的实例）</div>
        <div class="metrics-row">
          <div><span class="m-label">实例数</span><span class="m-val">{{ template.metrics.instanceCount }}</span></div>
          <div>
            <span class="m-label">平均收益率</span>
            <span class="m-val" :class="template.metrics.avgReturnPct > 0 ? 'up' : (template.metrics.avgReturnPct < 0 ? 'down' : '')">
              {{ template.metrics.avgReturnPct > 0 ? '+' : '' }}{{ template.metrics.avgReturnPct.toFixed(2) }}%
            </span>
          </div>
          <div><span class="m-label">胜率</span><span class="m-val">{{ template.metrics.winRate.toFixed(2) }}%</span></div>
          <div><span class="m-label">交易笔数</span><span class="m-val">{{ template.metrics.totalTrades }}</span></div>
        </div>
        <div v-if="template.metrics.instanceCount === 0" class="empty-hint">暂无运行数据，使用此策略后将开始统计</div>
      </div>

      <el-divider />

      <div class="section">
        <div class="section-label">使用此策略 · 创建 Trader</div>
        <el-form label-width="100px" label-position="left">
          <el-form-item label="Trader 名称">
            <el-input v-model="form.traderName" maxlength="64" placeholder="将作为虚拟账户的名字" />
          </el-form-item>
          <el-form-item v-if="isLLM" label="API Key">
            <el-input v-model="form.llmApiKey" type="password" show-password placeholder="OpenAI 兼容 key，仅写入此 trader" />
          </el-form-item>
          <p class="hint">
            <template v-if="isMA">将以 ¥1,000,000 虚拟资金、停用态创建，可在「Trader 管理」启用调度。</template>
            <template v-else>API Key 仅写入新 trader 行，模板表不会保存；可在 Trader 管理页修改 prompt。</template>
          </p>
        </el-form>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">使用此策略创建</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tpl-detail { max-height: 70vh; overflow-y: auto; padding-right: 4px; }
.head { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 14px; align-items: center; }
.tag {
  font-size: 11px; padding: 2px 8px; border-radius: 3px; font-weight: 700; letter-spacing: 1px;
}
.tag.ma { background: rgba(52, 211, 153, 0.15); color: #34d399; }
.tag.llm { background: rgba(251, 191, 36, 0.15); color: #fbbf24; }
.tag-chip {
  font-size: 11px; padding: 2px 8px;
  background: var(--brand-primary-soft); color: var(--brand-primary);
  border-radius: 3px;
}
.section { margin-bottom: 16px; }
.section-label {
  font-size: 12px; color: var(--brand-text-placeholder);
  letter-spacing: 1px; text-transform: uppercase;
  margin-bottom: 6px;
}
.desc { margin: 0; color: var(--brand-text-regular); line-height: 1.7; font-size: 13px; }
.param-summary {
  font-family: 'Consolas', 'Monaco', monospace; font-size: 13px;
  color: var(--brand-text-primary);
  padding: 8px 12px;
  background: var(--brand-bg-soft);
  border-radius: 4px;
  border-left: 2px solid var(--brand-primary);
}
.prompt-box {
  margin-top: 8px; padding: 12px;
  background: var(--brand-bg-soft);
  border: 1px solid var(--brand-border);
  border-radius: 4px;
  color: var(--brand-text-regular);
  font-size: 12px; line-height: 1.6;
  white-space: pre-wrap; word-break: break-word;
  max-height: 200px; overflow-y: auto;
  font-family: 'Consolas', 'Monaco', monospace;
}
.metrics-row {
  display: grid; grid-template-columns: 1fr 1fr 1fr 1fr;
  gap: 12px;
  padding: 12px;
  background: var(--brand-bg-soft);
  border-radius: 4px;
}
.metrics-row > div { display: flex; flex-direction: column; gap: 4px; }
.m-label { font-size: 11px; color: var(--brand-text-placeholder); }
.m-val { font-size: 16px; font-weight: 600; color: var(--brand-text-primary); }
.m-val.up { color: var(--brand-up); }
.m-val.down { color: var(--brand-down); }
.empty-hint { color: var(--brand-text-placeholder); font-size: 12px; margin-top: 6px; }
.hint { color: var(--brand-text-placeholder); font-size: 12px; margin: 4px 0 0; }
</style>
