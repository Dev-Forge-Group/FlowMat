import { NotificationsPanel } from './NotificationsPanel'
import { useEffect, useMemo, useState, type FormEvent, type MouseEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { FlowCanvasBackground } from './FlowCanvasBackground'
import { useCreateProjectMutation } from '../../../entities/project/api/useCreateProjectMutation'
import { useProjectsQuery } from '../../../entities/project/api/useProjectsQuery'
import { useOrganizationsQuery } from '../../../entities/organization/api/useOrganizations'
import { useCreateWorkflowMutation } from '../../../entities/workflow/api/useCreateWorkflowMutation'
import { useWorkflowsQuery } from '../../../entities/workflow/api/useWorkflowsQuery'
import {
  useLoginMutation,
  useLogoutMutation,
  useRequestDormantMutation,
  useRequestDormantReactivationMutation,
  useSendEmailCodeMutation,
  useSignupMutation,
  useVerifyEmailCodeMutation,
} from '../../../entities/auth/api/useLoginMutation'
import { useCurrentUserQuery } from '../../../entities/auth/api/useCurrentUserQuery'
import { useMyPermissionsQuery } from '../../../entities/auth/api/useMyPermissionsQuery'
import { refreshAccessToken, subscribeAuthChange, tokenStorage } from '../../../entities/auth/lib/authSession'
import {
  preloadInventoryRoute,
  preloadRulesRoute,
  preloadRunsRoute,
  preloadTemplatesRoute,
} from '../../../app/router/routePreload'
import { preloadWorkspaceExperience } from '../../workspace/ui/workspacePreload'
import { StartFromSample } from '../../../features/sample-start/ui/StartFromSample'
import { StartFromDescription } from '../../../features/sample-start/ui/StartFromDescription'
import { showsDemoLogin } from '../model/demoLogin'

type DashboardProps = {
  onLogout: () => void
  onRequestDormant: () => void
  isLoggingOut: boolean
}

function Dashboard({ onLogout, onRequestDormant, isLoggingOut }: DashboardProps) {
  const navigate = useNavigate()
  const currentUserQuery = useCurrentUserQuery()
  const currentUser = currentUserQuery.data
  const permissionsQuery = useMyPermissionsQuery(!!currentUser)

  const {
    data: projects = [],
    isLoading: isProjectsLoading,
    isError: isProjectsError,
    error: projectsError,
  } = useProjectsQuery()

  const [selectedProjectId, setSelectedProjectId] = useState('')
  const [projectName, setProjectName] = useState('')
  const [projectDesc, setProjectDesc] = useState('')
  const [organizationId, setOrganizationId] = useState('')
  // Team organizations only; without a choice the project goes to the creator's personal one (OR6).
  const teamOrganizations = (useOrganizationsQuery().data ?? []).filter((organization) => organization.organizationType === 'team')
  const chosenOrganizationId = teamOrganizations.some((organization) => organization.organizationId === organizationId) ? organizationId : ''
  const [workflowName, setWorkflowName] = useState('')
  const [workflowDesc, setWorkflowDesc] = useState('')

  const createProjectMutation = useCreateProjectMutation()
  const createWorkflowMutation = useCreateWorkflowMutation()

  const selectedProject = projects.find((project) => project.projectId === selectedProjectId)

  const projectLinks = useMemo(
    () =>
      selectedProjectId
        ? [
            {
              label: 'Inventory',
              to: `/projects/${selectedProjectId}/inventory`,
              preload: preloadInventoryRoute,
            },
            {
              label: 'Runs',
              to: `/projects/${selectedProjectId}/runs`,
              preload: preloadRunsRoute,
            },
            {
              label: 'Templates',
              to: `/projects/${selectedProjectId}/templates`,
              preload: preloadTemplatesRoute,
            },
            {
              label: 'Rules',
              to: `/projects/${selectedProjectId}/rules`,
              preload: preloadRulesRoute,
            },
            {
              label: 'Settings',
              to: `/projects/${selectedProjectId}/settings`,
              preload: async () => {},
            },
          ]
        : [],
    [selectedProjectId]
  )

  useEffect(() => {
    if (!selectedProjectId && projects.length > 0) {
      setSelectedProjectId(projects[0].projectId)
    }
  }, [projects, selectedProjectId])

  const {
    data: workflows = [],
    isLoading: isWorkflowsLoading,
    isError: isWorkflowsError,
    error: workflowsError,
  } = useWorkflowsQuery(selectedProjectId)

  async function handleCreateProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (!currentUser?.userId) {
      return
    }

    const created = await createProjectMutation.mutateAsync({
      projectName,
      ownerId: currentUser.userId,
      projectDesc,
      visibility: 'private',
      organizationId: chosenOrganizationId || undefined,
    })

    setProjectName('')
    setProjectDesc('')
    setSelectedProjectId(created.projectId)
  }

  async function handleCreateWorkflow(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (!selectedProjectId) {
      return
    }

    const preload = preloadWorkspaceExperience()

    const created = await createWorkflowMutation.mutateAsync({
      projectId: selectedProjectId,
      workflowName,
      workflowDesc,
      workflowType: 'main',
    })

    setWorkflowName('')
    setWorkflowDesc('')

    await preload
    navigate(`/projects/${created.projectId}/workflows/${created.workflowId}`)
  }

  const initials = (currentUser?.userName ?? '?').trim().slice(0, 2).toUpperCase()

  return (
    <div className="home-page min-h-[100svh] bg-[#12131a] px-4 py-8 pb-40 text-[#f2f1f6] sm:px-8">
      <div className="mx-auto max-w-[1080px]">
        <header className="mb-10 flex items-center justify-between">
          <div className="text-[17px] font-extrabold tracking-tight">
            Flow<span className="text-[#9b82ff]">Mat</span>
          </div>

          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={onRequestDormant}
              className="rounded-[10px] px-3.5 py-2 text-[13px] font-medium text-[#9694a3] transition hover:bg-[#1a1b24] hover:text-[#f09595]"
            >
              Dormant
            </button>

            <Link
              to="/organizations"
              className="rounded-[10px] px-3.5 py-2 text-[13px] font-medium text-[#9694a3] transition hover:bg-[#1a1b24] hover:text-[#f2f1f6]"
            >
              Organizations
            </Link>

            {permissionsQuery.data?.canManageUsers && (
              <Link
                to="/admin"
                className="rounded-[10px] px-3.5 py-2 text-[13px] font-medium text-[#9694a3] transition hover:bg-[#1a1b24] hover:text-[#f2f1f6]"
              >
                Admin
              </Link>
            )}

            <button
              type="button"
              onClick={onLogout}
              disabled={isLoggingOut}
              className="rounded-[10px] px-3.5 py-2 text-[13px] font-medium text-[#9694a3] transition hover:bg-[#1a1b24] hover:text-[#f2f1f6]"
            >
              Logout
            </button>

            <div className="ml-1.5 flex h-8 w-8 items-center justify-center rounded-full bg-[rgba(124,92,255,0.16)] text-xs font-bold text-[#9b82ff]">
              {initials}
            </div>
          </div>
        </header>

        <h1 className="mb-1.5 text-2xl font-bold tracking-tight">
          {currentUser ? `안녕하세요, ${currentUser.userName}님` : '안녕하세요'}
        </h1>
        <p className="mb-8 text-[14.5px] text-[#9694a3]">
          오늘도 생산 흐름을 정리해볼까요.
        </p>

        {currentUser?.userId && <NotificationsPanel userId={currentUser.userId} />}

        {projectLinks.length > 0 && (
          <div className="mb-8 flex flex-wrap gap-2">
            {projectLinks.map((item) => (
              <Link
                key={item.label}
                to={item.to}
                onMouseEnter={() => void item.preload()}
                onFocus={() => void item.preload()}
                className="rounded-full border border-[#26272f] px-3.5 py-1.5 text-[12.5px] font-medium text-[#9694a3] transition hover:border-[#7c5cff] hover:text-[#f2f1f6]"
              >
                {item.label}
              </Link>
            ))}
          </div>
        )}

        <div className="mb-10 grid grid-cols-1 gap-3 sm:grid-cols-2">
          <form
            onSubmit={(event) => void handleCreateProject(event)}
            className="flex h-full min-w-0 flex-col rounded-2xl bg-[#1a1b24] p-5 ring-1 ring-transparent transition focus-within:bg-[#202130] focus-within:ring-[#7c5cff]"
          >
            <label className="mb-3.5 block text-[13px] font-semibold">
              새 프로젝트
            </label>

            <input
              value={projectName}
              onChange={(event) => setProjectName(event.target.value)}
              placeholder="프로젝트 이름"
              required
              className="mb-2 w-full rounded-[11px] border-2 border-[#26272f] bg-[#12131a] px-3.5 py-2.5 text-sm text-[#f2f1f6] outline-none placeholder:text-[#5f5d6b] focus:border-[#9b82ff] focus:shadow-[0_0_0_3px_rgba(124,92,255,0.28)]"
            />

            <textarea
              value={projectDesc}
              onChange={(event) => setProjectDesc(event.target.value)}
              placeholder="설명 (선택)"
              rows={2}
              className="mb-2 w-full resize-none rounded-[11px] border-2 border-[#26272f] bg-[#12131a] px-3.5 py-2.5 text-sm text-[#f2f1f6] outline-none placeholder:text-[#5f5d6b] focus:border-[#9b82ff] focus:shadow-[0_0_0_3px_rgba(124,92,255,0.28)]"
            />

            {teamOrganizations.length > 0 && (
              <select
                aria-label="조직"
                value={chosenOrganizationId}
                onChange={(event) => setOrganizationId(event.target.value)}
                className="mb-2 w-full rounded-[11px] border-2 border-[#26272f] bg-[#12131a] px-3.5 py-2.5 text-sm text-[#f2f1f6] outline-none focus:border-[#9b82ff]"
              >
                <option value="">개인 작업 공간 (기본)</option>
                {teamOrganizations.map((organization) => (
                  <option key={organization.organizationId} value={organization.organizationId}>
                    {organization.organizationName}
                  </option>
                ))}
              </select>
            )}

            <button
              type="submit"
              disabled={createProjectMutation.isPending || !currentUser?.userId}
              className="mt-auto flex h-11 w-full items-center justify-center rounded-[11px] bg-[#7c5cff] text-sm font-bold text-white transition hover:bg-[#6a49f0] active:scale-[0.98] disabled:opacity-60"
            >
              {createProjectMutation.isPending ? '만드는 중...' : '만들기'}
            </button>

            {createProjectMutation.isError && (
              <p className="mt-2 text-[12.5px] text-[#f09595]">
                {createProjectMutation.error instanceof Error
                  ? createProjectMutation.error.message
                  : '프로젝트 생성에 실패했습니다.'}
              </p>
            )}
          </form>

          <form
            onSubmit={(event) => void handleCreateWorkflow(event)}
            className="flex h-full min-w-0 flex-col rounded-2xl bg-[#1a1b24] p-5 ring-1 ring-transparent transition focus-within:bg-[#202130] focus-within:ring-[#7c5cff]"
          >
            <label
              className="mb-3.5 flex min-w-0 items-baseline gap-1.5 text-[13px] font-semibold"
              title={selectedProjectId}
            >
              <span className="flex-shrink-0">새 워크플로우</span>
              <span className="truncate text-xs font-normal text-[#5f5d6b]">
                {selectedProject
                  ? `· ${selectedProject.projectName}`
                  : '· 프로젝트를 먼저 선택해주세요'}
              </span>
            </label>

            <input
              value={workflowName}
              onChange={(event) => setWorkflowName(event.target.value)}
              placeholder="워크플로우 이름"
              required
              className="mb-2 w-full rounded-[11px] border-2 border-[#26272f] bg-[#12131a] px-3.5 py-2.5 text-sm text-[#f2f1f6] outline-none placeholder:text-[#5f5d6b] focus:border-[#9b82ff] focus:shadow-[0_0_0_3px_rgba(124,92,255,0.28)]"
            />

            <textarea
              value={workflowDesc}
              onChange={(event) => setWorkflowDesc(event.target.value)}
              placeholder="설명 (선택)"
              rows={2}
              className="mb-2 w-full resize-none rounded-[11px] border-2 border-[#26272f] bg-[#12131a] px-3.5 py-2.5 text-sm text-[#f2f1f6] outline-none placeholder:text-[#5f5d6b] focus:border-[#9b82ff] focus:shadow-[0_0_0_3px_rgba(124,92,255,0.28)]"
            />

            <button
              type="submit"
              disabled={!selectedProjectId || createWorkflowMutation.isPending}
              className="mt-auto flex h-11 w-full items-center justify-center rounded-[11px] bg-[#7c5cff] text-sm font-bold text-white transition hover:bg-[#6a49f0] active:scale-[0.98] disabled:opacity-60"
            >
              {createWorkflowMutation.isPending ? '만드는 중...' : '만들기'}
            </button>

            {createWorkflowMutation.isError && (
              <p className="mt-2 text-[12.5px] text-[#f09595]">
                {createWorkflowMutation.error instanceof Error
                  ? createWorkflowMutation.error.message
                  : '워크플로우 생성에 실패했습니다.'}
              </p>
            )}
          </form>

          <StartFromSample ownerId={currentUser?.userId} />
          <StartFromDescription ownerId={currentUser?.userId} />
        </div>

        <div className="mb-3.5 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-[#9694a3]">프로젝트</h2>
        </div>

        {isProjectsLoading && (
          <p className="mb-4 text-sm text-[#9694a3]">불러오는 중...</p>
        )}

        {isProjectsError && (
          <p className="mb-4 text-sm text-[#f09595]">
            {projectsError instanceof Error
              ? projectsError.message
              : '프로젝트를 불러오지 못했습니다.'}
          </p>
        )}

        {!isProjectsLoading && !isProjectsError && projects.length === 0 && (
          <p className="mb-4 text-sm text-[#5f5d6b]">
            아직 프로젝트가 없습니다. 위에서 새로 만들어보세요.
          </p>
        )}

        <div className="mb-10 flex gap-3 overflow-x-auto pb-1 [scrollbar-width:thin]">
          {projects.map((project) => {
            const isSelected = project.projectId === selectedProjectId

            return (
              <button
                key={project.projectId}
                type="button"
                title={project.projectId}
                onClick={() => setSelectedProjectId(project.projectId)}
                className={`flex-shrink-0 basis-[220px] rounded-2xl p-5 text-left transition hover:-translate-y-0.5 ${
                  isSelected
                    ? 'border-[1.5px] border-[#7c5cff] bg-[rgba(124,92,255,0.1)]'
                    : 'border-[1.5px] border-transparent bg-[#1a1b24] hover:bg-[#1f2029]'
                }`}
              >
                <div className="mb-1 truncate text-[14.5px] font-bold">
                  {project.projectName}
                </div>

                {/* No internal id on the card: a first-time user has no use for a UUID (it stays in the card's tooltip). */}
                <div className="mb-3.5" />

                <div className="flex items-center gap-1.5">
                  <span
                    className={`h-1.5 w-1.5 rounded-full ${
                      project.projectStatus?.toLowerCase() === 'active'
                        ? 'bg-[#4ade80]'
                        : 'bg-[#5f5d6b]'
                    }`}
                  />
                  <span className="text-xs font-medium text-[#9694a3]">
                    {project.projectStatus}
                  </span>
                </div>
              </button>
            )
          })}
        </div>

        <div className="mb-3.5 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-[#9694a3]">워크플로우</h2>
        </div>

        {!selectedProjectId && (
          <p className="text-sm text-[#5f5d6b]">
            먼저 프로젝트를 선택해주세요.
          </p>
        )}

        {selectedProjectId && isWorkflowsLoading && (
          <p className="text-sm text-[#9694a3]">불러오는 중...</p>
        )}

        {selectedProjectId && isWorkflowsError && (
          <p className="text-sm text-[#f09595]">
            {workflowsError instanceof Error
              ? workflowsError.message
              : '워크플로우를 불러오지 못했습니다.'}
          </p>
        )}

        {selectedProjectId &&
          !isWorkflowsLoading &&
          !isWorkflowsError &&
          workflows.length === 0 && (
            <p className="text-sm text-[#5f5d6b]">
              이 프로젝트에는 아직 워크플로우가 없습니다.
            </p>
          )}

        <div className="flex flex-col gap-1.5">
          {workflows.map((workflow) => (
            <Link
              key={workflow.workflowId}
              to={`/projects/${workflow.projectId}/workflows/${workflow.workflowId}`}
              onMouseEnter={() => void preloadWorkspaceExperience()}
              onFocus={() => void preloadWorkspaceExperience()}
              className="group flex items-center justify-between rounded-[14px] bg-[#1a1b24] py-3.5 pl-[18px] pr-[18px] no-underline transition hover:bg-[#1f2029] hover:pl-[22px]"
            >
              <div className="flex min-w-0 flex-1 items-center gap-3">
                <div className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-[10px] bg-[rgba(124,92,255,0.1)] text-[15px] text-[#9b82ff]">
                  ⇢
                </div>

                <div className="min-w-0">
                  <div className="truncate text-[14.5px] font-semibold text-[#f2f1f6]">
                    {workflow.workflowName}
                  </div>
                  <div className="mt-0.5 truncate text-[12.5px] text-[#5f5d6b]">
                    {workflow.workflowStatus} · {workflow.workflowType}
                  </div>
                </div>
              </div>

              <span className="ml-3 flex-shrink-0 text-[15px] text-[#5f5d6b] transition group-hover:translate-x-1 group-hover:text-[#9b82ff]">
                →
              </span>
            </Link>
          ))}
        </div>
      </div>
    </div>
  )
}

export function HomeRoute() {
  const [isLoggedIn, setIsLoggedIn] = useState(
    () => !!tokenStorage.getAccess()
  )
  const [authMode, setAuthMode] = useState<'login' | 'signup'>('login')
  const [authUserId, setAuthUserId] = useState('')
  const [authUserName, setAuthUserName] = useState('')
  const [authNickname, setAuthNickname] = useState('')
  const [authEmail, setAuthEmail] = useState('')
  const [authEmailCode, setAuthEmailCode] = useState('')
  const [authTel, setAuthTel] = useState('')
  const [authBirth, setAuthBirth] = useState('')
  const [authPassword, setAuthPassword] = useState('')
  const [isEmailVerified, setIsEmailVerified] = useState(false)
  const [authError, setAuthError] = useState<string | null>(null)
  const [authInfo, setAuthInfo] = useState<string | null>(null)
  const [showDormantHelp, setShowDormantHelp] = useState(false)

  const loginMutation = useLoginMutation()
  const signupMutation = useSignupMutation()
  const logoutMutation = useLogoutMutation()
  const requestDormantMutation = useRequestDormantMutation()
  const requestDormantReactivationMutation =
    useRequestDormantReactivationMutation()
  const sendEmailCodeMutation = useSendEmailCodeMutation()
  const verifyEmailCodeMutation = useVerifyEmailCodeMutation()

  useEffect(
    () =>
      subscribeAuthChange(() => {
        setIsLoggedIn(!!tokenStorage.getAccess())
      }),
    []
  )

  useEffect(() => {
    if (!tokenStorage.getAccess() && tokenStorage.hasRefreshSessionHint()) {
      void refreshAccessToken()
    }
  }, [])

  useEffect(() => {
    if (authMode !== 'signup') {
      return
    }

    setIsEmailVerified(false)
    setAuthEmailCode('')
  }, [authEmail, authMode])

  function resetAuthMessages() {
    setAuthError(null)
    setAuthInfo(null)
    setShowDormantHelp(false)
  }

  function changeAuthMode(mode: 'login' | 'signup') {
    setAuthMode(mode)
    resetAuthMessages()
    setIsEmailVerified(false)
    setAuthEmailCode('')
  }

  async function handleAuthSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    resetAuthMessages()

    try {
      if (authMode === 'login') {
        await loginMutation.mutateAsync({
          userIdOrEmail: authUserId.trim(),
          password: authPassword,
        })
      } else {
        if (!isEmailVerified) {
          throw new Error('회원가입 전에 이메일 인증을 완료해주세요.')
        }

        await signupMutation.mutateAsync({
          userId: authUserId.trim(),
          userName: authUserName.trim(),
          userNickname: authNickname.trim(),
          userEmail: authEmail.trim(),
          userTel: authTel.trim(),
          userBirth: authBirth || null,
          password: authPassword,
        })

        await loginMutation.mutateAsync({
          userIdOrEmail: authUserId.trim(),
          password: authPassword,
        })
      }

      setAuthUserId('')
      setAuthUserName('')
      setAuthNickname('')
      setAuthEmail('')
      setAuthEmailCode('')
      setAuthTel('')
      setAuthBirth('')
      setAuthPassword('')
      setIsEmailVerified(false)
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : error && typeof error === 'object' && 'message' in error
            ? String((error as { message: unknown }).message)
            : '인증 처리에 실패했습니다.'

      setAuthError(message)
      setShowDormantHelp(message.toLowerCase().includes('dormant'))
    }
  }

  function handleLogout() {
    void logoutMutation.mutateAsync()
  }

  async function handleSendEmailCode() {
    setAuthError(null)
    setAuthInfo(null)

    try {
      await sendEmailCodeMutation.mutateAsync(authEmail.trim())
      setAuthInfo('이메일 인증 코드를 전송했습니다.')
    } catch (error) {
      setAuthError(
        error instanceof Error
          ? error.message
          : '이메일 인증 코드 전송에 실패했습니다.'
      )
    }
  }

  async function handleVerifyEmailCode() {
    setAuthError(null)
    setAuthInfo(null)

    try {
      await verifyEmailCodeMutation.mutateAsync({
        userEmail: authEmail.trim(),
        code: authEmailCode.trim(),
      })

      setIsEmailVerified(true)
      setAuthInfo('이메일 인증이 완료되었습니다.')
    } catch (error) {
      setIsEmailVerified(false)
      setAuthError(
        error instanceof Error
          ? error.message
          : '이메일 인증에 실패했습니다.'
      )
    }
  }

  async function handleDormantReactivationRequest() {
    setAuthError(null)
    setAuthInfo(null)

    try {
      await requestDormantReactivationMutation.mutateAsync(authUserId.trim())
      setAuthInfo(
        '휴면 계정이라면 등록된 이메일로 재활성화 안내를 전송했습니다.'
      )
    } catch (error) {
      setAuthError(
        error instanceof Error
          ? error.message
          : '휴면 계정 재활성화 요청에 실패했습니다.'
      )
    }
  }

  async function handleRequestDormant() {
    setAuthError(null)
    setAuthInfo(null)

    const confirmed = window.confirm(
      '계정을 휴면 상태로 전환하고 지금 로그아웃하시겠습니까?'
    )

    if (!confirmed) {
      return
    }

    try {
      await requestDormantMutation.mutateAsync()
      tokenStorage.clear()
      setIsLoggedIn(false)
      setAuthMode('login')
      setAuthPassword('')
      setAuthInfo(
        '계정이 휴면 상태로 전환되었습니다. 재활성화 이메일을 통해 다시 로그인할 수 있습니다.'
      )
    } catch (error) {
      setAuthError(
        error instanceof Error
          ? error.message
          : '휴면 상태 전환에 실패했습니다.'
      )
    }
  }

  function handleRipple(event: MouseEvent<HTMLButtonElement>) {
    const button = event.currentTarget
    const rect = button.getBoundingClientRect()
    const size = Math.max(rect.width, rect.height)
    const ripple = document.createElement('span')

    ripple.className =
      'pointer-events-none absolute scale-0 rounded-full bg-[#0d0e13]/35 [animation:flowmat-ripple_0.55s_ease-out]'
    ripple.style.width = `${size}px`
    ripple.style.height = `${size}px`
    ripple.style.left = `${event.clientX - rect.left - size / 2}px`
    ripple.style.top = `${event.clientY - rect.top - size / 2}px`

    button.appendChild(ripple)
    window.setTimeout(() => ripple.remove(), 550)
  }

  const isAuthPending =
    loginMutation.isPending ||
    signupMutation.isPending ||
    requestDormantReactivationMutation.isPending ||
    sendEmailCodeMutation.isPending ||
    verifyEmailCodeMutation.isPending

  if (!isLoggedIn) {
    return (
      <div className="relative flex min-h-[100svh] items-center justify-center overflow-hidden bg-[#0d0e13] py-10 text-[#e8e7ee]">
        <FlowCanvasBackground />

        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              'radial-gradient(circle at 50% 45%, transparent 0%, transparent 30%, #0d0e13 78%)',
          }}
        />

        <div className="relative z-10 w-full max-w-[420px] px-5">
          <div className="mb-5 flex items-center gap-2 font-mono text-[11px] tracking-wide text-[#8b899a]">
            <span className="h-1.5 w-1.5 rounded-full bg-[#3fd4c8] shadow-[0_0_8px_#3fd4c8] [animation:flowmat-pulse-dot_1.8s_ease-in-out_infinite]" />
            SYSTEM ONLINE
          </div>

          <h1 className="mb-1.5 text-[26px] font-bold tracking-tight">
            Flow<span className="text-[#a06bff]">Mat</span>
          </h1>

          <p className="mb-6 text-[13px] leading-relaxed text-[#8b899a]">
            범용 워크플로우를 한눈에 설계하고 추적하세요.
          </p>

          <form
            onSubmit={(event) => void handleAuthSubmit(event)}
            className="relative rounded-2xl border border-[#2a2b35] bg-gradient-to-b from-[#15161d] to-[#191a22] p-6 pb-5 shadow-[0_20px_60px_-20px_rgba(160,107,255,0.15)]"
          >
            <div className="relative mb-5 flex rounded-[10px] bg-white/[0.03] p-[3px]">
              <div
                className={`absolute top-[3px] h-[calc(100%-6px)] w-[calc(50%-3px)] rounded-lg bg-[#6d4bb8] transition-transform duration-300 ${
                  authMode === 'signup'
                    ? 'translate-x-full'
                    : 'translate-x-0'
                }`}
              />

              <button
                type="button"
                onClick={() => changeAuthMode('login')}
                className={`relative z-10 flex-1 rounded-lg py-2 text-[13px] font-medium transition-colors ${
                  authMode === 'login'
                    ? 'text-[#e8e7ee]'
                    : 'text-[#8b899a]'
                }`}
              >
                Login
              </button>

              <button
                type="button"
                onClick={() => changeAuthMode('signup')}
                className={`relative z-10 flex-1 rounded-lg py-2 text-[13px] font-medium transition-colors ${
                  authMode === 'signup'
                    ? 'text-[#e8e7ee]'
                    : 'text-[#8b899a]'
                }`}
              >
                Sign Up
              </button>
            </div>

            {authMode === 'signup' && (
              <>
                <div className="mb-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <div>
                    <label className="mb-1.5 block text-[11px] font-medium text-[#8b899a]">
                      Name
                    </label>
                    <input
                      value={authUserName}
                      onChange={(event) =>
                        setAuthUserName(event.target.value)
                      }
                      placeholder="홍길동"
                      required
                      className="w-full rounded-[9px] border border-[#2a2b35] bg-white/[0.02] px-3 py-2.5 text-sm text-[#e8e7ee] outline-none transition placeholder:text-[#5a5866] focus:border-[#a06bff] focus:bg-[#a06bff]/[0.03] focus:shadow-[0_0_0_3px_rgba(160,107,255,0.16)]"
                    />
                  </div>

                  <div>
                    <label className="mb-1.5 block text-[11px] font-medium text-[#8b899a]">
                      Nickname
                    </label>
                    <input
                      value={authNickname}
                      onChange={(event) =>
                        setAuthNickname(event.target.value)
                      }
                      placeholder="닉네임"
                      required
                      className="w-full rounded-[9px] border border-[#2a2b35] bg-white/[0.02] px-3 py-2.5 text-sm text-[#e8e7ee] outline-none transition placeholder:text-[#5a5866] focus:border-[#a06bff] focus:bg-[#a06bff]/[0.03] focus:shadow-[0_0_0_3px_rgba(160,107,255,0.16)]"
                    />
                  </div>
                </div>

                <div className="mb-3">
                  <label className="mb-1.5 block text-[11px] font-medium text-[#8b899a]">
                    Email
                  </label>

                  <div className="flex gap-2">
                    <input
                      type="email"
                      value={authEmail}
                      onChange={(event) => setAuthEmail(event.target.value)}
                      placeholder="you@company.com"
                      required
                      className="min-w-0 flex-1 rounded-[9px] border border-[#2a2b35] bg-white/[0.02] px-3 py-2.5 text-sm text-[#e8e7ee] outline-none transition placeholder:text-[#5a5866] focus:border-[#a06bff] focus:bg-[#a06bff]/[0.03] focus:shadow-[0_0_0_3px_rgba(160,107,255,0.16)]"
                    />

                    <button
                      type="button"
                      onClick={() => void handleSendEmailCode()}
                      disabled={
                        !authEmail.trim() ||
                        sendEmailCodeMutation.isPending ||
                        isEmailVerified
                      }
                      className="flex-shrink-0 rounded-[9px] border border-[#3fd4c8]/40 px-3 text-xs font-medium text-[#3fd4c8] transition hover:bg-[#3fd4c8]/10 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {sendEmailCodeMutation.isPending
                        ? 'Sending...'
                        : isEmailVerified
                          ? 'Verified'
                          : 'Send Code'}
                    </button>
                  </div>
                </div>

                <div className="mb-3">
                  <label className="mb-1.5 block text-[11px] font-medium text-[#8b899a]">
                    Verification Code
                  </label>

                  <div className="flex gap-2">
                    <input
                      value={authEmailCode}
                      onChange={(event) =>
                        setAuthEmailCode(event.target.value)
                      }
                      placeholder="인증 코드"
                      required
                      disabled={isEmailVerified}
                      className="min-w-0 flex-1 rounded-[9px] border border-[#2a2b35] bg-white/[0.02] px-3 py-2.5 text-sm text-[#e8e7ee] outline-none transition placeholder:text-[#5a5866] focus:border-[#a06bff] focus:bg-[#a06bff]/[0.03] focus:shadow-[0_0_0_3px_rgba(160,107,255,0.16)] disabled:opacity-60"
                    />

                    <button
                      type="button"
                      onClick={() => void handleVerifyEmailCode()}
                      disabled={
                        !authEmail.trim() ||
                        !authEmailCode.trim() ||
                        verifyEmailCodeMutation.isPending ||
                        isEmailVerified
                      }
                      className="flex-shrink-0 rounded-[9px] border border-[#a06bff]/50 px-3 text-xs font-medium text-[#b481ff] transition hover:bg-[#a06bff]/10 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {verifyEmailCodeMutation.isPending
                        ? 'Verifying...'
                        : isEmailVerified
                          ? 'Verified'
                          : 'Verify'}
                    </button>
                  </div>
                </div>

                <div className="mb-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <div>
                    <label className="mb-1.5 block text-[11px] font-medium text-[#8b899a]">
                      Phone
                    </label>
                    <input
                      type="tel"
                      value={authTel}
                      onChange={(event) => setAuthTel(event.target.value)}
                      placeholder="010-0000-0000"
                      required
                      className="w-full rounded-[9px] border border-[#2a2b35] bg-white/[0.02] px-3 py-2.5 text-sm text-[#e8e7ee] outline-none transition placeholder:text-[#5a5866] focus:border-[#a06bff] focus:bg-[#a06bff]/[0.03] focus:shadow-[0_0_0_3px_rgba(160,107,255,0.16)]"
                    />
                  </div>

                  <div>
                    <label className="mb-1.5 block text-[11px] font-medium text-[#8b899a]">
                      Birth (optional)
                    </label>
                    <input
                      type="date"
                      value={authBirth}
                      onChange={(event) => setAuthBirth(event.target.value)}
                      className="w-full rounded-[9px] border border-[#2a2b35] bg-white/[0.02] px-3 py-2.5 text-sm text-[#e8e7ee] outline-none transition focus:border-[#a06bff] focus:bg-[#a06bff]/[0.03] focus:shadow-[0_0_0_3px_rgba(160,107,255,0.16)]"
                    />
                  </div>
                </div>
              </>
            )}

            <div className="mb-3">
              <label className="mb-1.5 block text-[11px] font-medium text-[#8b899a]">
                {authMode === 'login' ? 'User ID or email' : 'User ID'}
              </label>

              <input
                value={authUserId}
                onChange={(event) => setAuthUserId(event.target.value)}
                placeholder={
                  authMode === 'login'
                    ? 'demo-owner'
                    : 'Choose a user ID'
                }
                required
                className="w-full rounded-[9px] border border-[#2a2b35] bg-white/[0.02] px-3 py-2.5 text-sm text-[#e8e7ee] outline-none transition placeholder:text-[#5a5866] focus:border-[#a06bff] focus:bg-[#a06bff]/[0.03] focus:shadow-[0_0_0_3px_rgba(160,107,255,0.16)]"
              />
            </div>

            <div className="mb-1">
              <label className="mb-1.5 block text-[11px] font-medium text-[#8b899a]">
                Password
              </label>

              <input
                type="password"
                value={authPassword}
                onChange={(event) => setAuthPassword(event.target.value)}
                placeholder="••••••••"
                required
                minLength={authMode === 'signup' ? 8 : undefined}
                aria-describedby={authMode === 'signup' ? 'signup-password-rule' : undefined}
                className="w-full rounded-[9px] border border-[#2a2b35] bg-white/[0.02] px-3 py-2.5 text-sm text-[#e8e7ee] outline-none transition placeholder:text-[#5a5866] focus:border-[#a06bff] focus:bg-[#a06bff]/[0.03] focus:shadow-[0_0_0_3px_rgba(160,107,255,0.16)]"
              />
              {authMode === 'signup' && (
                <p id="signup-password-rule" className="mt-1 text-[11px] text-[#8b899a]">
                  8자 이상, 아이디·이메일과 다르게
                </p>
              )}
            </div>

            {authError && (
              <p className="mt-2 text-[13px] leading-relaxed text-[#f09595]">
                {authError}
              </p>
            )}

            {authInfo && (
              <p className="mt-2 text-[13px] leading-relaxed text-[#3fd4c8]">
                {authInfo}
              </p>
            )}

            {authMode === 'login' && showDormantHelp && (
              <button
                type="button"
                onClick={() => void handleDormantReactivationRequest()}
                disabled={
                  !authUserId.trim() ||
                  requestDormantReactivationMutation.isPending
                }
                className="mt-3 w-full rounded-[9px] border border-[#3fd4c8]/40 py-2.5 text-xs font-semibold text-[#3fd4c8] transition hover:bg-[#3fd4c8]/10 disabled:opacity-50"
              >
                {requestDormantReactivationMutation.isPending
                  ? 'Sending Reactivation Email...'
                  : 'Send Reactivation Email'}
              </button>
            )}

            <button
              type="submit"
              onClick={handleRipple}
              disabled={
                isAuthPending ||
                (authMode === 'signup' && !isEmailVerified)
              }
              className="relative mt-3 w-full overflow-hidden rounded-[9px] bg-[#a06bff] py-2.5 text-sm font-semibold text-[#0d0e13] transition hover:bg-[#b481ff] active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-60"
            >
              {loginMutation.isPending || signupMutation.isPending
                ? 'Working...'
                : authMode === 'login'
                  ? 'Log in'
                  : 'Create account'}
            </button>

            <div className="mt-4 text-center text-xs text-[#8b899a]">
              {authMode === 'login'
                ? '계정이 없으신가요? '
                : '이미 계정이 있으신가요? '}

              <button
                type="button"
                onClick={() =>
                  changeAuthMode(
                    authMode === 'login' ? 'signup' : 'login'
                  )
                }
                className="text-xs text-[#3fd4c8]"
              >
                {authMode === 'login' ? 'Sign Up' : 'Login'}
              </button>
            </div>
          </form>

          {showsDemoLogin(import.meta.env) && (
            <p className="mt-3.5 text-center font-mono text-[11px] text-[#5a5866]">
              demo-owner / demo1234
            </p>
          )}
        </div>
      </div>
    )
  }

  return (
    <Dashboard
      onLogout={handleLogout}
      onRequestDormant={() => void handleRequestDormant()}
      isLoggingOut={logoutMutation.isPending}
    />
  )
}
