import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

beforeEach(() => {
  vi.resetModules()
  const values = new Map<string, string>()
  vi.stubGlobal('window', Object.assign(new EventTarget(), { localStorage: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => void values.set(key, value),
    removeItem: (key: string) => void values.delete(key),
  } }))
  vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=fixture' })
})
afterEach(() => vi.unstubAllGlobals())

const attachmentId = '00000000-0000-4000-8000-000000000001'
const row = { attachmentId, instructionId: 'instruction', fileName: 'Guard.txt', contentType: 'text/plain',
  sizeBytes: 5, sha256: 'a'.repeat(64), createdBy: 'owner', createdAt: null }

describe('attachment requests from retired sessions', () => {
  for (const action of ['upload', 'download'] as const) {
    it.each([200, 401])(`rejects delayed owner ${action} %i without replaying as viewer`, async (status) => {
      const { tokenStorage } = await import('../../auth/lib/authSession')
      const { uploadInstructionAttachment, downloadInstructionAttachment } = await import('./instructionAttachments')
      tokenStorage.setAccessToken('fixture.owner.session', { cookieBacked: true })
      let finish!: (response: Response) => void
      const success = () => new Response(action === 'upload' ? JSON.stringify({ success: true, data: row }) : 'Guard')
      const fetch = vi.fn().mockImplementationOnce(() => new Promise<Response>((done) => { finish = done }))
        .mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: { accessToken: 'fixture.viewer.refreshed' } })))
        .mockImplementation(success)
      vi.stubGlobal('fetch', fetch)
      const pending = action === 'upload'
        ? uploadInstructionAttachment('instruction', { attachmentId, file: new File(['Guard'], 'Guard.txt') })
        : downloadInstructionAttachment(row)
      tokenStorage.clear()
      tokenStorage.setAccessToken('fixture.viewer.session', { cookieBacked: true })
      finish(status === 200 ? success() : new Response('{}', { status }))
      await expect(pending).rejects.toThrow(/session changed/i)
      expect(fetch.mock.calls.length).toBe(1)
    })
  }
})
