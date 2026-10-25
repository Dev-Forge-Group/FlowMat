import { describe, expect, it } from 'vitest'
import type { HttpOperation, HttpProfileVersion } from './types'
import { compareHttpProfileVersions, defaultHttpProfileComparison } from './httpProfileHistory'

const operation: HttpOperation = { operationId: 'send', method: 'POST', relativePath: '/jobs', successStatuses: [202, 200] }
function version(revision: number, changes: Partial<HttpProfileVersion> = {}): HttpProfileVersion {
  return { profileVersionId: `v${revision}`, profileId: 'profile', revision, baseOrigin: 'https://api.example.com',
    credentialAlias: null, operations: [operation], createdAt: '2026-10-24T00:00:00Z', ...changes }
}

describe('immutable HTTP profile revision comparison', () => {
  it('identifies origin and credential alias references as separate changes', () => {
    const result = compareHttpProfileVersions(version(1), version(2, { baseOrigin: 'https://next.example.com', credentialAlias: 'dispatch' }))!
    expect(result.fields).toEqual([
      { field: 'baseOrigin', before: 'https://api.example.com', after: 'https://next.example.com' },
      { field: 'credentialAlias', before: null, after: 'dispatch' },
    ])
    expect(result.operations).toEqual([])
  })

  it('lists added, removed and changed operations in a stable order by operationId', () => {
    const from = version(1, { operations: [{ ...operation, operationId: 'z-remove' }, operation] })
    const to = version(2, { operations: [{ ...operation, method: 'PUT' }, { ...operation, operationId: 'a-add' }] })
    const result = compareHttpProfileVersions(from, to)!
    expect(result.operations.map(({ operationId, kind }) => [operationId, kind])).toEqual([
      ['a-add', 'added'], ['send', 'changed'], ['z-remove', 'removed'],
    ])
    expect(result.operations[0]).toMatchObject({ before: null, after: { operationId: 'a-add' } })
    expect(result.operations[2]).toMatchObject({ before: { operationId: 'z-remove' }, after: null })
  })

  it('shows each changed method, path and accepted status set', () => {
    const result = compareHttpProfileVersions(version(1), version(2, { operations: [
      { ...operation, method: 'PATCH', relativePath: '/jobs/new', successStatuses: [204, 201] },
    ] }))!
    expect(result.operations[0].changedFields).toEqual(['method', 'relativePath', 'successStatuses'])
    expect(result.operations[0].before?.successStatuses).toEqual([200, 202])
    expect(result.operations[0].after?.successStatuses).toEqual([201, 204])
  })

  it.each([
    ['method', { method: 'GET' }],
    ['relativePath', { relativePath: '/other' }],
    ['successStatuses', { successStatuses: [204] }],
  ] as ['method' | 'relativePath' | 'successStatuses', Partial<HttpOperation>][])('reports only the changed %s field', (field, change) => {
    const result = compareHttpProfileVersions(version(1), version(2, { operations: [{ ...operation, ...change }] }))!
    expect(result.operations[0].changedFields).toEqual([field])
  })

  it('ignores operation and status ordering, including a repeated status', () => {
    const other = { ...operation, operationId: 'read', method: 'GET' as const }
    const result = compareHttpProfileVersions(version(1, { operations: [operation, other] }), version(2, {
      operations: [{ ...other, successStatuses: [200, 202, 202] }, { ...operation, successStatuses: [200, 202] }],
    }))!
    expect(result).toEqual({ fields: [], operations: [] })
  })

  it('does not consider version metadata or a comparison with itself to be a configuration change', () => {
    expect(compareHttpProfileVersions(version(1), version(2, { createdAt: '2026-10-25T00:00:00Z' })))
      .toEqual({ fields: [], operations: [] })
    expect(compareHttpProfileVersions(version(1), version(1))).toEqual({ fields: [], operations: [] })
  })

  it('does not mutate operations or status arrays while normalizing them', () => {
    const from = version(1)
    const to = version(2, { operations: [{ ...operation, successStatuses: [204, 201] }] })
    const before = JSON.stringify([from, to])
    compareHttpProfileVersions(from, to)
    expect(JSON.stringify([from, to])).toBe(before)
  })

  it('does not mix configuration from different profiles', () => {
    expect(compareHttpProfileVersions(version(1), version(2, { profileId: 'other' }))).toBeNull()
  })
})

describe('initial HTTP profile revision selection', () => {
  it('chooses the preceding revision and the requested current version from an unordered list', () => {
    const history = [version(1), version(4), version(2), version(3)]
    expect(defaultHttpProfileComparison(history, 'v3')).toEqual({ fromVersionId: 'v2', toVersionId: 'v3' })
    expect(history.map(({ revision }) => revision)).toEqual([1, 4, 2, 3])
  })

  it('uses the sole version on both sides without inventing an earlier revision', () => {
    expect(defaultHttpProfileComparison([version(1)], 'v1')).toEqual({ fromVersionId: 'v1', toVersionId: 'v1' })
  })

  it('requires a selection when the current version is missing instead of silently choosing another version', () => {
    expect(defaultHttpProfileComparison([version(1)], 'missing')).toEqual({ fromVersionId: '', toVersionId: '' })
    expect(defaultHttpProfileComparison([], 'v1')).toEqual({ fromVersionId: '', toVersionId: '' })
  })

  it('does not select a preceding revision from a different profile', () => {
    expect(defaultHttpProfileComparison([version(2, { profileId: 'other' }), version(1), version(3)], 'v3'))
      .toEqual({ fromVersionId: 'v1', toVersionId: 'v3' })
  })
})
