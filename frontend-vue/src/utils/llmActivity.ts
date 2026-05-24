// LlmActivity 页面用的纯渲染辅助 + 决策分组逻辑
import type { LlmActivity, LlmActivityPhase } from '@/api'

export interface DecisionGroup {
  decisionId: number
  events: LlmActivity[]
  phase: LlmActivityPhase
  startedAt: string
  finishedAt: string
  finalMessage: string
  promptJson: string | null
}

export const PHASE_LABEL: Record<LlmActivityPhase, string> = {
  started: '开始',
  tool_call: '调用工具',
  tool_result: '工具返回',
  final: '完成',
  failed: '失败',
  cancel_requested: '中断中…',
  cancelled: '已中断',
  purged: '已清空'
}

export const PHASE_COLOR: Record<LlmActivityPhase, 'primary' | 'warning' | 'success' | 'info' | 'danger'> = {
  started: 'primary',
  tool_call: 'warning',
  tool_result: 'success',
  final: 'info',
  failed: 'danger',
  cancel_requested: 'warning',
  cancelled: 'info',
  purged: 'info'
}

export const PHASE_ICON: Record<LlmActivityPhase, string> = {
  started: '▶',
  tool_call: '→',
  tool_result: '✓',
  final: '✓✓',
  failed: '✗',
  cancel_requested: '⏸',
  cancelled: '⏹',
  purged: '🧹'
}

export const TOOL_LABELS: Record<string, string> = {
  get_stock_analysis: '股票分析',
  get_minute_chart: '分时图',
  get_recent_trades: '历史成交',
  place_order: '下单'
}

export const TOOL_ICONS: Record<string, string> = {
  get_stock_analysis: '🔍',
  get_minute_chart: '📈',
  get_recent_trades: '📋',
  place_order: '💰'
}

export const RUNNING_PHASES: LlmActivityPhase[] = ['started', 'tool_call', 'tool_result', 'cancel_requested']
export const DONE_PHASES: LlmActivityPhase[] = ['final', 'failed', 'cancelled']

export function isRunning(phase: LlmActivityPhase): boolean {
  return RUNNING_PHASES.includes(phase)
}

export function toolLabel(name: string | null): string {
  if (!name) return ''
  return TOOL_LABELS[name] || name
}

export function toolIcon(name: string | null): string {
  if (!name) return ''
  return TOOL_ICONS[name] || '🔧'
}

export function fmtClock(s: string): string {
  // 只取 HH:mm:ss
  return s.replace('T', ' ').replace(/\.\d+$/, '').slice(11, 19)
}

export function fmtDateTime(s: string): string {
  return s.replace('T', ' ').replace(/\.\d+$/, '')
}

export function safeParse(json: string | null): any {
  if (!json) return null
  try { return JSON.parse(json) } catch { return null }
}

function pct(v: any): string {
  if (v == null) return '-'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return (n >= 0 ? '+' : '') + n.toFixed(2) + '%'
}

export function argsSummary(e: LlmActivity): string {
  if (e.phase !== 'tool_call') return ''
  const a = safeParse(e.argsJson)
  if (!a) return ''
  switch (e.toolName) {
    case 'get_stock_analysis': return `分析 ${a.code || '?'}`
    case 'get_minute_chart':   return `查 ${a.code || '?'} 分时`
    case 'get_recent_trades':  return `查最近 ${a.limit || 20} 笔成交`
    case 'place_order': {
      const side = a.side === 'BUY' ? '买入' : a.side === 'SELL' ? '卖出' : a.side
      const price = a.price ? `@¥${a.price}` : '@现价'
      return `${side} ${a.code || '?'} ${a.amount || 0} 股 ${price}`
    }
    default: return JSON.stringify(a).slice(0, 120)
  }
}

export function resultSummary(e: LlmActivity): string {
  if (e.phase !== 'tool_result') return ''
  const r = safeParse(e.resultJson)
  if (!r) return ''
  if (r.ok === false || r.error) return `失败: ${r.error || r.message || '未知错误'}`
  switch (e.toolName) {
    case 'get_stock_analysis': {
      const parts: string[] = []
      if (r.name) parts.push(r.name)
      if (r.current_price != null) parts.push(`现价 ¥${r.current_price}`)
      if (r.ma5 != null && r.ma10 != null && r.ma20 != null) parts.push(`MA5=${r.ma5} MA10=${r.ma10} MA20=${r.ma20}`)
      const dyn: string[] = []
      if (r.change_5d_pct != null) dyn.push(`5日 ${pct(r.change_5d_pct)}`)
      if (r.change_20d_pct != null) dyn.push(`20日 ${pct(r.change_20d_pct)}`)
      if (r.vol_ratio_vs_5d_avg != null) dyn.push(`量比 ${r.vol_ratio_vs_5d_avg}`)
      if (dyn.length) parts.push(dyn.join(' / '))
      return parts.join(' · ')
    }
    case 'get_minute_chart':  return `${r.name || r.code || ''} - ${r.samples?.length || 0} 个采样`
    case 'get_recent_trades': return `共 ${r.count || 0} 笔历史成交`
    case 'place_order':
      if (r.ok) return `挂单成功 #${r.order_id} (${r.status || ''}) ${r.message || ''}`.trim()
      return `挂单失败: ${r.error || '未知'}`
    default: return JSON.stringify(r).slice(0, 200)
  }
}

export function isResultOk(e: LlmActivity): boolean {
  if (e.phase !== 'tool_result') return true
  const r = safeParse(e.resultJson)
  if (!r) return true
  return r.ok !== false && !r.error
}

export function decisionStateIcon(d: DecisionGroup): string {
  if (isRunning(d.phase)) return '🔄'
  if (d.phase === 'cancelled') return '⏹'
  if (d.phase === 'failed') return '✗'
  return '✓'
}

/** 把扁平事件列表分组为决策（按 decisionId）。最近优先。 */
export function groupByDecision(events: LlmActivity[]): DecisionGroup[] {
  const map = new Map<number, DecisionGroup>()
  for (const ev of events) {
    let g = map.get(ev.decisionId)
    if (!g) {
      g = {
        decisionId: ev.decisionId,
        events: [],
        phase: ev.phase,
        startedAt: ev.createdAt,
        finishedAt: ev.createdAt,
        finalMessage: '',
        promptJson: null
      }
      map.set(ev.decisionId, g)
    }
    if (g.events.some(e => e.seq === ev.seq)) continue
    g.events.push(ev)
    g.phase = ev.phase
    g.finishedAt = ev.createdAt
    if (DONE_PHASES.includes(ev.phase)) g.finalMessage = ev.message || ''
    if (ev.promptJson && !g.promptJson) g.promptJson = ev.promptJson
  }
  // 内部按 seq 升序
  for (const g of map.values()) g.events.sort((a, b) => a.seq - b.seq)
  return Array.from(map.values()).sort((a, b) => b.decisionId - a.decisionId)
}

/** 增量合并一个事件到决策组 map。返回是否真的合入（去重） */
export function mergeEventInto(
  map: Map<number, DecisionGroup>,
  ev: LlmActivity,
  maxDecisions = 5
): boolean {
  let g = map.get(ev.decisionId)
  if (!g) {
    g = {
      decisionId: ev.decisionId,
      events: [],
      phase: ev.phase,
      startedAt: ev.createdAt,
      finishedAt: ev.createdAt,
      finalMessage: '',
      promptJson: null
    }
    map.set(ev.decisionId, g)
  }
  if (g.events.some(e => e.seq === ev.seq)) return false
  g.events.push(ev)
  g.events.sort((a, b) => a.seq - b.seq)
  g.phase = ev.phase
  g.finishedAt = ev.createdAt
  if (DONE_PHASES.includes(ev.phase)) g.finalMessage = ev.message || ''
  if (ev.promptJson && !g.promptJson) g.promptJson = ev.promptJson
  if (maxDecisions > 0 && map.size > maxDecisions) {
    const ids = Array.from(map.keys()).sort((a, b) => a - b)
    while (map.size > maxDecisions) {
      const oldest = ids.shift()
      if (oldest != null) map.delete(oldest)
    }
  }
  return true
}

/** Dashboard 卡片"当前状态摘要"。
 *  - 有进行中决策时：返回"正在 XXX"（从最近一个 tool_call 的 args 提取）
 *  - 否则：返回最近一次 final/failed/cancelled 的简短文本
 *  - 全空时：返回 null
 */
export function getCurrentDecisionSummary(decisions: DecisionGroup[]): string | null {
  if (decisions.length === 0) return null
  const top = decisions[0]
  if (isRunning(top.phase)) {
    // 找最近一个 tool_call（按 seq 倒序），提取人类可读的进行中描述
    for (let i = top.events.length - 1; i >= 0; i--) {
      const e = top.events[i]
      if (e.phase === 'tool_call' && e.toolName) {
        const a = argsSummary(e)
        return a ? `正在${a}` : `正在${toolLabel(e.toolName)}`
      }
    }
    return '正在思考…'
  }
  // 已结束：用最近一次的总结
  if (top.finalMessage) {
    return top.finalMessage.slice(0, 60)
  }
  return PHASE_LABEL[top.phase] || null
}

/** Dashboard 卡片状态点的取色依据。 */
export function getLatestDecisionStatus(decisions: DecisionGroup[]):
    { phase: LlmActivityPhase; icon: string; label: string; finalMessage?: string } | null {
  if (decisions.length === 0) return null
  const top = decisions[0]
  return {
    phase: top.phase,
    icon: decisionStateIcon(top),
    label: PHASE_LABEL[top.phase] || top.phase,
    finalMessage: top.finalMessage || undefined
  }
}

export interface DecisionPrompt {
  system: string
  user: string
}

/** 解析 started 事件里塞进来的 promptJson。失败/为空返回 null。 */
export function parsePrompt(json: string | null): DecisionPrompt | null {
  const r = safeParse(json)
  if (!r || typeof r !== 'object') return null
  const system = typeof r.system === 'string' ? r.system : ''
  const user = typeof r.user === 'string' ? r.user : ''
  if (!system && !user) return null
  return { system, user }
}

/** 决策耗时（毫秒）。无法解析时返回 null。 */
export function decisionDurationMs(d: DecisionGroup): number | null {
  const t1 = Date.parse(d.startedAt.replace(' ', 'T'))
  const t2 = Date.parse(d.finishedAt.replace(' ', 'T'))
  if (Number.isNaN(t1) || Number.isNaN(t2)) return null
  if (t2 < t1) return null
  return t2 - t1
}

export interface SymbolAction {
  key: string
  code: string
  side: 'BUY' | 'SELL' | string
  amount: number | null
  price: string | null
  ok: boolean
  pending: boolean
  label: string       // 成功/失败摘要文本
  errorMsg?: string
  orderId?: number | string
  status?: string
}

/** 抽取一个决策里所有 place_order 的动作（call + result 配对）。
 *  - 未配对的 tool_call（pending 中）也会出现：ok=false, pending=true
 *  - 用 toolCallId 配对；fallback：同 code 最近一个未匹配的 call
 */
export function extractSymbolActions(d: DecisionGroup): SymbolAction[] {
  const calls = new Map<string, LlmActivity>()  // toolCallId → call event
  const results: Array<{ call: LlmActivity | null; result: LlmActivity }> = []
  const orphanCalls: LlmActivity[] = []

  for (const e of d.events) {
    if (e.toolName !== 'place_order') continue
    if (e.phase === 'tool_call') {
      if (e.toolCallId) calls.set(e.toolCallId, e)
      else orphanCalls.push(e)
    } else if (e.phase === 'tool_result') {
      let call: LlmActivity | null = null
      if (e.toolCallId && calls.has(e.toolCallId)) {
        call = calls.get(e.toolCallId)!
        calls.delete(e.toolCallId)
      } else if (orphanCalls.length > 0) {
        call = orphanCalls.shift()!
      }
      results.push({ call, result: e })
    }
  }

  const out: SymbolAction[] = []
  for (const { call, result } of results) {
    const args = safeParse(call?.argsJson || null) || {}
    const r = safeParse(result.resultJson) || {}
    const ok = r.ok !== false && !r.error
    out.push({
      key: `${result.seq}-r`,
      code: String(args.code || r.code || '?'),
      side: String(args.side || ''),
      amount: typeof args.amount === 'number' ? args.amount : null,
      price: args.price != null ? String(args.price) : null,
      ok,
      pending: false,
      label: ok
        ? `挂单成功 #${r.order_id ?? ''} ${r.status || ''} ${r.message || ''}`.trim()
        : `失败: ${r.error || r.message || '未知错误'}`,
      errorMsg: ok ? undefined : (r.error || r.message),
      orderId: r.order_id,
      status: r.status
    })
  }
  // 剩下未配对的 call → pending
  for (const call of [...calls.values(), ...orphanCalls]) {
    const args = safeParse(call.argsJson) || {}
    out.push({
      key: `${call.seq}-c`,
      code: String(args.code || '?'),
      side: String(args.side || ''),
      amount: typeof args.amount === 'number' ? args.amount : null,
      price: args.price != null ? String(args.price) : null,
      ok: false,
      pending: true,
      label: '等待返回…'
    })
  }
  return out
}

/** 时间轴里的一个步骤：把成对的 tool_call + tool_result 合并为一个 step；
 *  其他 phase（started/final/failed/cancelled/cancel_requested）自成 meta step。 */
export interface TimelineStep {
  key: string
  kind: 'meta' | 'tool'
  phase: LlmActivityPhase            // 升级后的 phase（tool_call → tool_result 表示已完成）
  argsEvent: LlmActivity | null      // tool_call 事件
  resultEvent: LlmActivity | null    // tool_result 事件（pending 时为 null）
  metaEvent: LlmActivity | null      // started/final/...
  toolName: string | null
  round: number | null
  createdAt: string
}

export function buildSteps(events: LlmActivity[]): TimelineStep[] {
  const steps: TimelineStep[] = []
  const pendingByCallId = new Map<string, TimelineStep>()

  for (const e of events) {
    if (e.phase === 'tool_call') {
      const step: TimelineStep = {
        key: `s-${e.seq}-call`,
        kind: 'tool',
        phase: 'tool_call',
        argsEvent: e,
        resultEvent: null,
        metaEvent: null,
        toolName: e.toolName,
        round: e.round,
        createdAt: e.createdAt
      }
      steps.push(step)
      if (e.toolCallId) pendingByCallId.set(e.toolCallId, step)
    } else if (e.phase === 'tool_result') {
      let target: TimelineStep | undefined
      if (e.toolCallId) target = pendingByCallId.get(e.toolCallId)
      if (!target) {
        // 配对 fallback：找最近一个无 result 且 toolName 相同的 tool step
        for (let i = steps.length - 1; i >= 0; i--) {
          const s = steps[i]
          if (s.kind === 'tool' && !s.resultEvent && s.toolName === e.toolName) {
            target = s
            break
          }
        }
      }
      if (target) {
        target.resultEvent = e
        target.phase = 'tool_result'
        if (e.toolCallId) pendingByCallId.delete(e.toolCallId)
      } else {
        // 孤儿 tool_result，单独成 step
        steps.push({
          key: `s-${e.seq}-orphan`,
          kind: 'tool',
          phase: 'tool_result',
          argsEvent: null,
          resultEvent: e,
          metaEvent: null,
          toolName: e.toolName,
          round: e.round,
          createdAt: e.createdAt
        })
      }
    } else {
      steps.push({
        key: `s-${e.seq}-meta`,
        kind: 'meta',
        phase: e.phase,
        argsEvent: null,
        resultEvent: null,
        metaEvent: e,
        toolName: null,
        round: e.round,
        createdAt: e.createdAt
      })
    }
  }

  return steps
}
