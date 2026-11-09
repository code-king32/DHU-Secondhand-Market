const pad = (n: number) => String(n).padStart(2, '0')

// 统一格式化后端返回的时间字符串为 "YYYY-MM-DD HH:mm"。
// 后端 LocalDateTime 序列化为 ISO-8601 本地时间（无时区后缀）；纯日期或无法解析时原样返回，避免破坏展示。
export function formatTime(value?: string | null): string {
  if (!value) return '—'
  // 纯日期（YYYY-MM-DD）避免被当作 UTC 午夜导致时区偏移
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
