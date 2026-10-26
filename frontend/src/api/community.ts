import http from './http'
import type { Page } from './types'

export interface Review {
  id: number
  tradeId: number
  reviewerId: number
  revieweeId: number
  rating: number
  comment: string
  createdAt: string
}

export interface Reputation {
  userId: number
  reviewCount: number
  averageRating: number
}

export interface Report {
  id: number
  reporterId: number
  targetType: string
  targetId: number
  reason: string
  status: string
  resolution: string | null
  resolverId: number | null
  createdAt: string
  updatedAt: string
}

export interface Appeal {
  id: number
  reportId: number
  appellantId: number
  reason: string
  status: string
  resolution: string | null
  resolverId: number | null
  createdAt: string
  updatedAt: string
}

export interface Audit {
  id: number
  actorId: number
  reportId: number
  action: string
  detail: string
  createdAt: string
}

export interface Favorite {
  id: number
  goodsId: number
  title: string
  price: number
  status: string
  createdAt: string
}

export interface Wanted {
  id: number
  userId: number
  title: string
  description: string
  maxPrice: number
  category: string
  status: string
  createdAt: string
  updatedAt: string
}

// ---------- 评价 ----------
export function createReview(data: { tradeId: number; rating: number; comment: string }) {
  return http.post('/reviews', data) as Promise<Review>
}

export function listReviews(userId: number, params: { page?: number; size?: number }) {
  return http.get('/reviews', { params: { userId, ...params } }) as Promise<Page<Review>>
}

export function getReputation(userId: number) {
  return http.get(`/users/${userId}/reputation`) as Promise<Reputation>
}

// ---------- 举报 ----------
export function createReport(data: { targetType: string; targetId: number; reason: string }) {
  return http.post('/reports', data) as Promise<Report>
}

export function listMyReports(params: { page?: number; size?: number }) {
  return http.get('/reports/mine', { params }) as Promise<Page<Report>>
}

export function createAppeal(reportId: number, reason: string) {
  return http.post(`/reports/${reportId}/appeals`, { reason }) as Promise<Appeal>
}

export function listAppeals(reportId: number) {
  return http.get(`/reports/${reportId}/appeals`) as Promise<Appeal[]>
}

// ---------- 管理员 ----------
export function listAdminReports(params: { status?: string; page?: number; size?: number }) {
  return http.get('/admin/reports', { params }) as Promise<Page<Report>>
}

export function resolveReport(id: number, data: { decision: string; note: string; action?: string }) {
  return http.put(`/admin/reports/${id}/resolution`, data) as Promise<Report>
}

export function listAdminAppeals(params: { status?: string; page?: number; size?: number }) {
  return http.get('/admin/appeals', { params }) as Promise<Page<Appeal>>
}

export function resolveAppeal(id: number, data: { decision: string; note: string }) {
  return http.put(`/admin/appeals/${id}/resolution`, data) as Promise<Appeal>
}

export function getReportAudit(id: number) {
  return http.get(`/admin/reports/${id}/audit`) as Promise<Audit[]>
}

export function setUserStatus(id: number, status: number) {
  return http.put(`/admin/users/${id}/status`, { status }) as Promise<{ userId: number; status: number }>
}

// ---------- 收藏 ----------
export function favorite(goodsId: number) {
  return http.put(`/favorites/${goodsId}`) as Promise<Favorite>
}

export function unfavorite(goodsId: number) {
  return http.delete(`/favorites/${goodsId}`) as Promise<void>
}

export function listFavorites(params: { page?: number; size?: number }) {
  return http.get('/favorites', { params }) as Promise<Page<Favorite>>
}

// ---------- 求购 ----------
export function createWanted(data: { title: string; description: string; maxPrice: number; category: string }) {
  return http.post('/wanted', data) as Promise<Wanted>
}

export function listWanted(params: { q?: string; category?: string; page?: number; size?: number }) {
  return http.get('/wanted', { params }) as Promise<Page<Wanted>>
}

export function closeWanted(id: number) {
  return http.put(`/wanted/${id}/close`) as Promise<Wanted>
}
