// 后端分页响应统一结构
export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}
