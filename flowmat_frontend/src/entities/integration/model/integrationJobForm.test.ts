import { describe, expect, it } from 'vitest'
import type { HttpProfile } from '../../connector/model/types'
import { emptyJobForm, exampleMapping, jobBody, jobForm, operationsFor, resultLabel, runSummary } from './integrationJobForm'
import type { IntegrationJob, IntegrationRun } from './types'

const profile: HttpProfile = { profileId: 'p', projectId: 'prj', name: 'Supplier', enabled: true, version: 1, currentRevision: 2,
  profileVersionId: 'pv2', baseOrigin: 'https://api.example.com', credentialAlias: null, createdAt: '', updatedAt: '',
  operations: [
    { operationId: 'send', method: 'POST', relativePath: '/stock', successStatuses: [200] },
    { operationId: 'list', method: 'GET', relativePath: '/items', successStatuses: [200] },
    { operationId: 'drop', method: 'DELETE', relativePath: '/items', successStatuses: [204] },
  ] }

const run = (patch: Partial<IntegrationRun>): IntegrationRun => ({ runId: 'r', jobId: 'j', trigger: 'manual', scheduledFor: null,
  status: 'succeeded', runBy: 'owner', startedAt: null, finishedAt: null, rowsTotal: null, rowsApplied: null, rowsLeftOut: null,
  message: null, items: [], ...patch })

describe('integration job form', () => {
  it('starts with the profile\'s first reading operation and a mapping for the kind', () => {
    const form = emptyJobForm(profile)
    expect(form).toMatchObject({ kind: 'pull_items', profileVersionId: 'pv2', operationId: 'list' })
    expect(JSON.parse(form.mapping)).toEqual({ rowsAt: '/items',
      row: { object: { itemCode: { from: '/sku' }, itemName: { from: '/name' }, unitCode: { literal: 'KG' } } } })
    expect(JSON.parse(exampleMapping('push_stock'))).toMatchObject({ rowsAt: '/stock', row: { object: { quantity: { from: '/onHand' } } } })
  })

  it('sends a reading operation only for a push, a note only for a stock pull, and the version only when editing', () => {
    const base = { ...emptyJobForm(profile), name: ' Levels ', verifyOperationId: 'list', note: ' nightly ' }
    expect(JSON.parse(jobBody(base))).toEqual({ name: 'Levels', kind: 'pull_items', profileVersionId: 'pv2', operationId: 'list',
      verifyOperationId: null, note: null, mapping: JSON.parse(base.mapping) })
    expect(JSON.parse(jobBody({ ...base, kind: 'pull_stock' }))).toMatchObject({ note: 'nightly', verifyOperationId: null })
    expect(JSON.parse(jobBody({ ...base, kind: 'push_stock', operationId: 'send', version: 3 })))
      .toMatchObject({ verifyOperationId: 'list', note: null, version: 3 })
    expect(() => jobBody({ ...base, mapping: '{"rowsAt":' })).toThrow('The mapping must be valid JSON.')
  })

  it('reads a saved job back into the form', () => {
    const job: IntegrationJob = { jobId: 'j', projectId: 'prj', name: 'Push', kind: 'push_stock', profileVersionId: 'pv2',
      operationId: 'send', verifyOperationId: 'list', mapping: { rowsAt: '/stock' }, note: null, scheduleCron: null,
      scheduleEnabled: false, nextRunAt: null, runAs: null, pausedReason: null, failuresInRow: 0, version: 4, lastRun: null }
    expect(jobForm(job)).toMatchObject({ jobId: 'j', version: 4, verifyOperationId: 'list', note: '' })
    expect(JSON.parse(jobForm(job).mapping)).toEqual({ rowsAt: '/stock' })
  })

  it('offers GET operations to read and POST, PUT or PATCH ones to send', () => {
    expect(operationsFor(profile, 'read').map((operation) => operation.operationId)).toEqual(['list'])
    expect(operationsFor(profile, 'send').map((operation) => operation.operationId)).toEqual(['send'])
    expect(operationsFor(undefined, 'read')).toEqual([])
  })

  it('says what a run did in one line', () => {
    expect(runSummary(run({ status: 'partial', rowsTotal: 4, rowsApplied: 2, rowsLeftOut: 2, message: '2 of 4 records were left out.' })))
      .toBe('Partly done · 2 of 4 taken · 2 left out · 2 of 4 records were left out.')
    expect(runSummary(run({ status: 'failed', message: 'The source answered HTTP 503.' }))).toBe('Failed · The source answered HTTP 503.')
    expect(runSummary(run({ status: 'outcome_unknown', rowsTotal: 1, rowsApplied: 0, rowsLeftOut: 0 }))).toBe('Outcome unknown · 0 of 1 taken')
    expect(resultLabel('left_out')).toBe('Left out')
    expect(resultLabel('not_in_flowmat')).toBe('Not in FlowMat')
    expect(resultLabel('something_new')).toBe('something_new')
  })
})
