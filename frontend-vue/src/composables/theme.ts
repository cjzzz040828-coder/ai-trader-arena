import { ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark'

const STORAGE_KEY = 'aitrade.theme'

function readInitial(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved === 'light' || saved === 'dark') return saved
  return 'light'
}

const mode = ref<ThemeMode>(readInitial())

function apply(m: ThemeMode) {
  const cls = document.documentElement.classList
  if (m === 'dark') cls.add('dark')
  else cls.remove('dark')
}

apply(mode.value)

watch(mode, (m) => {
  apply(m)
  localStorage.setItem(STORAGE_KEY, m)
})

export function useTheme() {
  return {
    mode,
    toggle() { mode.value = mode.value === 'dark' ? 'light' : 'dark' },
    set(m: ThemeMode) { mode.value = m }
  }
}
