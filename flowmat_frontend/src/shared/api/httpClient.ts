import { normalizeUiError } from '../lib/normalizeUiError'
import type { ApiEnvelope } from '../types/api'
import { refreshAccessToken, tokenStorage } from '../../entities/auth/lib/authSession'

const BASE = '/api'

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'

function isApiEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  return typeof value === 'object' && value !== null && 'success' in value && 'message' in value
}

function buildHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = tokenStorage.getAccess()
  if (token) headers['Authorization'] = `Bearer ${token}`
  return headers
}

async function request<T>(method: HttpMethod, path: string, body?: unknown, rawJson = false): Promise<T> {
  if (rawJson) {
    try { if (typeof body !== 'string') throw new Error(); JSON.parse(body) }
    catch { throw new Error('Enter valid JSON.') }
  }
  return requestInternal<T>(method, path, body, false, rawJson)
}

async function readResponseBody(res: Response): Promise<unknown> {
  const text = await res.text()
  if (!text) return null

  try {
    return JSON.parse(text) as unknown
  } catch {
    return text
  }
}

async function requestInternal<T>(method: HttpMethod, path: string, body: unknown, retried: boolean, rawJson = false): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: buildHeaders(),
    credentials: 'same-origin',
    body: rawJson ? body as string : body !== undefined ? JSON.stringify(body) : undefined,
  })

  const payload = await readResponseBody(res)

  if (!retried && res.status === 401 && !path.startsWith('/auth/')) {
    const refreshed = await refreshAccessToken()
    if (refreshed) {
      return requestInternal<T>(method, path, body, true, rawJson)
    }
  }

  if (!res.ok) {
    const message =
      isApiEnvelope(payload) && typeof payload.message === 'string' && payload.message.trim().length > 0
        ? payload.message
        : typeof payload === 'string' && payload.trim().length > 0
          ? payload
          : `Request failed with status ${res.status}`
    throw normalizeUiError(res.status, message)
  }

  return payload as T
}

export const httpClient = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body: unknown) => request<T>('POST', path, body),
  postJson: <T>(path: string, json: string) => request<T>('POST', path, json, true),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  putJson: <T>(path: string, json: string) => request<T>('PUT', path, json, true),
  patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
}
