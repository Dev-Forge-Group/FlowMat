import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useProjectMembersQuery } from '../../../entities/project/api/useProjectMembersQuery'
import { useProjectInvitesQuery } from '../../../entities/project/api/useProjectInvitesQuery'
import { useCreateInviteMutation } from '../../../entities/project/api/useCreateInviteMutation'
import { useCancelInviteMutation } from '../../../entities/project/api/useCancelInviteMutation'
import { useUpdateMemberRoleMutation } from '../../../entities/project/api/useUpdateMemberRoleMutation'
import { useRemoveMemberMutation } from '../../../entities/project/api/useRemoveMemberMutation'
import type { ProjectMemberDto, ProjectInviteDto } from '../../../shared/types/api'

import { ProjectTimeZonePanel } from './ProjectTimeZonePanel'
import { NotificationSubscriptionsPanel } from './NotificationSubscriptionsPanel'
import { WorkerCalendarsPanel } from './WorkerCalendarsPanel'
import { HTTPProfilesPanel } from './HTTPProfilesPanel'

const ROLES = ['viewer', 'editor'] as const

const sectionStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 16,
  padding: 24,
  display: 'grid',
  gap: 16,
}

const tableStyle: React.CSSProperties = {
  width: '100%',
  borderCollapse: 'collapse',
  fontSize: 14,
}

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '6px 10px',
  borderBottom: '1px solid var(--border)',
  fontWeight: 600,
  fontSize: 12,
  opacity: 0.7,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
}

const tdStyle: React.CSSProperties = {
  padding: '8px 10px',
  borderBottom: '1px solid var(--border)',
  verticalAlign: 'middle',
}

function RoleBadge({ role }: { role: string }) {
  const colors: Record<string, string> = {
    owner: '#8b5cf6',
    editor: '#0ea5e9',
    viewer: '#64748b',
  }
  return (
    <span
      style={{
        display: 'inline-block',
        padding: '2px 8px',
        borderRadius: 999,
        fontSize: 12,
        fontWeight: 600,
        background: `${colors[role] ?? '#64748b'}22`,
        color: colors[role] ?? '#64748b',
      }}
    >
      {role}
    </span>
  )
}

function MembersPanel({ projectId }: { projectId: string }) {
  const { data: members = [], isLoading } = useProjectMembersQuery(projectId)
  const updateRoleMutation = useUpdateMemberRoleMutation(projectId)
  const removeMutation = useRemoveMemberMutation(projectId)
  const [roleSelections, setRoleSelections] = useState<Record<string, string>>({})
  const [actionError, setActionError] = useState<string | null>(null)

  async function handleRoleChange(member: ProjectMemberDto) {
    const newRole = roleSelections[member.projectMemberId] ?? member.projectRole
    setActionError(null)
    try {
      await updateRoleMutation.mutateAsync({ projectMemberId: member.projectMemberId, projectRole: newRole })
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Failed to update role.')
    }
  }

  async function handleRemove(member: ProjectMemberDto) {
    if (!confirm(`Remove ${member.userId} from this project?`)) return
    setActionError(null)
    try {
      await removeMutation.mutateAsync(member.projectMemberId)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Failed to remove member.')
    }
  }

  return (
    <div style={sectionStyle}>
      <h2 style={{ margin: 0 }}>Members</h2>
      {actionError && <p style={{ color: '#dc2626', fontSize: 13, margin: 0 }}>{actionError}</p>}
      {isLoading && <p style={{ opacity: 0.5 }}>Loading members…</p>}
      {!isLoading && members.length === 0 && <p style={{ opacity: 0.5 }}>No members found.</p>}
      {members.length > 0 && (
        <table style={tableStyle}>
          <thead>
            <tr>
              <th style={thStyle}>User</th>
              <th style={thStyle}>Role</th>
              <th style={thStyle}>Joined</th>
              <th style={thStyle}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {members.map((m) => (
              <tr key={m.projectMemberId}>
                <td style={tdStyle}>{m.userId}</td>
                <td style={tdStyle}>
                  {m.projectRole === 'owner' ? (
                    <RoleBadge role="owner" />
                  ) : (
                    <select
                      value={roleSelections[m.projectMemberId] ?? m.projectRole}
                      onChange={(e) =>
                        setRoleSelections((prev) => ({ ...prev, [m.projectMemberId]: e.target.value }))
                      }
                      style={{ fontSize: 13, borderRadius: 6 }}
                    >
                      {ROLES.map((r) => (
                        <option key={r} value={r}>{r}</option>
                      ))}
                    </select>
                  )}
                </td>
                <td style={tdStyle}>
                  {m.joinedAt ? new Date(m.joinedAt).toLocaleDateString() : '—'}
                </td>
                <td style={tdStyle}>
                  {m.projectRole !== 'owner' && (
                    <div style={{ display: 'flex', gap: 8 }}>
                      <button
                        type="button"
                        onClick={() => void handleRoleChange(m)}
                        disabled={updateRoleMutation.isPending}
                        style={{ fontSize: 12, padding: '3px 10px', borderRadius: 6, cursor: 'pointer' }}
                      >
                        Save
                      </button>
                      <button
                        type="button"
                        onClick={() => void handleRemove(m)}
                        disabled={removeMutation.isPending}
                        style={{ fontSize: 12, padding: '3px 10px', borderRadius: 6, cursor: 'pointer', color: '#dc2626', borderColor: '#dc2626' }}
                      >
                        Remove
                      </button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function InvitesPanel({ projectId }: { projectId: string }) {
  const { data: invites = [], isLoading } = useProjectInvitesQuery(projectId)
  const createMutation = useCreateInviteMutation(projectId)
  const cancelMutation = useCancelInviteMutation(projectId)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<'viewer' | 'editor'>('viewer')
  const [formError, setFormError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  async function handleInvite(e: FormEvent) {
    e.preventDefault()
    setFormError(null)
    try {
      await createMutation.mutateAsync({ projectId, invitedEmail: email, projectRole: role })
      setEmail('')
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Failed to send invitation.')
    }
  }

  async function handleCancel(invite: ProjectInviteDto) {
    setActionError(null)
    try {
      await cancelMutation.mutateAsync(invite.inviteId)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Failed to cancel invitation.')
    }
  }

  const pending = invites.filter((i) => i.inviteStatus === 'pending')

  return (
    <div style={sectionStyle}>
      <h2 style={{ margin: 0 }}>Invitations</h2>

      <form
        onSubmit={(e) => void handleInvite(e)}
        style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}
      >
        <div style={{ display: 'grid', gap: 4 }}>
          <label style={{ fontSize: 12, opacity: 0.7 }}>Email</label>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="invitee@example.com"
            required
            style={{ width: 220 }}
          />
        </div>
        <div style={{ display: 'grid', gap: 4 }}>
          <label style={{ fontSize: 12, opacity: 0.7 }}>Role</label>
          <select value={role} onChange={(e) => setRole(e.target.value as 'viewer' | 'editor')} style={{ borderRadius: 6 }}>
            {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        </div>
        <button
          type="submit"
          disabled={createMutation.isPending}
          style={{ padding: '6px 16px', borderRadius: 8, cursor: 'pointer', height: 36 }}
        >
          {createMutation.isPending ? 'Sending…' : 'Send Invite'}
        </button>
      </form>

      {formError && <p style={{ color: '#dc2626', fontSize: 13, margin: 0 }}>{formError}</p>}
      {actionError && <p style={{ color: '#dc2626', fontSize: 13, margin: 0 }}>{actionError}</p>}

      {isLoading && <p style={{ opacity: 0.5 }}>Loading invitations…</p>}
      {!isLoading && pending.length === 0 && <p style={{ opacity: 0.5 }}>No pending invitations.</p>}
      {pending.length > 0 && (
        <table style={tableStyle}>
          <thead>
            <tr>
              <th style={thStyle}>Email</th>
              <th style={thStyle}>Role</th>
              <th style={thStyle}>Expires</th>
              <th style={thStyle}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {pending.map((inv) => (
              <tr key={inv.inviteId}>
                <td style={tdStyle}>{inv.invitedEmail}</td>
                <td style={tdStyle}><RoleBadge role={inv.projectRole} /></td>
                <td style={tdStyle}>
                  {inv.expiredAt ? new Date(inv.expiredAt).toLocaleDateString() : '—'}
                </td>
                <td style={tdStyle}>
                  <button
                    type="button"
                    onClick={() => void handleCancel(inv)}
                    disabled={cancelMutation.isPending}
                    style={{ fontSize: 12, padding: '3px 10px', borderRadius: 6, cursor: 'pointer', color: '#dc2626', borderColor: '#dc2626' }}
                  >
                    Cancel
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

export function ProjectSettingsRoute() {
  const { projectId } = useParams<{ projectId: string }>()

  if (!projectId) return null

  return (
    <div
      style={{
        minHeight: '100svh',
        padding: '32px',
        maxWidth: 900,
        margin: '0 auto',
        display: 'grid',
        gap: 24,
        boxSizing: 'border-box',
      }}
    >
      <header style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        <Link to="/" style={{ fontSize: 13, color: 'var(--accent)', textDecoration: 'none' }}>← Home</Link>
        <h1 style={{ margin: 0 }}>Project Settings</h1>
        <span style={{ fontSize: 12, opacity: 0.5 }}>{projectId}</span>
      </header>

      <ProjectTimeZonePanel key={projectId} projectId={projectId} />
      <HTTPProfilesPanel key={`http-${projectId}`} projectId={projectId} />
      <NotificationSubscriptionsPanel key={`notifications-${projectId}`} projectId={projectId} />
      <WorkerCalendarsPanel key={`workers-${projectId}`} projectId={projectId} />
      <MembersPanel projectId={projectId} />
      <InvitesPanel projectId={projectId} />
    </div>
  )
}
