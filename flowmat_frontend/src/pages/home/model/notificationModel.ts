import type { NotificationDto } from '../../../entities/notification/api/useNotifications'

/** Where a notification leads (docs/domain/notification.md NT5): the screen of the work, or nowhere. */
export function notificationLink(
  notification: Pick<NotificationDto, 'projectId' | 'refType'> & { refId?: string | null },
): string | null {
  if (!notification.projectId) return null
  const project = `/projects/${encodeURIComponent(notification.projectId)}`
  if (notification.refType === 'nonconformity') return `${project}/inventory?tab=quality`
  if (notification.refType === 'warehouse_task') return `${project}/inventory?tab=tasks`
  if (notification.refType === 'work_order') return `${project}/runs?view=work-orders`
  if (notification.refType === 'stock_alert') return `${project}/inventory?tab=stock`
  if (notification.refType === 'count_plan') return `${project}/inventory?tab=count-plans`
  // Approvals (NT8): a correction opens its run, a BOM revision the BOM tab.
  if (notification.refType === 'production_run' && notification.refId) {
    return `${project}/runs/${encodeURIComponent(notification.refId)}`
  }
  if (notification.refType === 'bom') return `${project}/inventory?tab=boms`
  // A purchase order waiting for approval, or approved (docs/domain/purchasing.md PO36).
  if (notification.refType === 'purchase_order') return `${project}/inventory?tab=purchasing`
  return null
}

/** "2 unread", or "All read" when none is. */
export function unreadText(notifications: Pick<NotificationDto, 'read'>[]): string {
  const unread = notifications.filter((notification) => !notification.read).length
  return unread === 0 ? 'All read' : `${unread} unread`
}
