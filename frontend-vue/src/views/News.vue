<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type NewsItem, type StockSearchItem } from '@/api'

defineOptions({ name: 'News' })

// ---------- 财联社电报 ----------
const clsSymbol = ref<'全部' | '重点'>('全部')
const clsItems = ref<NewsItem[]>([])
const clsLoading = ref(false)
const clsLastRefreshAt = ref<string>('')
let timer: number | null = null

async function refreshCls(silent = false) {
  if (!silent) clsLoading.value = true
  try {
    const resp = await api.news.cls(clsSymbol.value, 50)
    clsItems.value = resp.items || []
    clsLastRefreshAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (e: any) {
    if (!silent) ElMessage.error('财联社拉取失败: ' + (e?.message || e))
  } finally {
    if (!silent) clsLoading.value = false
  }
}

function switchSymbol(s: '全部' | '重点') {
  if (clsSymbol.value === s) return
  clsSymbol.value = s
  refreshCls()
}

// ---------- 个股新闻 ----------
interface RecentItem { code: string; name: string }

const queryText = ref('')                              // 用户输入文本（代码或名称）
const selectedStock = ref<StockSearchItem | null>(null) // 最终选定股票
const stockItems = ref<NewsItem[]>([])
const stockMeta = ref<{ code: string; name: string; count: number } | null>(null)
const stockLoading = ref(false)
const recentStocks = ref<RecentItem[]>(JSON.parse(localStorage.getItem('news_recent_stocks') || '[]'))

let searchTimer: number | null = null

async function querySuggestions(qs: string, cb: (results: any[]) => void) {
  const q = (qs || '').trim()
  if (!q) { cb([]); return }
  // 防抖 200ms（el-autocomplete 自带 debounce，但保险起见）
  try {
    const resp = await api.stockSearch(q, 12)
    const list = (resp.items || []).map(it => ({
      value: `${it.code} ${it.name}`,
      code: it.code,
      name: it.name,
      market: it.market
    }))
    cb(list)
  } catch (e: any) {
    console.error('[news] search failed', e)
    cb([])
  }
}

function onSuggestionSelect(item: any) {
  if (!item || !item.code) return
  selectedStock.value = { code: item.code, name: item.name, market: item.market }
  fetchStockNews()
}

async function fetchStockNews() {
  // 没选中但输入了 6 位代码：直接按代码查
  let target = selectedStock.value
  if (!target) {
    const raw = queryText.value.trim()
    if (/^\d{6}$/.test(raw)) {
      target = { code: raw, name: '', market: '' }
    } else {
      ElMessage.warning('请输入 6 位股票代码，或从搜索建议中选一只股票')
      return
    }
  }
  stockLoading.value = true
  try {
    const resp = await api.news.stock(target.code, 20)
    stockItems.value = resp.items || []
    stockMeta.value = { code: target.code, name: target.name, count: resp.count }
    queryText.value = target.name ? `${target.code} ${target.name}` : target.code
    // 历史记录（最近 8 个），按 code 去重
    const entry: RecentItem = { code: target.code, name: target.name || target.code }
    recentStocks.value = [entry, ...recentStocks.value.filter(r => r.code !== target.code)].slice(0, 8)
    localStorage.setItem('news_recent_stocks', JSON.stringify(recentStocks.value))
  } catch (e: any) {
    ElMessage.error('个股新闻拉取失败: ' + (e?.message || e))
  } finally {
    stockLoading.value = false
  }
}

function pickRecent(r: RecentItem) {
  selectedStock.value = { code: r.code, name: r.name, market: '' }
  queryText.value = `${r.code} ${r.name}`
  fetchStockNews()
}

function clearRecent() {
  recentStocks.value = []
  localStorage.removeItem('news_recent_stocks')
}

// ---------- 工具 ----------
function fmtTime(s: string): string {
  if (!s) return ''
  if (/^\d{2}:\d{2}:\d{2}$/.test(s)) return s
  return s.replace('T', ' ').replace(/\.\d+$/, '')
}

function openLink(url: string) {
  if (!url) return
  window.open(url, '_blank', 'noopener')
}

const clsTotalLabel = computed(() => `${clsItems.value.length} 条 · 最近刷新 ${clsLastRefreshAt.value || '-'}`)

onMounted(() => {
  refreshCls()
  timer = window.setInterval(() => refreshCls(true), 5 * 60 * 1000)
})

onUnmounted(() => {
  if (timer != null) clearInterval(timer)
  if (searchTimer != null) clearTimeout(searchTimer)
})
</script>

<template>
  <div class="news-page">
    <!-- 左侧：财联社电报 -->
    <section class="panel cls-panel">
      <div class="panel-header">
        <h3>财联社电报</h3>
        <span class="hint">5 分钟自动刷新 · {{ clsTotalLabel }}</span>
        <span class="spacer"></span>
        <el-radio-group v-model="clsSymbol" size="small" @change="(v: any) => switchSymbol(v)">
          <el-radio-button label="全部" value="全部" />
          <el-radio-button label="重点" value="重点" />
        </el-radio-group>
        <el-button size="small" :loading="clsLoading" @click="refreshCls(false)" style="margin-left: 8px;">刷新</el-button>
      </div>
      <div class="cls-list" v-loading="clsLoading">
        <div v-if="!clsItems.length && !clsLoading" class="empty">暂无电报</div>
        <article v-for="(n, i) in clsItems" :key="i" class="cls-item">
          <div class="cls-time">{{ fmtTime(n.time) }}</div>
          <div class="cls-body">
            <div v-if="n.title" class="cls-title">{{ n.title }}</div>
            <div v-if="n.content" class="cls-content">{{ n.content }}</div>
          </div>
        </article>
      </div>
    </section>

    <!-- 右侧：个股新闻 -->
    <section class="panel stock-panel">
      <div class="panel-header">
        <h3>个股新闻</h3>
        <span class="hint">东方财富 · 支持代码或股票名搜索</span>
      </div>
      <div class="stock-search">
        <el-autocomplete
          v-model="queryText"
          :fetch-suggestions="querySuggestions"
          placeholder="输入代码或名称（如 600519 / 茅台）"
          clearable
          :debounce="250"
          :trigger-on-focus="false"
          value-key="value"
          style="width: 280px;"
          @select="onSuggestionSelect"
          @keyup.enter="fetchStockNews"
        >
          <template #default="{ item }">
            <div class="sugg-row">
              <span class="sugg-code">{{ item.code }}</span>
              <span class="sugg-name">{{ item.name }}</span>
              <span class="sugg-market">{{ item.market }}</span>
            </div>
          </template>
        </el-autocomplete>
        <el-button type="primary" :loading="stockLoading" @click="fetchStockNews">查询</el-button>
      </div>

      <div v-if="recentStocks.length" class="recent-row">
        <span class="recent-label">最近：</span>
        <el-tag
          v-for="r in recentStocks"
          :key="r.code"
          class="recent-tag"
          @click="pickRecent(r)"
        >{{ r.code }} {{ r.name }}</el-tag>
        <el-button link size="small" @click="clearRecent" style="margin-left: 8px;">清空</el-button>
      </div>

      <div class="stock-result" v-loading="stockLoading">
        <div v-if="stockMeta" class="stock-meta">
          <span class="stock-code">{{ stockMeta.code }}</span>
          <span v-if="stockMeta.name" class="stock-name">{{ stockMeta.name }}</span>
          <span class="stock-count">共 {{ stockMeta.count }} 条新闻</span>
        </div>
        <div v-if="stockMeta && !stockItems.length && !stockLoading" class="empty">该股票暂无新闻</div>
        <div v-if="!stockMeta && !stockLoading" class="empty">请搜索股票（代码或名称）后查看新闻</div>
        <article v-for="(n, i) in stockItems" :key="i" class="stock-item" :class="{ clickable: !!n.url }" @click="openLink(n.url)">
          <div class="stock-item-head">
            <span class="stock-title">{{ n.title }}</span>
            <span class="stock-time">{{ fmtTime(n.time) }}</span>
          </div>
          <div v-if="n.content" class="stock-content">{{ n.content }}</div>
          <div class="stock-foot">
            <span v-if="n.source" class="stock-source">{{ n.source }}</span>
            <span v-if="n.url" class="stock-link">查看原文 →</span>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.news-page {
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: 16px;
  padding: 16px;
  height: 100%;
  overflow: hidden;
  background: var(--brand-bg);
}
.panel {
  display: flex;
  flex-direction: column;
  background: var(--brand-card-bg);
  border: 1px solid var(--brand-border);
  border-radius: 8px;
  overflow: hidden;
}
.panel-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--brand-border);
  background: var(--brand-panel-header-bg, var(--brand-card-bg));
}
.panel-header h3 { margin: 0; font-size: 15px; color: var(--brand-text-primary); }
.panel-header .hint { color: var(--brand-text-secondary); font-size: 12px; }
.panel-header .spacer { flex: 1; }

/* 财联社电报 */
.cls-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}
.cls-item {
  display: flex;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px dashed var(--brand-border);
}
.cls-item:hover { background: var(--brand-hover-bg, rgba(59, 130, 246, 0.04)); }
.cls-time {
  flex-shrink: 0;
  width: 72px;
  color: var(--brand-primary);
  font-size: 12px;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  padding-top: 2px;
}
.cls-body { flex: 1; min-width: 0; }
.cls-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--brand-text-primary);
  margin-bottom: 4px;
  line-height: 1.5;
}
.cls-content {
  font-size: 13px;
  color: var(--brand-text-secondary);
  line-height: 1.6;
  word-break: break-word;
}

/* 个股新闻 */
.stock-search {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--brand-border);
}
.recent-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 16px;
  border-bottom: 1px dashed var(--brand-border);
}
.recent-label { color: var(--brand-text-secondary); font-size: 12px; }
.recent-tag { cursor: pointer; }
.stock-result {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}
.stock-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  background: var(--brand-hover-bg, rgba(59, 130, 246, 0.04));
  font-size: 13px;
}
.stock-code { font-weight: 700; color: var(--brand-primary); }
.stock-name { font-weight: 600; color: var(--brand-text-primary); }
.stock-count { color: var(--brand-text-secondary); }

/* 搜索建议下拉 */
.sugg-row { display: flex; align-items: center; gap: 10px; }
.sugg-code { font-family: 'SF Mono', Menlo, Consolas, monospace; color: var(--brand-primary); font-weight: 600; }
.sugg-name { color: var(--brand-text-primary); flex: 1; }
.sugg-market { color: var(--brand-text-secondary); font-size: 12px; }
.empty {
  text-align: center;
  padding: 60px 16px;
  color: var(--brand-text-secondary);
  font-size: 13px;
}
.stock-item {
  padding: 12px 16px;
  border-bottom: 1px dashed var(--brand-border);
}
.stock-item.clickable { cursor: pointer; }
.stock-item.clickable:hover { background: var(--brand-hover-bg, rgba(59, 130, 246, 0.04)); }
.stock-item-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}
.stock-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--brand-text-primary);
  line-height: 1.5;
  flex: 1;
}
.stock-time {
  flex-shrink: 0;
  color: var(--brand-text-secondary);
  font-size: 12px;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  padding-top: 2px;
}
.stock-content {
  font-size: 13px;
  color: var(--brand-text-secondary);
  line-height: 1.6;
  word-break: break-word;
  margin-bottom: 6px;
}
.stock-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
}
.stock-source { color: var(--brand-text-secondary); }
.stock-link { color: var(--brand-primary); }

/* 移动端折叠 */
@media (max-width: 960px) {
  .news-page { grid-template-columns: 1fr; }
}
</style>
