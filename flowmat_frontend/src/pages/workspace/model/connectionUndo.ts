import type {
  CanvasEdgeViewModel,
  UpdateProcessConnectionInput,
} from '../../../entities/workflow/model/types'

export function connectionUndoInput(
  edge: CanvasEdgeViewModel,
  input: UpdateProcessConnectionInput
): UpdateProcessConnectionInput {
  const previous: UpdateProcessConnectionInput = { connectionId: input.connectionId }

  if (input.connectionLabel !== undefined) previous.connectionLabel = edge.label
  if (input.connectionType !== undefined) previous.connectionType = edge.connectionType
  if (input.flowRate !== undefined) {
    previous.flowRate = edge.flowRate === null ? null : Number(edge.flowRate)
  }
  if (input.unit !== undefined) previous.unit = edge.unit
  if (input.delayTimeSec !== undefined) previous.delayTimeSec = edge.delayTimeSec
  if (input.delayBasis !== undefined) previous.delayBasis = edge.delayBasis ?? 'elapsed'
  if (input.lossRate !== undefined) previous.lossRate = edge.lossRate
  if (input.priority !== undefined) previous.priority = edge.priority
  if (input.conditionExpr !== undefined) previous.conditionExpr = edge.conditionExpr ?? ''
  if (input.capacity !== undefined || input.clearCapacity !== undefined) {
    if (edge.capacity === null) {
      previous.clearCapacity = true
    } else {
      previous.capacity = edge.capacity
      previous.clearCapacity = false
    }
  }
  if (input.failurePolicy !== undefined) previous.failurePolicy = edge.failurePolicy
  if (input.defaultRoute !== undefined) previous.defaultRoute = edge.defaultRoute ?? false
  if (input.splitKind !== undefined || input.clearSplit !== undefined) {
    if (edge.splitKind == null) {
      previous.clearSplit = true
    } else {
      previous.splitKind = edge.splitKind
      if (edge.splitValue != null) previous.splitValue = edge.splitValue
    }
  }

  return previous
}
