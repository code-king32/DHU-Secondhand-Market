import { ref } from 'vue'
import type { Page } from '@/api/types'

export interface PageListOptions {
  size?: number
}

// 统一列表页的分页、加载中、空数据与网络错误状态。
// fetcher 通过闭包读取当前搜索/筛选条件，返回后端 Page<T> 结构。
export function usePageList<T>(
  fetcher: (page: number, size: number) => Promise<Page<T>>,
  options: PageListOptions = {},
) {
  const items = ref<T[]>([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(options.size ?? 20)
  const loading = ref(false)
  const error = ref(false)

  async function load() {
    loading.value = true
    error.value = false
    try {
      const data = await fetcher(page.value, size.value)
      items.value = data.items
      total.value = data.total
    } catch {
      // 具体错误提示由 axios 拦截器统一弹出，这里只记录失败状态用于展示重试入口。
      error.value = true
    } finally {
      loading.value = false
    }
  }

  // 搜索/筛选变化时回到第一页重新加载。
  function reset() {
    page.value = 1
    return load()
  }

  return { items, total, page, size, loading, error, load, reset }
}
