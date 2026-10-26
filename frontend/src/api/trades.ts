import http from './http'
import type { Page } from './types'

export interface Trade {
  id: number
  goodsId: number
  sellerId: number
  buyerId: number
  agreedPrice: number
  status: string
  buyerConfirmed: boolean
  sellerConfirmed: boolean
  createdAt: string
  updatedAt: string
}

export function applyTrade(goodsId: number) {
  return http.post('/trades', { goodsId }) as Promise<Trade>
}

export function listTrades(params: { role?: string; page?: number; size?: number }) {
  return http.get('/trades', { params }) as Promise<Page<Trade>>
}

export function getTrade(id: number) {
  return http.get(`/trades/${id}`) as Promise<Trade>
}

export function acceptTrade(id: number) {
  return http.post(`/trades/${id}/accept`) as Promise<Trade>
}

export function confirmTrade(id: number) {
  return http.post(`/trades/${id}/confirm`) as Promise<Trade>
}

export function cancelTrade(id: number) {
  return http.post(`/trades/${id}/cancel`) as Promise<Trade>
}
