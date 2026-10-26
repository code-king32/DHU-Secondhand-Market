<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { sendRegistrationCode, register } from '@/api/auth'

const router = useRouter()
const form = reactive({ email: '', code: '', password: '', nickname: '' })
const loading = ref(false)
const sending = ref(false)
const countdown = ref(0)

async function sendCode() {
  if (!form.email) {
    ElMessage.warning('请先填写校园邮箱')
    return
  }
  sending.value = true
  try {
    const res = await sendRegistrationCode(form.email)
    ElMessage.success(res.message || '验证码已发送')
    startCountdown(res.resendAfterSeconds || 60)
  } catch {
    // 拦截器已提示
  } finally {
    sending.value = false
  }
}

function startCountdown(seconds: number) {
  countdown.value = seconds
  const timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) clearInterval(timer)
  }, 1000)
}

async function submit() {
  if (!form.email || !form.code || !form.password || !form.nickname) {
    ElMessage.warning('请填写完整信息')
    return
  }
  if (form.password.length < 8) {
    ElMessage.warning('密码至少 8 位')
    return
  }
  loading.value = true
  try {
    await register({ ...form })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <el-card class="card">
      <h2>注册</h2>
      <el-form label-width="90px">
        <el-form-item label="校园邮箱">
          <el-input v-model="form.email" placeholder="xxx@mail.dhu.edu.cn" />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="code-row">
            <el-input v-model="form.code" placeholder="6 位数字" />
            <el-button :disabled="countdown > 0" :loading="sending" @click="sendCode">
              {{ countdown > 0 ? `${countdown}s` : '发送验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" placeholder="你的昵称" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="submit">注册</el-button>
        <el-button @click="router.push('/login')">返回登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.wrap {
  display: flex;
  justify-content: center;
  padding-top: 80px;
}
.card {
  width: 480px;
}
.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
</style>
