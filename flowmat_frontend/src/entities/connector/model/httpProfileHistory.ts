import type { HttpOperation, HttpProfileVersion } from './types'

type OperationField = 'method' | 'relativePath' | 'successStatuses'
interface OperationChange {
  operationId: string
  kind: 'added' | 'removed' | 'changed'
  before: HttpOperation | null
  after: HttpOperation | null
  changedFields: OperationField[]
}
interface ProfileComparison {
  fields: { field: 'baseOrigin' | 'credentialAlias'; before: string | null; after: string | null }[]
  operations: OperationChange[]
}

function normalizedOperation(operation: HttpOperation): HttpOperation {
  return { ...operation, successStatuses: [...new Set(operation.successStatuses)].sort((a, b) => a - b) }
}

export function compareHttpProfileVersions(from: HttpProfileVersion, to: HttpProfileVersion): ProfileComparison | null {
  if (from.profileId !== to.profileId) return null
  const fields: ProfileComparison['fields'] = []
  for (const field of ['baseOrigin', 'credentialAlias'] as const) {
    if (from[field] !== to[field]) fields.push({ field, before: from[field], after: to[field] })
  }
  const before = new Map(from.operations.map((operation) => [operation.operationId, normalizedOperation(operation)]))
  const after = new Map(to.operations.map((operation) => [operation.operationId, normalizedOperation(operation)]))
  const operations: OperationChange[] = []
  for (const operationId of [...new Set([...before.keys(), ...after.keys()])].sort()) {
    const previous = before.get(operationId) ?? null
    const next = after.get(operationId) ?? null
    if (!previous || !next) {
      operations.push({ operationId, kind: previous ? 'removed' : 'added', before: previous, after: next, changedFields: [] })
      continue
    }
    const changedFields: OperationField[] = []
    if (previous.method !== next.method) changedFields.push('method')
    if (previous.relativePath !== next.relativePath) changedFields.push('relativePath')
    if (previous.successStatuses.join(',') !== next.successStatuses.join(',')) changedFields.push('successStatuses')
    if (changedFields.length) operations.push({ operationId, kind: 'changed', before: previous, after: next, changedFields })
  }
  return { fields, operations }
}

/** Use an explicit pinned ID; never substitute a different current version after a partial history read. */
export function defaultHttpProfileComparison(versions: HttpProfileVersion[], currentVersionId: string) {
  const current = versions.find((version) => version.profileVersionId === currentVersionId)
  if (!current) return { fromVersionId: '', toVersionId: '' }
  const previous = versions.filter((version) => version.profileId === current.profileId && version.revision < current.revision)
    .sort((a, b) => b.revision - a.revision)[0] ?? current
  return { fromVersionId: previous.profileVersionId, toVersionId: current.profileVersionId }
}
