import { afterEach, describe, expect, it, vi } from 'vitest'
import { useHttpProfileReadiness } from './useHttpProfileReadiness'

const mock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@tanstack/react-query', () => ({
  useMutation: (options: { mutationFn: () => Promise<unknown>; retry?: boolean }) => ({
    mutateAsync: options.mutationFn, retry: options.retry,
  }),
}))
vi.mock('../../../shared/api/httpClient', () => ({ httpClient: { get: mock.get, post: mock.post } }))

const ready = { profileVersionId: 'profile-v3', configurationReady: true, blockers: [],
  credentialValuesChecked: false, connectionTested: false }

describe('manual HTTP profile configuration check', () => {
  afterEach(() => { vi.clearAllMocks() })

  function hook(projectId = 'project', profileVersionId = 'profile-v3') {
    return Reflect.apply(useHttpProfileReadiness, undefined, [projectId, profileVersionId]) as unknown as {
      mutateAsync: () => Promise<typeof ready>; retry: boolean
    }
  }

  it('does not automatically fetch or issue a POST on mount', () => {
    expect(hook().retry).toBe(false)
    expect(mock.get).not.toHaveBeenCalled()
    expect(mock.post).not.toHaveBeenCalled()
  })

  it('manually reads only the encoded project and profile version endpoint', async () => {
    mock.get.mockResolvedValueOnce({ success: true, data: ready, message: null })
    expect(await hook('project/one', 'version/two').mutateAsync()).toEqual(ready)
    expect(mock.get).toHaveBeenCalledExactlyOnceWith('/projects/project%2Fone/http-profiles/by-version/version%2Ftwo/readiness')
    expect(mock.post).not.toHaveBeenCalled()
  })

  it('keeps configuration readiness separate from credential and connection checks', async () => {
    mock.get.mockResolvedValueOnce({ success: true, data: ready, message: null })
    const result = await hook().mutateAsync()
    expect(result.configurationReady).toBe(true)
    expect(result.credentialValuesChecked).toBe(false)
    expect(result.connectionTested).toBe(false)
  })

  it('preserves safe blocker codes without invoking execution', async () => {
    const blocked = { ...ready, configurationReady: false, blockers: ['CONNECTOR_DISABLED', 'PROFILE_DISABLED'] }
    mock.get.mockResolvedValueOnce({ success: true, data: blocked, message: null })
    expect(await hook().mutateAsync()).toEqual(blocked)
    expect(mock.post).not.toHaveBeenCalled()
  })

  it('does not retry a failed check or turn it into an execution request', async () => {
    const failure = { httpStatus: 403, message: 'Forbidden' }
    mock.get.mockRejectedValueOnce(failure)
    await expect(hook().mutateAsync()).rejects.toBe(failure)
    expect(mock.get).toHaveBeenCalledTimes(1)
    expect(mock.post).not.toHaveBeenCalled()
  })
})
