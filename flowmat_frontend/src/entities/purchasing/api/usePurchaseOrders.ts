import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** docs/domain/purchasing.md PO3-PO4. */
export type PurchaseOrderStatus = 'draft' | 'ordered' | 'received' | 'closed' | 'cancelled'

export interface PurchaseOrderLineDto {
  lineId: string
  lineNo: number
  itemId: string
  itemCode: string
  itemName: string | null
  /** The item's stock unit, which the quantities are in. */
  unitCode: string | null
  lotTracked: boolean
  quantity: number
  receivedQuantity: number
  remainingQuantity: number
  expectedDate: string | null
  note: string | null
  /** Per stock unit; null while not agreed (docs/domain/purchasing.md PO14). */
  unitPrice?: number | null
  /** Quantity × unit price; null without a price. */
  lineValue?: number | null
  /** Sent back to the supplier; what was received stays (PO27). */
  returnedQuantity?: number
  /** The supplier's own code for the item, kept as the order was placed (PO45); null on a draft or without one. */
  supplierItemCode?: string | null
  /** Sent back to be replaced on this line (PO46); receivedQuantity counts the replacements too. */
  replacementQuantity?: number
}

export interface PurchaseReceiptDto {
  receiptId: string
  lineId: string
  itemCode: string
  quantity: number
  inventoryId: string
  location: string | null
  lotNo: string | null
  receivedBy: string
  receivedAt: string
}

/** A change to a supplier credit kept for a return, and why (docs/domain/purchasing.md PO52); no amount withdrew it. */
export interface PurchaseCreditChangeDto {
  amount: number | null
  amountText?: string | null
  creditNote: string | null
  reason: string
  changedBy: string
  changedAt: string
}

/** Stock received on a line and sent back to the supplier (docs/domain/purchasing.md PO26-PO28). */
export interface PurchaseReturnDto {
  returnId: string
  lineId: string
  itemCode: string
  quantity: number
  inventoryId: string
  location: string | null
  lotNo: string | null
  reason: string
  returnedBy: string
  returnedAt: string
  /** The NCR it went back for and its number (PO42); null when none. */
  nonconformityId?: string | null
  ncrNo?: string | null
  /** The supplier replaces it on the same line (PO46). */
  replacement?: boolean
  /** What the supplier credited for it, with their credit note, who kept it and when (PO47); null until then. */
  creditAmount?: number | null
  creditAmountText?: string | null
  creditNote?: string | null
  creditedBy?: string | null
  creditedAt?: string | null
  /** Every change to the credit, oldest first; the latest stands (PO52). */
  creditChanges?: PurchaseCreditChangeDto[]
}

/** An expected date moved on an order being received (docs/domain/purchasing.md PO23); no line is the order's own. */
export interface PurchaseOrderDateChangeDto {
  changeId: string
  lineId: string | null
  itemCode: string | null
  previousDate: string | null
  expectedDate: string | null
  reason: string
  changedBy: string
  changedAt: string
}

/** One approval a draft needs or got (docs/domain/purchasing.md PO50). */
export interface PurchaseApprovalDto {
  approverId: string
  /** The order value it is needed from; null on an order that is no longer a draft. */
  fromValue: number | null
  /** The owner's, from the approval limit. */
  projectOwner: boolean
  /** Null while it waits. */
  approvedAt: string | null
}

export interface PurchaseOrderDto {
  purchaseOrderId: string
  projectId: string
  orderNumber: string
  supplierName: string
  status: PurchaseOrderStatus
  expectedDate: string | null
  note: string | null
  createdBy: string
  createdAt: string
  orderedBy: string | null
  orderedAt: string | null
  /** Who received the last of it, closed it or cancelled it. */
  endedBy: string | null
  endedAt: string | null
  endReason: string | null
  lines: PurchaseOrderLineDto[]
  receipts: PurchaseReceiptDto[]
  /** The known line values added up; valueComplete when every line has a price (PO14). */
  orderValue?: number
  valueComplete?: boolean
  dateChanges?: PurchaseOrderDateChangeDto[]
  returns?: PurchaseReturnDto[]
  /** The project owner who approved the draft as it is (PO34). */
  approvedBy?: string | null
  approvedAt?: string | null
  /** A draft ordered only once approved, under the project's approval limit (PO34). */
  needsApproval?: boolean
  /** Where it is to arrive (PO39). */
  deliverTo?: string | null
  /** A draft's approvals in turn; another order's as they were given (PO50). */
  approvals?: PurchaseApprovalDto[]
}

/** A draft, sent whole. */
export interface PurchaseOrderInput {
  projectId: string
  supplierName: string
  expectedDate: string | null
  note: string | null
  lines: { itemId: string; quantity: number; expectedDate: string | null; note: string | null; unitPrice?: number | null }[]
  /** Where it is to arrive (PO39); left out for none. */
  deliverTo?: string | null
}

/** What arrived, all lines or none; the same requestId again answers as the order is now. */
export interface PurchaseReceiptInput {
  requestId: string
  lines: { lineId: string; quantity: number; location: string | null; lotNo: string | null; expiryDate: string | null }[]
}

/** One line's stock going back from one stock record (PO26). */
export interface PurchaseReturnInput {
  requestId: string
  lineId: string
  inventoryId: string
  quantity: number
  reason: string
  /** The open NCR, set to return to supplier, that decided it (PO42); left out when none. */
  nonconformityId?: string
  /** The supplier replaces it on the same line, which is then to receive it again (PO46). */
  replacement?: boolean
}

/** What the supplier credited for a return, kept once (PO47). */
export interface PurchaseCreditInput {
  amount: string
  /** The supplier's credit note number; left out when none. */
  creditNote?: string
}

/** A change to a kept credit, with a reason; no amount withdraws it (PO52). */
export interface PurchaseCreditChangeInput {
  amount: string | null
  creditNote?: string
  reason: string
}

/** The order's own expected date, sent whole, and the lines that move (PO22). */
export interface PurchaseOrderDatesInput {
  expectedDate: string | null
  lines: { lineId: string; expectedDate: string | null }[]
  reason: string
}

const key = (projectId: string) => ['purchase-orders', projectId]
const path = (purchaseOrderId: string) => `/purchase-orders/${encodeURIComponent(purchaseOrderId)}`

/** The project's purchase orders, newest first (docs/architecture/adr/ADR-027-purchasing.md). */
export function usePurchaseOrdersQuery(projectId: string) {
  return useQuery<PurchaseOrderDto[]>({
    queryKey: key(projectId),
    enabled: Boolean(projectId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<PurchaseOrderDto[]>>(
      `/purchase-orders?projectId=${encodeURIComponent(projectId)}`)),
  })
}

/** Each change reads the orders again; a receipt also changes stock, LOTs and their history. */
export function usePurchaseOrderMutations(projectId: string) {
  const client = useQueryClient()
  const refresh = () => client.invalidateQueries({ queryKey: key(projectId) })
  const post = async (url: string, body?: unknown) =>
    unwrapApiResponse(await httpClient.post<ApiEnvelope<PurchaseOrderDto>>(url, body ?? {}))
  return {
    create: useMutation({
      mutationFn: async (input: PurchaseOrderInput) => post('/purchase-orders', input),
      onSuccess: refresh,
    }),
    update: useMutation({
      mutationFn: async ({ purchaseOrderId, input }: { purchaseOrderId: string; input: PurchaseOrderInput }) =>
        unwrapApiResponse(await httpClient.put<ApiEnvelope<PurchaseOrderDto>>(path(purchaseOrderId), input)),
      onSuccess: refresh,
    }),
    order: useMutation({
      mutationFn: async (purchaseOrderId: string) => post(`${path(purchaseOrderId)}/order`),
      onSuccess: refresh,
    }),
    cancel: useMutation({
      mutationFn: async ({ purchaseOrderId, reason }: { purchaseOrderId: string; reason: string }) =>
        post(`${path(purchaseOrderId)}/cancel`, { reason }),
      onSuccess: refresh,
    }),
    close: useMutation({
      mutationFn: async ({ purchaseOrderId, reason }: { purchaseOrderId: string; reason: string }) =>
        post(`${path(purchaseOrderId)}/close`, { reason }),
      onSuccess: refresh,
    }),
    receive: useMutation({
      mutationFn: async ({ purchaseOrderId, input }: { purchaseOrderId: string; input: PurchaseReceiptInput }) =>
        post(`${path(purchaseOrderId)}/receipts`, input),
      onSuccess: () => {
        void refresh()
        for (const queryKey of [['inventories', projectId], ['lots', projectId], ['inventory-transactions'], ['storage-locations', projectId]]) {
          void client.invalidateQueries({ queryKey })
        }
      },
    }),
    approve: useMutation({
      mutationFn: async (purchaseOrderId: string) => post(`${path(purchaseOrderId)}/approve`),
      onSuccess: refresh,
    }),
    giveBack: useMutation({
      mutationFn: async ({ purchaseOrderId, input }: { purchaseOrderId: string; input: PurchaseReturnInput }) =>
        post(`${path(purchaseOrderId)}/returns`, input),
      onSuccess: () => {
        void refresh()
        for (const queryKey of [['inventories', projectId], ['lots', projectId], ['inventory-transactions']]) {
          void client.invalidateQueries({ queryKey })
        }
      },
    }),
    credit: useMutation({
      mutationFn: async ({ purchaseOrderId, returnId, input }: { purchaseOrderId: string; returnId: string; input: PurchaseCreditInput }) =>
        post(`${path(purchaseOrderId)}/returns/${encodeURIComponent(returnId)}/credit`, input),
      onSuccess: refresh,
    }),
    changeCredit: useMutation({
      mutationFn: async ({ purchaseOrderId, returnId, input }: { purchaseOrderId: string; returnId: string; input: PurchaseCreditChangeInput }) =>
        unwrapApiResponse(await httpClient.put<ApiEnvelope<PurchaseOrderDto>>(
          `${path(purchaseOrderId)}/returns/${encodeURIComponent(returnId)}/credit`, input)),
      onSuccess: refresh,
    }),
    changeDates: useMutation({
      mutationFn: async ({ purchaseOrderId, input }: { purchaseOrderId: string; input: PurchaseOrderDatesInput }) =>
        post(`${path(purchaseOrderId)}/expected-dates`, input),
      onSuccess: refresh,
    }),
  }
}
