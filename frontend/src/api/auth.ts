import http from './http'

export interface User {
  id: number
  email: string
  nickname: string
  emailVerified: boolean
  status: number
  role: 'USER' | 'ADMIN'
  createdAt: string
}

// 公开用户资料（不含邮箱、角色等敏感字段）
export interface PublicProfile {
  id: number
  nickname: string
  emailVerified: boolean
  createdAt: string
}

export interface SendCodeResponse {
  message: string
  expiresInSeconds: number
  resendAfterSeconds: number
}

export function sendRegistrationCode(email: string) {
  return http.post('/auth/registration-codes', { email }) as Promise<SendCodeResponse>
}

export function register(data: { email: string; code: string; password: string; nickname: string }) {
  return http.post('/auth/register', data) as Promise<User>
}

export function login(email: string, password: string) {
  return http.post('/auth/login', { email, password }) as Promise<User>
}

export function logout() {
  return http.post('/auth/logout') as Promise<void>
}

// silent: 静默检查登录态，失败不弹错误
export function getMe() {
  return http.get('/auth/me', { silent: true }) as Promise<User>
}

export function updateNickname(nickname: string) {
  return http.patch('/account/me', { nickname }) as Promise<User>
}

export function deleteAccount(password: string) {
  return http.delete('/account/me', { data: { password } }) as Promise<void>
}

export function getUser(id: number) {
  return http.get(`/users/${id}`) as Promise<PublicProfile>
}
