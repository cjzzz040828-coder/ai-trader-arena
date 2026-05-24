/**
 * 给 ECharts 用的主题色 helper：
 * ECharts inline 配置不能写 CSS 变量字符串（var(...) 不会被解析），
 * 所以这里 runtime 读出来再喂给 setOption。
 *
 * 配合 useTheme().mode 的 watch 在主题切换时 setOption(buildOption(...))
 * 重画即可，无需 dispose。
 */
export interface ChartColors {
  primary: string
  up: string
  down: string
  textRegular: string
  textMuted: string
  surface: string
  bgSoft: string
  border: string
  borderLight: string
  tooltipBg: string
}

export function readChartColors(): ChartColors {
  const cs = getComputedStyle(document.documentElement)
  const get = (name: string, fallback = '') => (cs.getPropertyValue(name).trim() || fallback)
  const isDark = document.documentElement.classList.contains('dark')
  return {
    primary: get('--brand-primary', '#3b82f6'),
    up: get('--brand-up', '#dc2626'),
    down: get('--brand-down', '#16a34a'),
    textRegular: get('--brand-text-regular', '#334155'),
    textMuted: get('--brand-text-secondary', '#64748b'),
    surface: get('--brand-surface', '#ffffff'),
    bgSoft: get('--brand-bg-soft', '#f1f5f9'),
    border: get('--brand-border', '#e2e8f0'),
    borderLight: get('--brand-border-light', '#f1f5f9'),
    tooltipBg: isDark ? 'rgba(15, 23, 42, 0.95)' : 'rgba(255, 255, 255, 0.96)',
  }
}
