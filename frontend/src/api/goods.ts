import http from './http'
import type { Page } from './types'

export interface Goods {
  id: number
  sellerId: number
  title: string
  description: string
  price: number
  category: string
  imageUrls: string[]
  status: string
  createdAt: string
  updatedAt: string
}

export interface GoodsWrite {
  title: string
  description: string
  price: number
  category: string
  imageUrls: string[]
}

export function listGoods(params: { keyword?: string; category?: string; page?: number; size?: number }) {
  return http.get('/goods', { params }) as Promise<Page<Goods>>
}

export function getGoods(id: number) {
  return http.get(`/goods/${id}`) as Promise<Goods>
}

export function listMyGoods(params: { page?: number; size?: number }) {
  return http.get('/goods/mine', { params }) as Promise<Page<Goods>>
}

export function getGoodsManagement(id: number) {
  return http.get(`/goods/${id}/management`) as Promise<Goods>
}

export function createGoods(data: GoodsWrite) {
  return http.post('/goods', data) as Promise<Goods>
}

export function updateGoods(id: number, data: GoodsWrite) {
  return http.put(`/goods/${id}`, data) as Promise<Goods>
}

export function shelfGoods(id: number, onSale: boolean) {
  return http.post(`/goods/${id}/shelf`, { onSale }) as Promise<Goods>
}
