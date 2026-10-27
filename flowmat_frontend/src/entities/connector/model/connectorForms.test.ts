import { describe, expect, it } from 'vitest'
import { bindingBody, profileBody, profileForm, emptyProfileForm, rawRunInputBody } from './connectorForms'

describe('HTTP profile and binding forms', () => {
  it('retains decimal literals and scale when sending a binding', () => {
    const requestMapping = '{"literal":{"amount":9007199254740993.2300}}'
    const responseMapping = '{"from":""}'
    const body = bindingBody({ profileVersionId: 'revision', operationId: 'send', outputPortId: 'out',
      requestMapping, responseMapping, expectedVersion: 4 })
    expect(body).toContain(requestMapping)
    expect(JSON.parse(body)).toMatchObject({ expectedVersion: 4, profileVersionId: 'revision' })
  })

  it('rejects malformed mappings before sending and clears all optional binding fields together', () => {
    expect(() => bindingBody({ profileVersionId: 'revision', operationId: 'send', outputPortId: 'out',
      requestMapping: '{', responseMapping: '{}', expectedVersion: 0 })).toThrow('Request mapping')
    expect(JSON.parse(bindingBody(null, 7))).toEqual({ profileVersionId: null, operationId: null,
      outputPortId: null, requestMapping: null, responseMapping: null, expectedVersion: 7 })
  })

  it('pins the edit version instead of taking a later background query version', () => {
    const form = profileForm({ profileId: 'p', projectId: 'prj', name: 'Dispatch', enabled: true, version: 3,
      currentRevision: 2, profileVersionId: 'r', baseOrigin: 'https://api.example.com', credentialAlias: null,
      operations: [{ operationId: 'send', method: 'POST', relativePath: '/jobs', successStatuses: [202] }],
      createdAt: '', updatedAt: '' })
    expect(JSON.parse(profileBody(form))).toMatchObject({ expectedVersion: 3, credentialAlias: null })
    expect(JSON.parse(profileBody({ ...emptyProfileForm(), name: 'New' }))).not.toHaveProperty('expectedVersion')
  })

  it('requires operation JSON to be a nonempty array without accepting a secret input field', () => {
    expect(() => profileBody({ ...emptyProfileForm(), operations: '{}' })).toThrow('Operations')
    expect(() => profileBody({ ...emptyProfileForm(), operations: '[]' })).toThrow('Operations')
    expect(JSON.parse(profileBody(emptyProfileForm()))).not.toHaveProperty('password')
  })

  it('keeps exact input digits when starting a graph run whose HTTP mapping copies its source', () => {
    const input = '{"quantity":9007199254740993.2300,"attrs":{"at":"2026-10-24"}}'
    const body = rawRunInputBody({ workflowId: 'workflow', workflowRevisionId: 'revision', runType: 'actual' }, input)
    expect(body).toContain(input)
    expect(JSON.parse(body)).toMatchObject({ workflowId: 'workflow', workflowRevisionId: 'revision', runType: 'actual' })
    expect(() => rawRunInputBody({ workflowId: 'workflow', workflowRevisionId: 'revision', runType: 'actual' }, '{'))
      .toThrow('Run input must contain valid JSON.')
  })
})


describe('profile operation JSON retains the original validation input', () => {
  it.each([
    ['fractional token', '[{"operationId":"send","method":"POST","relativePath":"/jobs","successStatuses":[200.0]}]'],
    ['rounded boundary', '[{"operationId":"send","method":"POST","relativePath":"/jobs","successStatuses":[199.99999999999999]}]'],
    ['duplicate keys', '[{"operationId":"send","method":"POST","method":"DELETE","relativePath":"/jobs","successStatuses":[200]}]'],
  ])('preserves %s for the strict server parser', (_case, operations) => {
    const body = profileBody({ ...emptyProfileForm(), operations })
    expect(body).toContain(`"operations":${operations}`)
  })

  it('escapes and trims ordinary metadata while keeping operation whitespace and an edit version', () => {
    const operations = ' [ {"operationId":"send","method":"POST","relativePath":"/jobs","successStatuses":[200,202]} ] '
    const body = profileBody({ ...emptyProfileForm(), expectedVersion: 7, name: '  Dispatch "priority"  ',
      baseOrigin: '  https://api.example.com  ', credentialAlias: '  dispatch  ', enabled: false, operations })
    expect(body).toContain(`"operations":${operations}`)
    expect(JSON.parse(body)).toMatchObject({ expectedVersion: 7, name: 'Dispatch "priority"',
      baseOrigin: 'https://api.example.com', credentialAlias: 'dispatch', enabled: false })
    expect(JSON.parse(profileBody({ ...emptyProfileForm(), credentialAlias: '  ' }))).toHaveProperty('credentialAlias', null)
  })

  it('rejects malformed operation JSON locally without constructing a body', () => {
    expect(() => profileBody({ ...emptyProfileForm(), operations: '[{' })).toThrow('Operations must contain valid JSON.')
  })
})
