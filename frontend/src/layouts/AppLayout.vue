<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loggingOut = ref(false)

onMounted(() => {
  // 公开页面也恢复登录态，保证导航栏显示正确
  if (!auth.user) auth.fetchMe()
})

async function doLogout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await auth.logout()
    ElMessage.success('已退出')
    await router.push('/')
  } catch {
    // 请求拦截器已提示；退出失败时保留当前登录状态，允许重试。
  } finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <router-link to="/" class="brand">DHU 校园二手市场</router-link>
      <nav class="nav">
        <router-link to="/">首页</router-link>
        <router-link to="/wanted">求购</router-link>
        <template v-if="auth.user">
          <router-link to="/goods/new">发布</router-link>
          <router-link to="/mine/goods">我的商品</router-link>
          <router-link to="/mine/trades">我的交易</router-link>
          <router-link to="/mine/favorites">收藏</router-link>
          <router-link to="/mine/reports">我的举报</router-link>
          <router-link to="/mine/account">账号</router-link>
          <template v-if="auth.user.role === 'ADMIN'">
            <router-link to="/admin/reports">举报处理</router-link>
            <router-link to="/admin/appeals">申诉处理</router-link>
          </template>
        </template>
      </nav>
      <div class="right">
        <template v-if="auth.user">
          <span class="nick">{{ auth.user.nickname }}</span>
          <el-button size="small" :loading="loggingOut" @click="doLogout">退出</el-button>
        </template>
        <template v-else>
          <el-button size="small" type="primary" @click="router.push('/login')">登录</el-button>
          <el-button size="small" @click="router.push('/register')">注册</el-button>
        </template>
      </div>
    </el-header>
    <el-main class="main">
      <router-view :key="$route.path" />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}
.header {
  display: flex;
  align-items: center;
  gap: 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 24px;
}
.brand {
  font-weight: 700;
  font-size: 18px;
  white-space: nowrap;
}
.nav {
  display: flex;
  gap: 16px;
  flex: 1;
  overflow-x: auto;
}
.nav a {
  white-space: nowrap;
  color: #606266;
}
.nav a.router-link-active {
  color: #409eff;
  font-weight: 600;
}
.right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.nick {
  color: #303133;
}
.main {
  background: #f5f7fa;
}
</style>
