import type { Pinia } from 'pinia'
import type { Router } from 'vue-router'
import type { AxiosError } from 'axios'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'

export function installAuthHandling(router: Router, pinia: Pinia) {
  const auth = useAuthStore(pinia)

  http.interceptors.response.use(undefined, (error: AxiosError) => {
    // 登录接口的 401 表示密码错误，不能当作现有会话过期。
    if (error.response?.status === 401 && error.config?.url !== '/auth/login') {
      auth.user = null
      const current = router.currentRoute.value
      // 静默校验由导航守卫决定去向，避免同时发起两次跳转。
      if (!error.config?.silent && current.meta.requiresAuth) {
        void router.replace({ path: '/login', query: { redirect: current.fullPath } })
      }
    }
    return Promise.reject(error)
  })

  router.beforeEach(async (to) => {
    // 即使前端保存着用户信息，也要确认服务器上的会话仍然有效。
    if (to.meta.requiresAuth && !(await auth.fetchMe())) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (to.meta.requiresAdmin && auth.user?.role !== 'ADMIN') return '/'
  })
}
