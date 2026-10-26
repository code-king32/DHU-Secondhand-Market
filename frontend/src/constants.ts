// 后端枚举值 → 前端中文文案（所有页面共用）

export const CATEGORY_LABELS: Record<string, string> = {
  TEXTBOOK: '教材',
  ELECTRONICS: '电子产品',
  DAILY: '日常用品',
  CLOTHING: '衣物',
  OTHER: '其他',
}

export const GOODS_STATUS_LABELS: Record<string, string> = {
  ON_SALE: '在售',
  TRADING: '交易中',
  SOLD: '已售',
  OFF_SHELF: '已下架',
  HIDDEN: '已隐藏',
}

export const TRADE_STATUS_LABELS: Record<string, string> = {
  PENDING: '待处理',
  ACCEPTED: '已接受',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

export const REPORT_STATUS_LABELS: Record<string, string> = {
  OPEN: '待处理',
  RESOLVED: '已处理',
  REJECTED: '已驳回',
}

export const APPEAL_STATUS_LABELS: Record<string, string> = {
  OPEN: '待处理',
  UPHELD: '已成立',
  REJECTED: '已驳回',
}

export const TARGET_TYPE_LABELS: Record<string, string> = {
  GOODS: '商品',
  USER: '用户',
  REVIEW: '评价',
}

export type TagType = 'success' | 'info' | 'warning' | 'danger' | 'primary'

export function goodsStatusTag(status: string): TagType {
  if (status === 'ON_SALE') return 'success'
  if (status === 'TRADING') return 'warning'
  if (status === 'HIDDEN') return 'danger'
  return 'info'
}

export function tradeStatusTag(status: string): TagType {
  if (status === 'PENDING') return 'warning'
  if (status === 'ACCEPTED') return 'primary'
  if (status === 'COMPLETED') return 'success'
  return 'info'
}

export function reportStatusTag(status: string): TagType {
  if (status === 'OPEN') return 'warning'
  if (status === 'RESOLVED') return 'success'
  return 'info'
}
