<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const mode = ref<'login' | 'register'>('login')
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  nickname: ''
})

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    if (mode.value === 'login') {
      await auth.login(form.username, form.password)
      ElMessage.success('登录成功')
    } else {
      await auth.register(form.username, form.password, form.nickname)
      ElMessage.success('注册成功')
    }
    router.replace('/')
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message || '请求失败'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="card">
      <h2>aiTrade</h2>
      <el-tabs v-model="mode" class="tabs">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>
      <el-form @submit.prevent="submit" label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="3-32位" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="至少6位"
                    autocomplete="current-password" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="昵称（可选）">
          <el-input v-model="form.nickname" placeholder="留空则用用户名" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="submit" style="width: 100%;">
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--brand-bg);
  position: relative;
  overflow: hidden;
}
.card {
  width: 360px;
  padding: 32px;
  background: var(--brand-surface);
  border: 1px solid var(--brand-border);
  border-radius: 8px;
  box-shadow: var(--brand-shadow-lg);
  position: relative;
  z-index: 1;
}
.card h2 {
  margin: 0 0 16px;
  text-align: center;
  color: var(--brand-primary);
  font-size: 22px;
  letter-spacing: 2px;
}
.tabs { margin-bottom: 8px; }
</style>
