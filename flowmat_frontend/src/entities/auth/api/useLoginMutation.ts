import { useMutation } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse, unwrapApiVoidResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'
import { getCsrfToken, parseJwtUserId, refreshAccessToken, tokenStorage, withRefreshCookieLock } from '../lib/authSession'

export interface LoginRequest {
  userIdOrEmail: string
  password: string
}

export interface SignupRequest {
  userId: string
  userName: string
  userNickname: string
  userEmail: string
  userTel: string
  userBirth: string
  password: string
}

export interface TokenResponse {
  accessToken: string
  refreshToken?: string | null
}

async function login(req: LoginRequest): Promise<TokenResponse> {
  const envelope = await httpClient.post<ApiEnvelope<TokenResponse>>('/auth/login', req)
  return unwrapApiResponse(envelope)
}

async function signup(req: SignupRequest): Promise<void> {
  const envelope = await httpClient.post<ApiEnvelope<null>>('/auth/signup', req)
  unwrapApiVoidResponse(envelope)
}

async function sendEmailCode(userEmail: string): Promise<void> {
  const envelope = await httpClient.post<ApiEnvelope<null>>('/auth/email/send-code', { userEmail })
  unwrapApiVoidResponse(envelope)
}

async function verifyEmailCode(input: { userEmail: string; code: string }): Promise<void> {
  const envelope = await httpClient.post<ApiEnvelope<null>>('/auth/email/verify-code', input)
  unwrapApiVoidResponse(envelope)
}

async function requestDormantReactivation(userIdOrEmail: string): Promise<void> {
  const envelope = await httpClient.post<ApiEnvelope<null>>('/auth/dormant/reactivation/request', { userIdOrEmail })
  unwrapApiVoidResponse(envelope)
}

async function reactivateDormant(token: string): Promise<void> {
  const envelope = await httpClient.post<ApiEnvelope<null>>('/auth/dormant/reactivate', { token })
  unwrapApiVoidResponse(envelope)
}

async function requestDormant(): Promise<void> {
  const envelope = await httpClient.post<ApiEnvelope<null>>('/users/me/dormant', {})
  unwrapApiVoidResponse(envelope)
}

export function useLoginMutation() {
  return useMutation({
    mutationFn: (request: LoginRequest) => withRefreshCookieLock(async () => {
      const data = await login(request)
      tokenStorage.setAccessToken(data.accessToken, { cookieBacked: true })
      return data
    }),
  })
}

export function useSignupMutation() {
  return useMutation({ mutationFn: signup })
}

export function useSendEmailCodeMutation() {
  return useMutation({ mutationFn: sendEmailCode })
}

export function useVerifyEmailCodeMutation() {
  return useMutation({ mutationFn: verifyEmailCode })
}

export function useRequestDormantReactivationMutation() {
  return useMutation({ mutationFn: requestDormantReactivation })
}

export function useReactivateDormantMutation() {
  return useMutation({ mutationFn: reactivateDormant })
}

export function useRequestDormantMutation() {
  return useMutation({ mutationFn: requestDormant })
}

export function useLogoutMutation() {
  return useMutation({
    mutationFn: () => withRefreshCookieLock(async () => {
      try {
        const csrfToken = await getCsrfToken()
        await fetch('/api/auth/logout', {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'X-XSRF-TOKEN': csrfToken, 'Content-Type': 'application/json' },
        })
      } catch {
        // Ignore logout transport failures. Local session is cleared regardless.
      } finally {
        // Clear the shared hint before releasing the lock to waiting refresh requests.
        tokenStorage.clear()
      }
    }),
  })
}

export { parseJwtUserId, refreshAccessToken, tokenStorage }
