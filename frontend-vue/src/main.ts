import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/theme.css'
import './composables/theme'
import App from './App.vue'
import router from './router'

// 顶部导航栏高度 48px，ElMessage 默认 offset=16 会被盖住。
// 包一层让所有调用默认下移到导航栏下面，业务代码不用改。
const HEADER_OFFSET = 64
const TYPES = ['success', 'warning', 'info', 'error'] as const
for (const t of TYPES) {
  const orig = ElMessage[t]
  ;(ElMessage as any)[t] = (opt: any) => {
    if (typeof opt === 'string') return orig({ message: opt, offset: HEADER_OFFSET })
    return orig({ offset: HEADER_OFFSET, ...opt })
  }
}

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
