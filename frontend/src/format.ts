import type { Category, TaskStatus, ReviewStatus, Point } from './types'
export const categoryLabels: Record<Category, string> = {
  person: '人员',
  vehicle: '车辆',
  motorcycle: '摩托车',
  animal: '动物',
  obstacle: '岩石 / 障碍物',
}
export const taskLabels: Record<TaskStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '检测中',
  SUCCEEDED: '已完成',
  FAILED: '检测失败',
  CANCELLED: '已取消',
}
export const reviewLabels: Record<ReviewStatus, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  RESOLVED: '已处理',
  FALSE_POSITIVE: '已标记误报',
}
export const categoryColors: Record<Category, string> = {
  person: '#5471f8',
  vehicle: '#f2ad55',
  motorcycle: '#e27791',
  animal: '#4dbea4',
  obstacle: '#9e88db',
}
export function dateTime(value?: string) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
        timeZone: 'Asia/Shanghai',
      }).format(new Date(value))
    : '—'
}
export function duration(ms?: number) {
  return ms == null ? '—' : ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`
}
export const isActive = (status: TaskStatus) => status === 'QUEUED' || status === 'RUNNING'
export function validRegion(points: Point[]) {
  if (
    points.length !== 4 ||
    points.some((p) => p.some((n) => !Number.isFinite(n) || n < 0 || n > 1))
  )
    return false
  let sign = 0,
    area = 0
  for (let i = 0; i < 4; i++) {
    const p = points[i],
      q = points[(i + 1) % 4],
      r = points[(i + 2) % 4]
    const cross = (q[0] - p[0]) * (r[1] - q[1]) - (q[1] - p[1]) * (r[0] - q[0])
    if (Math.abs(cross) < 0.00001 || (sign && Math.sign(cross) !== sign)) return false
    sign = Math.sign(cross)
    area += p[0] * q[1] - q[0] * p[1]
  }
  return Math.abs(area) / 2 >= 0.005
}
