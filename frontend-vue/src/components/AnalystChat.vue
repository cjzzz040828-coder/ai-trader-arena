<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'

defineOptions({ name: 'AnalystChat' })

const props = defineProps<{
  code: string | null
  stockName?: string
}>()

interface ChatMsg { role: 'user' | 'assistant'; content: string }

const messages = ref<ChatMsg[]>([])
const input = ref('')
const streaming = ref(false)
const listRef = ref<HTMLElement | null>(null)
let es: EventSource | null = null

// 切换股票时清空对话（上下文换了，旧对话无意义）
watch(() => props.code, () => {
  closeStream()
  messages.value = []
})

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

function closeStream() {
  if (es) { es.close(); es = null }
  streaming.value = false
}

function buildUrl(code: string, q: string, history: ChatMsg[]): string {
  const token = localStorage.getItem('token') || ''
  // 只带最近 6 条历史，避免 URL 过长
  const recent = history.slice(-6)
  const histParam = encodeURIComponent(JSON.stringify(recent))
  return `/api/analyst/stream?code=${encodeURIComponent(code)}`
    + `&q=${encodeURIComponent(q)}`
    + `&history=${histParam}`
    + `&token=${encodeURIComponent(token)}`
}

function send(presetQ?: string) {
  const code = props.code
  if (!code) { ElMessage.warning('请先在左侧选择一只股票'); return }
  if (streaming.value) return
  const q = (presetQ ?? input.value).trim()
  if (!q) return

  const history = messages.value.slice()  // 发送前的历史（不含本轮）
  messages.value.push({ role: 'user', content: q })
  const assistant: ChatMsg = { role: 'assistant', content: '' }
  messages.value.push(assistant)
  input.value = ''
  streaming.value = true
  scrollToBottom()

  es = new EventSource(buildUrl(code, q, history))
  es.addEventListener('delta', (e: MessageEvent) => {
    assistant.content += e.data
    scrollToBottom()
  })
  es.addEventListener('error', (e: MessageEvent) => {
    // SSE 业务错误事件（后端 send name=error）
    if (e.data) {
      assistant.content += (assistant.content ? '\n\n' : '') + '⚠️ ' + e.data
      scrollToBottom()
    }
    closeStream()
  })
  es.addEventListener('done', () => {
    closeStream()
  })
  // 连接层错误（非业务 error 事件）：EventSource onerror
  es.onerror = () => {
    if (!assistant.content) assistant.content = '⚠️ 连接中断或服务不可用'
    closeStream()
  }
}

function clearChat() {
  closeStream()
  messages.value = []
}
</script>

<template>
  <el-card class="analyst-card" shadow="never" :body-style="{ padding: '0' }">
    <div class="analyst-head">
      <span class="title">AI 分析助手</span>
      <span v-if="code" class="cur">{{ stockName || code }} <i>{{ code }}</i></span>
      <span v-else class="cur muted">未选股票</span>
      <span class="spacer"></span>
      <button v-if="messages.length" class="clr" @click="clearChat" title="清空对话">清空</button>
    </div>

    <div class="msg-list" ref="listRef">
      <div v-if="!messages.length" class="empty">
        <p>选中股票后，问我这只票怎么看。</p>
        <div class="quick">
          <button @click="send('这只股票当前怎么看？')" :disabled="!code || streaming">怎么看</button>
          <button @click="send('关键支撑位和压力位在哪？')" :disabled="!code || streaming">支撑压力</button>
          <button @click="send('现在适合买入吗？给出理由和参考价位。')" :disabled="!code || streaming">能买吗</button>
        </div>
      </div>
      <div v-for="(m, i) in messages" :key="i" :class="['bubble', m.role]">
        <div class="role">{{ m.role === 'user' ? '我' : 'AI' }}</div>
        <div class="content">{{ m.content || (streaming && i === messages.length - 1 ? '思考中…' : '') }}</div>
      </div>
    </div>

    <div class="input-row">
      <input
        v-model="input"
        type="text"
        :placeholder="code ? '问点什么…（回车发送）' : '请先选择股票'"
        :disabled="!code || streaming"
        @keyup.enter="send()"
      />
      <button class="send-btn" :disabled="!code || streaming || !input.trim()" @click="send()">
        {{ streaming ? '…' : '发送' }}
      </button>
    </div>
  </el-card>
</template>

<style scoped>
.analyst-card { margin-top: 12px; display: flex; flex-direction: column; }
.analyst-head {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px; border-bottom: 1px solid var(--brand-border, #e5e7eb);
}
.analyst-head .title { font-weight: 600; color: var(--brand-text-primary); }
.analyst-head .cur { font-size: 13px; color: var(--brand-primary); }
.analyst-head .cur i { font-style: normal; color: var(--brand-text-placeholder); font-family: monospace; }
.analyst-head .cur.muted { color: var(--brand-text-placeholder); }
.analyst-head .spacer { flex: 1; }
.analyst-head .clr {
  border: none; background: transparent; cursor: pointer;
  color: var(--brand-text-secondary); font-size: 12px;
}
.analyst-head .clr:hover { color: var(--brand-up); }

.msg-list { height: 320px; overflow-y: auto; padding: 12px 14px; }
.empty { color: var(--brand-text-placeholder); font-size: 13px; text-align: center; padding-top: 24px; }
.empty .quick { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 14px; }
.empty .quick button {
  border: 1px solid var(--brand-border, #e5e7eb); background: var(--brand-bg, #fff);
  border-radius: 14px; padding: 4px 12px; font-size: 12px; cursor: pointer;
  color: var(--brand-text-secondary);
}
.empty .quick button:hover:not(:disabled) { border-color: var(--brand-primary); color: var(--brand-primary); }
.empty .quick button:disabled { opacity: .5; cursor: not-allowed; }

.bubble { margin-bottom: 12px; }
.bubble .role { font-size: 11px; color: var(--brand-text-placeholder); margin-bottom: 3px; }
.bubble.user .role { color: var(--brand-primary); }
.bubble .content {
  white-space: pre-wrap; word-break: break-word; line-height: 1.6; font-size: 13px;
  color: var(--brand-text-primary);
  background: var(--brand-bg-soft, #f5f6f8); border-radius: 8px; padding: 8px 10px;
}
.bubble.user .content { background: var(--brand-primary-soft, rgba(59,130,246,.10)); }

.input-row {
  display: flex; gap: 8px; padding: 10px 14px; border-top: 1px solid var(--brand-border, #e5e7eb);
}
.input-row input {
  flex: 1; border: 1px solid var(--brand-border, #e5e7eb); border-radius: 6px;
  padding: 7px 10px; font-size: 13px; background: var(--brand-bg, #fff); color: var(--brand-text-primary);
}
.input-row input:disabled { background: var(--brand-bg-soft, #f5f6f8); }
.send-btn {
  border: none; background: var(--brand-primary); color: #fff; border-radius: 6px;
  padding: 0 16px; cursor: pointer; font-size: 13px;
}
.send-btn:disabled { opacity: .5; cursor: not-allowed; }
</style>
