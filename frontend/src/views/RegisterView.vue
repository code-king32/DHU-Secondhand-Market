<script setup lang="ts">
import { onUnmounted, reactive, ref } from 'vue'
import { isAxiosError } from 'axios'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { sendRegistrationCode, getRegistrationCodeStatus, register } from '@/api/auth'

const router = useRouter()
const form = reactive({ email: '', code: '', password: '', nickname: '' })
const loading = ref(false)
const sending = ref(false)
const countdown = ref(0)
const deliveryMessage = ref('')
let timer: ReturnType<typeof setInterval> | undefined
let pollTimer: ReturnType<typeof setTimeout> | undefined
let activeDelivery = ''
onUnmounted(() => {
  clearInterval(timer)
  clearTimeout(pollTimer)
  activeDelivery = ''
})

function pollDelivery(id: string, remaining = 24) {
  pollTimer = setTimeout(async () => {
    if (activeDelivery !== id) return
    try {
      const result = await getRegistrationCodeStatus(id)
      if (activeDelivery !== id) return
      deliveryMessage.value = result.message
      if (result.status === 'SENT') {
        ElMessage.success(result.message)
        return
      }
      if (result.status !== 'PENDING' && result.status !== 'SENDING') return
    } catch {
      // 查询失败不代表投递失败，继续有限次查询。
    }
    if (activeDelivery !== id) return
    if (remaining > 1) pollDelivery(id, remaining - 1)
    else deliveryMessage.value = '发送结果暂未确认，请检查邮箱；若已收到验证码，可直接填写'
  }, 5000)
}

async function sendCode() {
  if (!form.email) {
    ElMessage.warning('请先填写校园邮箱')
    return
  }
  sending.value = true
  clearTimeout(pollTimer)
  activeDelivery = ''
  deliveryMessage.value = '正在提交发送请求，请稍候'
  try {
    const res = await sendRegistrationCode(form.email)
    deliveryMessage.value = res.message
    if (!res.status || res.status === 'SENT') ElMessage.success(res.message || '验证码已发送')
    else if (res.status === 'PENDING' || res.status === 'SENDING') {
      ElMessage.info(res.message)
      activeDelivery = res.deliveryId
      pollDelivery(res.deliveryId)
    }
    startCountdown(res.resendAfterSeconds || 60)
  } catch (error) {
    // 超时不代表发信失败，留出收信时间，避免立即重复发送。
    if (isAxiosError(error) && (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT')) {
      startCountdown(60)
      deliveryMessage.value = '发送结果暂未确认，请检查邮箱；若已收到验证码，可直接填写'
    } else if (isAxiosError(error)) {
      deliveryMessage.value = error.response?.data?.message || '发送请求未完成，请稍后重试'
      const retry = Number(error.response?.headers['retry-after'])
      if (retry > 0) startCountdown(retry)
      else if (error.response?.status === 502) startCountdown(60)
    }
  } finally {
    sending.value = false
  }
}

function startCountdown(seconds: number) {
  clearInterval(timer)
  countdown.value = seconds
  timer = setInterval(() => {
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
        <p v-if="deliveryMessage" class="delivery-message" role="status">{{ deliveryMessage }}</p>
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
.delivery-message { margin: -6px 0 18px 90px; color: #606266; font-size: 12px; line-height: 1.5; }
</style>
