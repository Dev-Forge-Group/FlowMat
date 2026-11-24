import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'
import { tokenStorage } from '../../../entities/auth/lib/authSession'

type ExchangeResultType = 'login' | 'signup_required'

interface OAuthExchangeResponse {
  resultType: ExchangeResultType
  accessToken: string | null
  signupToken: string | null
  provider: string | null
  deviceId: string | null
  additionalInfoRequired: boolean
}

interface TokenResponse {
  accessToken: string
  refreshToken: string | null
  deviceId: string | null
  additionalInfoRequired: boolean
}

type Phase = 'exchanging' | 'signup' | 'redirecting' | 'error'

async function exchangeOAuthCode(code: string): Promise<OAuthExchangeResponse> {
  const envelope = await httpClient.post<ApiEnvelope<OAuthExchangeResponse>>('/auth/oauth2/exchange', { code })
  return unwrapApiResponse(envelope)
}

async function completeOAuthSignup(
  provider: string,
  payload: {
    signupToken: string
    userId: string
    userName: string
    userNickname: string
    userTel: string
    userBirth: string | null
    password: string
  }
): Promise<TokenResponse> {
  const envelope = await httpClient.post<ApiEnvelope<TokenResponse>>(`/auth/oauth2/${provider}/complete`, payload)
  return unwrapApiResponse(envelope)
}

export function OAuthCallbackRoute() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [phase, setPhase] = useState<Phase>('exchanging')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [provider, setProvider] = useState<'google' | 'kakao' | null>(null)
  const [signupToken, setSignupToken] = useState<string | null>(null)
  const [userId, setUserId] = useState('')
  const [userName, setUserName] = useState('')
  const [userNickname, setUserNickname] = useState('')
  const [userTel, setUserTel] = useState('')
  const [userBirth, setUserBirth] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const code = searchParams.get('code')
  const oauthError = searchParams.get('error')
  const oauthReason = searchParams.get('reason')

  const normalizedProvider = useMemo(() => {
    if (provider === 'google' || provider === 'kakao') return provider
    return null
  }, [provider])

  useEffect(() => {
    if (oauthError) {
      setErrorMessage(oauthReason || 'OAuth login failed.')
      setPhase('error')
      return
    }

    if (!code) {
      setErrorMessage('OAuth callback code is missing.')
      setPhase('error')
      return
    }

    let cancelled = false

    void exchangeOAuthCode(code)
      .then((result) => {
        if (cancelled) return

        if (result.resultType === 'login' && result.accessToken) {
          tokenStorage.setAccessToken(result.accessToken, { cookieBacked: true })
          setPhase('redirecting')
          navigate('/', { replace: true })
          return
        }

        if (result.resultType === 'signup_required' && result.signupToken && result.provider) {
          const nextProvider = result.provider.toLowerCase()
          if (nextProvider !== 'google' && nextProvider !== 'kakao') {
            setErrorMessage(`Unsupported OAuth provider: ${result.provider}`)
            setPhase('error')
            return
          }
          setProvider(nextProvider)
          setSignupToken(result.signupToken)
          setPhase('signup')
          return
        }

        setErrorMessage('OAuth exchange response was incomplete.')
        setPhase('error')
      })
      .catch((error: unknown) => {
        if (cancelled) return
        setErrorMessage(error instanceof Error ? error.message : 'OAuth exchange failed.')
        setPhase('error')
      })

    return () => {
      cancelled = true
    }
  }, [code, navigate, oauthError, oauthReason])

  async function handleSignupSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!signupToken || !normalizedProvider) return

    setSubmitting(true)
    setErrorMessage(null)
    try {
      const response = await completeOAuthSignup(normalizedProvider, {
        signupToken,
        userId,
        userName,
        userNickname,
        userTel,
        userBirth: userBirth || null,
        password,
      })
      tokenStorage.setAccessToken(response.accessToken, { cookieBacked: true })
      setPhase('redirecting')
      navigate('/', { replace: true })
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : 'OAuth sign-up completion failed.')
    } finally {
      setSubmitting(false)
    }
  }

  const shellStyle: React.CSSProperties = {
    minHeight: '100svh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    boxSizing: 'border-box',
  }

  const cardStyle: React.CSSProperties = {
    width: 420,
    maxWidth: '100%',
    padding: 28,
    border: '1px solid var(--border)',
    borderRadius: 16,
    display: 'grid',
    gap: 14,
    background: 'var(--bg)',
  }

  if (phase === 'exchanging' || phase === 'redirecting') {
    return (
      <div style={shellStyle}>
        <div style={cardStyle}>
          <h2 style={{ margin: 0 }}>Completing OAuth Sign-In</h2>
          <p style={{ margin: 0, opacity: 0.7 }}>
            {phase === 'redirecting' ? 'Redirecting to FlowMat...' : 'Exchanging the OAuth callback with the server...'}
          </p>
        </div>
      </div>
    )
  }

  if (phase === 'error') {
    return (
      <div style={shellStyle}>
        <div style={cardStyle}>
          <h2 style={{ margin: 0 }}>OAuth Sign-In Failed</h2>
          <p style={{ margin: 0, color: '#dc2626' }}>{errorMessage}</p>
          <Link to="/" style={{ color: 'var(--accent)' }}>Back to home</Link>
        </div>
      </div>
    )
  }

  return (
    <div style={shellStyle}>
      <form onSubmit={(event) => void handleSignupSubmit(event)} style={cardStyle}>
        <h2 style={{ margin: 0 }}>Complete Your OAuth Sign-Up</h2>
        <p style={{ margin: 0, opacity: 0.7 }}>
          Finish the remaining FlowMat profile fields for your {normalizedProvider} account.
        </p>
        <input value={userId} onChange={(event) => setUserId(event.target.value)} placeholder="User ID" required />
        <input value={userName} onChange={(event) => setUserName(event.target.value)} placeholder="Name" required />
        <input value={userNickname} onChange={(event) => setUserNickname(event.target.value)} placeholder="Nickname" required />
        <input value={userTel} onChange={(event) => setUserTel(event.target.value)} placeholder="Phone number" required />
        <label style={{ display: 'grid', gap: 4 }}>
          <span style={{ fontSize: 12, opacity: 0.7 }}>Birth date (optional)</span>
          <input type="date" value={userBirth} onChange={(event) => setUserBirth(event.target.value)} />
        </label>
        <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="Password (8 or more characters, not your user ID)" required minLength={8} />
        {errorMessage && <p style={{ margin: 0, color: '#dc2626', fontSize: 13 }}>{errorMessage}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Completing...' : 'Complete Sign-Up'}
        </button>
        <Link to="/" style={{ color: 'var(--accent)', fontSize: 13 }}>Cancel</Link>
      </form>
    </div>
  )
}
