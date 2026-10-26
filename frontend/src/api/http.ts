import axios, { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

// 给 axios 请求配置加一个 silent 字段：silent=true 时不弹错误提示
// （用于「检查是否已登录」这类静默请求）
declare module 'axios' {
  export interface AxiosRequestConfig {
    silent?: boolean
  }
}

const http = axios.create({
  baseURL: '/api',
  withCredentials: true, // 关键：自动携带 JSESSIONID Cookie
  timeout: 10000,
})

http.interceptors.response.use(
  (response) => response.data, // 成功时直接返回 data，省去每页写 .data
  (error: AxiosError<{ message?: string }>) => {
    if (!error.config?.silent) {
      ElMessage.error(error.response?.data?.message || '网络错误，请稍后重试')
    }
    return Promise.reject(error)
  },
)

export default http
