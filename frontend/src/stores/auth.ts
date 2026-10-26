import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as apiLogin, logout as apiLogout, getMe, type User } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)

  // 刷新页面后靠它恢复登录态（静默，失败返回 null）
  async function fetchMe() {
    try {
      user.value = await getMe()
    } catch {
      user.value = null
    }
    return user.value
  }

  async function login(email: string, password: string) {
    user.value = await apiLogin(email, password)
  }

  async function logout() {
    try {
      await apiLogout()
    } catch {
      // 忽略退出错误
    }
    user.value = null
  }

  return { user, fetchMe, login, logout }
})
