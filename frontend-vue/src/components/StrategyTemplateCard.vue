<script setup lang="ts">
import { computed } from 'vue'
import type { StrategyTemplateVO } from '@/api'

const props = defineProps<{ template: StrategyTemplateVO }>()
defineEmits<{ (e: 'view', t: StrategyTemplateVO): void }>()

const isLLM = computed(() => props.template.strategyType === 'LLM')
const isMA = computed(() => props.template.strategyType === 'MA')

const paramSummary = computed(() => {
  const p = props.template.params || {}
  if (isMA.value) {
    return `${p.maShort ?? '?'} / ${p.maLong ?? '?'}`
  }
  if (isLLM.value) {
    return p.llmModel || '需自备 LLM'
  }
  return '-'
})

const returnClass = computed(() => {
  const r = props.template.metrics.avgReturnPct
  if (r > 0) return 'up'
  if (r < 0) return 'down'
  return 'neutral'
})

function fmtPct(v: number, withSign = false): string {
  if (v == null || Number.isNaN(v)) return '-'
  const s = v.toFixed(2) + '%'
  return withSign && v > 0 ? '+' + s : s
}
</script>

<template>
  <div class="tpl-card" :class="{ llm: isLLM, ma: isMA }">
    <div class="card-head">
      <span class="tag" :class="template.strategyType.toLowerCase()">{{ template.strategyType }}</span>
      <span class="name">{{ template.name }}</span>
    </div>
    <p class="desc">{{ template.description }}</p>
    <div class="metrics">
      <div class="metric">
        <div class="label">实例数</div>
        <div class="value">{{ template.metrics.instanceCount }}</div>
      </div>
      <div class="metric">
        <div class="label">平均收益率</div>
        <div class="value" :class="returnClass">{{ fmtPct(template.metrics.avgReturnPct, true) }}</div>
      </div>
      <div class="metric">
        <div class="label">胜率</div>
        <div class="value">{{ fmtPct(template.metrics.winRate) }}</div>
      </div>
      <div class="metric">
        <div class="label">交易笔数</div>
        <div class="value">{{ template.metrics.totalTrades }}</div>
      </div>
      <div class="metric">
        <div class="label">参数</div>
        <div class="value param-val" :title="paramSummary">{{ paramSummary }}</div>
      </div>
      <div class="metric">
        <div class="label">标签</div>
        <div class="value tags">
          <span v-for="t in template.tags" :key="t" class="tag-chip">{{ t }}</span>
          <span v-if="!template.tags.length" class="dim">-</span>
        </div>
      </div>
    </div>
    <div class="actions">
      <el-button type="primary" plain size="small" @click="$emit('view', template)">查看 / 使用</el-button>
    </div>
  </div>
</template>

<style scoped>
.tpl-card {
  background: var(--brand-surface);
  border: 1px solid var(--brand-border);
  border-radius: 8px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  transition: border-color 0.15s, box-shadow 0.15s;
  height: 100%;
}
.tpl-card:hover {
  border-color: var(--brand-primary);
  box-shadow: var(--brand-shadow-md);
}
.card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.tag {
  font-size: 11px; padding: 2px 8px; border-radius: 3px; font-weight: 700; letter-spacing: 1px;
}
.tag.ma { background: rgba(52, 211, 153, 0.15); color: #34d399; }
.tag.llm { background: rgba(251, 191, 36, 0.15); color: #fbbf24; }
.name {
  font-size: 16px; font-weight: 700; color: var(--brand-text-primary);
}
.desc {
  color: var(--brand-text-secondary); font-size: 13px; line-height: 1.6;
  margin: 0 0 14px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 42px;
}
.metrics {
  display: grid; grid-template-columns: 1fr 1fr 1fr;
  gap: 10px 14px;
  padding: 12px;
  background: var(--brand-bg-soft);
  border-radius: 6px;
  margin-bottom: 14px;
}
.metric .label {
  font-size: 11px; color: var(--brand-text-placeholder); margin-bottom: 4px;
}
.metric .value {
  font-size: 15px; font-weight: 600; color: var(--brand-text-primary);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.metric .value.up { color: var(--brand-up); }
.metric .value.down { color: var(--brand-down); }
.metric .value.neutral { color: var(--brand-text-secondary); }
.metric .param-val { font-family: 'Consolas', 'Monaco', monospace; font-size: 13px; }
.metric .tags { display: flex; gap: 4px; flex-wrap: wrap; }
.tag-chip {
  font-size: 10px; padding: 1px 6px;
  background: var(--brand-primary-soft); color: var(--brand-primary);
  border-radius: 2px;
}
.dim { color: var(--brand-text-placeholder); font-size: 13px; }
.actions { margin-top: auto; display: flex; justify-content: flex-end; }
</style>
