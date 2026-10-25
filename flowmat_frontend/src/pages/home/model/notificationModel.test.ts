import { describe, expect, it } from 'vitest'
import { notificationLink, unreadText } from './notificationModel'

describe('notifications', () => {
  it('leads to the screen of the work', () => {
    expect(notificationLink({ projectId: 'prj 1', refType: 'nonconformity' })).toBe('/projects/prj%201/inventory?tab=quality')
    expect(notificationLink({ projectId: 'prj', refType: 'warehouse_task' })).toBe('/projects/prj/inventory?tab=tasks')
    expect(notificationLink({ projectId: 'prj', refType: 'work_order' })).toBe('/projects/prj/runs?view=work-orders')
    expect(notificationLink({ projectId: 'prj', refType: 'stock_alert' })).toBe('/projects/prj/inventory?tab=stock')
    expect(notificationLink({ projectId: 'prj', refType: 'count_plan', refId: 'plan-1' })).toBe('/projects/prj/inventory?tab=count-plans')
    expect(notificationLink({ projectId: 'prj', refType: 'production_run', refId: 'run 1' })).toBe('/projects/prj/runs/run%201')
    expect(notificationLink({ projectId: 'prj', refType: 'production_run', refId: null })).toBeNull()
    expect(notificationLink({ projectId: 'prj', refType: 'bom', refId: 'bom-1' })).toBe('/projects/prj/inventory?tab=boms')
    expect(notificationLink({ projectId: 'prj', refType: 'purchase_order', refId: 'po1' })).toBe('/projects/prj/inventory?tab=purchasing')
    expect(notificationLink({ projectId: 'prj', refType: 'something_else' })).toBeNull()
    expect(notificationLink({ projectId: null, refType: 'nonconformity' })).toBeNull()
  })

  it('counts the unread ones', () => {
    expect(unreadText([{ read: false }, { read: true }, { read: false }])).toBe('2 unread')
    expect(unreadText([{ read: true }])).toBe('All read')
    expect(unreadText([])).toBe('All read')
  })
})
