import { useState } from 'react'
import { useProjectTimeZone } from '../../../entities/project/api/useProjectTimeZone'
import { usePurchaseOrderMutations, type PurchaseOrderInput } from '../../../entities/purchasing/api/usePurchaseOrders'
import { useSupplierItemsQuery, useSuppliersQuery } from '../../../entities/purchasing/api/useSuppliers'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { projectToday } from '../model/equipmentScheduleModel'
import { draftsBySupplier, type DraftGroup } from '../model/supplierModel'

const lineCount = (count: number) => `${count} ${count === 1 ? 'line' : 'lines'}`

/**
 * Drafts purchase orders for lines by their items' usual suppliers (docs/domain/purchasing.md PO30): one draft per active usual
 * supplier, expected after its lead time, and the lines without one on a draft for a supplier asked for. Without a usual
 * supplier among the lines it only asks for one supplier, as before.
 */
export function useUsualSupplierDrafts(projectId: string) {
  const suppliers = useSuppliersQuery(projectId).data ?? []
  const usual = useSupplierItemsQuery(projectId).data ?? []
  const timeZone = useProjectTimeZone(projectId).query.data?.timeZone
  const { create } = usePurchaseOrderMutations(projectId)
  const [busy, setBusy] = useState(false)

  /** What happened, for the status line; null when it was called off. A place given is where the drafts are to arrive (PO40). */
  async function draft(lines: PurchaseOrderInput['lines'], note: string, deliverTo: string | null = null): Promise<{ error: boolean; text: string } | null> {
    const plan = draftsBySupplier(lines, usual, suppliers, projectToday(timeZone, new Date()))
    const groups: DraftGroup[] = [...plan.groups]
    if (groups.length > 0) {
      const list = groups.map((one) => `${one.supplierName} (${lineCount(one.lines.length)})`).join(', ')
      const rest = plan.rest.length > 0 ? ` and ${lineCount(plan.rest.length)} without a usual supplier` : ''
      if (!window.confirm(`Draft purchase orders for ${list}${rest}?`)) return null
    }
    if (plan.rest.length > 0) {
      const supplier = window.prompt(groups.length > 0
        ? `Which supplier is the draft for the ${lineCount(plan.rest.length)} without a usual supplier?`
        : 'Which supplier is the draft purchase order for?')
      if (supplier?.trim()) groups.push({ supplierName: supplier.trim(), expectedDate: null, lines: plan.rest })
      else if (groups.length === 0) return null
    }
    setBusy(true)
    const done: { orderNumber: string; supplierName: string; lines: number }[] = []
    try {
      for (const group of groups) {
        const order = await create.mutateAsync({ projectId, supplierName: group.supplierName, expectedDate: group.expectedDate, note, lines: group.lines,
          ...(deliverTo ? { deliverTo } : {}) })
        done.push({ orderNumber: order.orderNumber, supplierName: order.supplierName, lines: order.lines.length })
      }
      return done.length === 1
        ? { error: false, text: `Drafted ${done[0].orderNumber} with ${lineCount(done[0].lines)}; check and order it on the Purchasing tab.` }
        : { error: false, text: `Drafted ${done.map((one) => `${one.orderNumber} for ${one.supplierName} with ${lineCount(one.lines)}`).join(', ')}; `
          + 'check and order them on the Purchasing tab.' }
    } catch (error) {
      const before = done.length > 0 ? `Drafted ${done.map((one) => one.orderNumber).join(', ')}; then: ` : ''
      return { error: true, text: before + errorMessage(error, 'The draft purchase order was refused.') }
    } finally {
      setBusy(false)
    }
  }

  return { draft, busy }
}
