<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type StrategyTemplateVO } from '@/api'
import StrategyTemplateCard from '@/components/StrategyTemplateCard.vue'
import StrategyTemplateDetailDialog from '@/components/StrategyTemplateDetailDialog.vue'

defineOptions({ name: 'StrategyMarket' })

const loading = ref(false)
const templates = ref<StrategyTemplateVO[]>([])
const filter = ref<'ALL' | 'MA' | 'LLM'>('ALL')

const selected = ref<StrategyTemplateVO | null>(null)
const detailOpen = ref(false)

const filtered = computed(() => {
  if (filter.value === 'ALL') return templates.value
  return templates.value.filter(t => t.strategyType === filter.value)
})

async function refresh() {
  loading.value = true
  try {
    templates.value = await api.strategyTemplates.list()
  } catch (e: any) {
    ElMessage.error('加载策略模板失败: ' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

function onView(t: StrategyTemplateVO) {
  selected.value = t
  detailOpen.value = true
}

onMounted(refresh)
</script>

<template>
  <div class="strategy-market">
    <div class="page-head">
      <div class="title-block">
        <h2>策略库</h2>
        <span class="hint">官方预置策略模板，一键复制为你的 Trader。指标基于已运行实例聚合。</span>
      </div>
      <div class="spacer"></div>
      <el-radio-group v-model="filter" size="small">
        <el-radio-button label="ALL">全部</el-radio-button>
        <el-radio-button label="MA">MA 策略</el-radio-button>
        <el-radio-button label="LLM">LLM 策略</el-radio-button>
      </el-radio-group>
      <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
    </div>

    <div v-loading="loading" class="grid">
      <StrategyTemplateCard
        v-for="t in filtered"
        :key="t.id"
        :template="t"
        @view="onView" />
      <div v-if="!loading && filtered.length === 0" class="empty">
        当前筛选下没有可用的策略模板
      </div>
    </div>

    <StrategyTemplateDetailDialog
      v-model:open="detailOpen"
      :template="selected"
      @instantiated="refresh" />
  </div>
</template>

<style scoped>
.strategy-market {
  padding: 16px;
  height: 100%;
  overflow: auto;
  background: var(--brand-bg);
}
.page-head {
  display: flex; align-items: center; gap: 14px;
  margin-bottom: 16px;
}
.title-block { display: flex; flex-direction: column; gap: 2px; }
.title-block h2 { margin: 0; font-size: 18px; color: var(--brand-text-primary); }
.title-block .hint { color: var(--brand-text-secondary); font-size: 13px; }
.spacer { flex: 1; }

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
  min-height: 200px;
}
.empty {
  grid-column: 1 / -1;
  text-align: center;
  color: var(--brand-text-placeholder);
  padding: 48px 0;
  font-size: 14px;
}
</style>
