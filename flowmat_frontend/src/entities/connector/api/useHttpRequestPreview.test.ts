import { afterEach, describe, expect, it, vi } from 'vitest'
import { useHttpRequestPreview } from './useHttpConnectors'

const mock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@tanstack/react-query', () => ({
  useMutation: (options: { mutationFn: () => Promise<unknown>; retry?: boolean }) => ({
    mutateAsync: options.mutationFn, retry: options.retry,
  }),
}))
vi.mock('../../../shared/api/httpClient', () => ({ httpClient: { get: mock.get, post: mock.post } }))

const preview = { profileVersionId: 'profile-v3', operationId: 'send', method: 'POST',
  target: 'https://api.example.com/jobs', hasRequestBody: true, requestJson: '{"amount":9007199254740993.2300}' }

describe('manual HTTP request preview', () => {
  afterEach(() => { vi.clearAllMocks() })

  function hook(runId = 'run', stepId = 'step') {
    return Reflect.apply(useHttpRequestPreview, undefined, [runId, stepId]) as unknown as {
      mutateAsync: () => Promise<typeof preview>; retry: boolean
    }
  }

  it('does not fetch or execute when the preview hook is mounted', () => {
    const result = hook()
    expect(result.retry).toBe(false)
    expect(mock.get).not.toHaveBeenCalled()
    expect(mock.post).not.toHaveBeenCalled()
  })

  it('uses only a GET to the encoded step and preserves the exact server JSON string', async () => {
    mock.get.mockResolvedValueOnce({ success: true, data: preview, message: null })
    const result = await hook('run/one', 'step/two').mutateAsync()
    expect(mock.get).toHaveBeenCalledExactlyOnceWith('/flow-runs/run%2Fone/steps/step%2Ftwo/http-request-preview')
    expect(result.requestJson).toBe('{"amount":9007199254740993.2300}')
    expect(mock.post).not.toHaveBeenCalled()
  })

  it.each([
    { ...preview, method: 'GET', hasRequestBody: false, requestJson: null },
    { ...preview, hasRequestBody: true, requestJson: 'null' },
  ])('preserves body presence separately from a JSON null value: $method/$hasRequestBody', async (expected) => {
    mock.get.mockResolvedValueOnce({ success: true, data: expected, message: null })
    expect(await hook().mutateAsync()).toEqual(expected)
    expect(mock.post).not.toHaveBeenCalled()
  })

  it('returns a failed preview without retrying or issuing an execution POST', async () => {
    const failure = { httpStatus: 403, message: 'Forbidden' }
    mock.get.mockRejectedValueOnce(failure)
    await expect(hook().mutateAsync()).rejects.toBe(failure)
    expect(mock.get).toHaveBeenCalledTimes(1)
    expect(mock.post).not.toHaveBeenCalled()
  })
})
