<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { EMAIL_RULES, LOGIN_PASSWORD_RULES, isValid } from '@/utils/validate'

const router = useRouter()
const auth = useAuthStore()
const form = reactive({ email: '', password: '' })
const formRef = ref<FormInstance>()
const loading = ref(false)
const rules = { email: EMAIL_RULES, password: LOGIN_PASSWORD_RULES }

async function submit() {
  if (!(await isValid(formRef.value))) return
  loading.value = true
  try {
    await auth.login(form.email, form.password)
    ElMessage.success('登录成功')
    router.push((router.currentRoute.value.query.redirect as string) || '/')
  } catch {
    // 错误提示已由 axios 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2>登录</h2>
      <el-form ref="formRef" :model="form" :rules="rules">
        <el-form-item label="校园邮箱" prop="email">
          <el-input v-model="form.email" placeholder="xxx@mail.dhu.edu.cn" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="submit">登录</el-button>
        <el-button @click="router.push('/register')">注册</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap {
  display: flex;
  justify-content: center;
  padding-top: 100px;
}
.login-card {
  width: 400px;
}
</style>
