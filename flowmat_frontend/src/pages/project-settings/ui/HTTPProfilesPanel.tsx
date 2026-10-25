import { useState, type FormEvent } from 'react'
import { useIsProjectOwner } from '../../../entities/project/api/useIsProjectOwner'
import { useHttpProfiles, useHttpProfileMutations, useHttpProfileVersions } from '../../../entities/connector/api/useHttpConnectors'
import { emptyProfileForm, profileForm, profileInput, type ProfileForm } from '../../../entities/connector/model/connectorForms'
import { compareHttpProfileVersions, defaultHttpProfileComparison } from '../../../entities/connector/model/httpProfileHistory'
import type { HttpOperation, HttpProfileVersion } from '../../../entities/connector/model/types'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { HTTPProfileReadinessCheck } from './HTTPProfileReadinessCheck'

const field = { display: 'grid', gap: 4 } as const

export function HTTPProfilesPanel({ projectId }: { projectId: string }) {
  const owner = useIsProjectOwner(projectId)
  const query = useHttpProfiles(projectId)
  const { save, status, remove } = useHttpProfileMutations(projectId)
  const [form, setForm] = useState<ProfileForm | null>(null)
  const [historyId, setHistoryId] = useState('')
  const history = useHttpProfileVersions(projectId, historyId)
  const [problem, setProblem] = useState<string | null>(null)
  const profiles = Array.isArray(query.data) ? query.data : []
  const pending = save.isPending || status.isPending || remove.isPending
  const set = (patch: Partial<ProfileForm>) => setForm((current) => current ? { ...current, ...patch } : null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!form || pending) return
    setProblem(null)
    try {
      await save.mutateAsync({ profileId: form.profileId, input: profileInput(form) })
      setForm(null)
    } catch (error) { setProblem(errorMessage(error, 'Could not save the HTTP profile.')) }
  }

  return <section aria-label="HTTP profiles" style={{ border: '1px solid var(--border)', borderRadius: 16, padding: 24,
    display: 'grid', gap: 12, minWidth: 0 }}>
    <h2 style={{ margin: 0 }}>HTTP profiles</h2>
    <p style={{ margin: 0 }}>Choose approved destinations and operations for workflow steps. Profile edits create a new revision;
      published workflows keep the revision they selected.</p>
    {owner ? <button type="button" disabled={pending || Boolean(form)} onClick={() => {
      setForm(emptyProfileForm()); setProblem(null)
    }}>Add HTTP profile</button> : <p>Only the project owner can create or change profiles.</p>}
    {query.isPending && <p>Loading HTTP profiles...</p>}
    {query.isError && <p role="alert">{errorMessage(query.error, 'Could not load HTTP profiles.')}</p>}
    {!query.isPending && !query.isError && profiles.length === 0 && <p>No HTTP profiles yet.</p>}
    {profiles.map((profile) => <article key={profile.profileId} style={{ borderTop: '1px solid var(--border)', paddingTop: 12,
      display: 'grid', gap: 6, minWidth: 0 }}>
      <strong>{profile.name}</strong>
      <span>{profile.enabled ? 'Enabled' : 'Disabled'} · revision {profile.currentRevision}</span>
      <span style={{ overflowWrap: 'anywhere' }}>{profile.baseOrigin}</span>
      <span>Credential alias: {profile.credentialAlias ?? 'none'}</span>
      <ul style={{ margin: 0, paddingLeft: 20 }}>{profile.operations.map((operation) => <li key={operation.operationId}
        style={{ overflowWrap: 'anywhere' }}>{operation.operationId}: {operation.method} {operation.relativePath}
        {' · '}accepts {operation.successStatuses.join(', ')}</li>)}</ul>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        <button type="button" onClick={() => setHistoryId(historyId === profile.profileId ? '' : profile.profileId)}>
          {historyId === profile.profileId ? 'Hide' : 'View'} revisions for {profile.name}
        </button>
        {owner && <>
          <button type="button" disabled={pending || Boolean(form)} onClick={() => {
            setForm(profileForm(profile)); setProblem(null)
          }}>Edit {profile.name}</button>
          <button type="button" disabled={pending || Boolean(form)} onClick={async () => {
            setProblem(null)
            try { await status.mutateAsync({ profileId: profile.profileId, expectedVersion: profile.version, enabled: !profile.enabled }) }
            catch (error) { setProblem(errorMessage(error, 'Could not change the HTTP profile status.')) }
          }}>{profile.enabled ? 'Disable' : 'Enable'} {profile.name}</button>
          <button type="button" disabled={pending || Boolean(form)} onClick={async () => {
            if (!window.confirm(`Remove ${profile.name}? New HTTP requests using any of its revisions will be blocked.`)) return
            setProblem(null)
            try { await remove.mutateAsync({ profileId: profile.profileId, expectedVersion: profile.version }) }
            catch (error) { setProblem(errorMessage(error, 'Could not remove the HTTP profile.')) }
          }}>Remove {profile.name}</button>
        </>}
      </div>
      {owner && <HTTPProfileReadinessCheck key={JSON.stringify([projectId, profile.profileVersionId, profile.version, profile.enabled, pending])}
        projectId={projectId} profileVersionId={profile.profileVersionId} profileName={profile.name} disabled={pending} />}
      {historyId === profile.profileId && <div aria-label={`Revisions for ${profile.name}`}>
        {history.isPending && <p>Loading revisions...</p>}
        {history.isError && <p role="alert">{errorMessage(history.error, 'Could not load revisions.')}</p>}
        {history.data && <ProfileRevisionComparison key={profile.profileId}
          profileId={profile.profileId} currentVersionId={profile.profileVersionId} versions={history.data} />}
      </div>}
    </article>)}
    {form && owner && <form aria-label="Edit HTTP profile" onSubmit={submit}>
      <fieldset disabled={pending} style={{ display: 'grid', gap: 10, minWidth: 0, margin: 0, padding: 0, border: 0 }}>
        <legend>{form.profileId ? 'Edit profile' : 'New profile'}</legend>
        <label style={field}>Profile name<input required maxLength={120} value={form.name} onChange={(event) => set({ name: event.target.value })} /></label>
        <label style={field}>Base origin<input required type="url" placeholder="https://api.example.com" value={form.baseOrigin}
          onChange={(event) => set({ baseOrigin: event.target.value })} /></label>
        <small>The origin must be approved in the server configuration. Use a scheme and host, without a path or query.</small>
        <label style={field}>Registered credential alias<input maxLength={100} value={form.credentialAlias}
          onChange={(event) => set({ credentialAlias: event.target.value })} /></label>
        <small>Use an alias registered for this project by the server administrator. Leave empty for no authentication. Do not enter a password or token.</small>
        <label><input type="checkbox" checked={form.enabled} onChange={(event) => set({ enabled: event.target.checked })} /> Enabled</label>
        <label style={field}>Approved operations (JSON)<textarea rows={7} spellCheck={false} value={form.operations}
          onChange={(event) => set({ operations: event.target.value })} style={{ width: '100%', boxSizing: 'border-box' }} /></label>
        <small>Each operation needs operationId, method, relativePath and successStatuses (200–299). Paths are fixed and cannot contain a query.</small>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button type="submit">{save.isPending ? 'Saving...' : 'Save profile'}</button>
          <button type="button" onClick={() => { setForm(null); setProblem(null) }}>Cancel profile edit</button>
        </div>
      </fieldset>
    </form>}
    {problem && <p role="alert">{problem}</p>}
    <small>Disabling a profile blocks new requests from all of its revisions. It cannot undo a request already sent.</small>
  </section>
}

function ProfileRevisionComparison({ profileId, currentVersionId, versions }: {
  profileId: string; currentVersionId: string; versions: HttpProfileVersion[]
}) {
  const ordered = versions.filter((version) => version.profileId === profileId).sort((a, b) => b.revision - a.revision)
  const [selection, setSelection] = useState(() => defaultHttpProfileComparison(ordered, currentVersionId))
  const from = ordered.find((version) => version.profileVersionId === selection.fromVersionId)
  const to = ordered.find((version) => version.profileVersionId === selection.toVersionId)
  const comparison = from && to ? compareHttpProfileVersions(from, to) : null
  const labels = { baseOrigin: 'Base origin', credentialAlias: 'Credential alias' }
  const operationLabels = { method: 'Method', relativePath: 'Path', successStatuses: 'Accepted statuses' }
  return <section aria-label="Revision comparison" style={{ display: 'grid', gap: 10, minWidth: 0 }}>
    <h3 style={{ margin: 0 }}>Compare revisions</h3>
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, minWidth: 0 }}>
      {(['fromVersionId', 'toVersionId'] as const).map((side) => <label key={side} style={{ ...field, flex: '1 1 140px', minWidth: 0 }}>
        {side === 'fromVersionId' ? 'From revision' : 'To revision'}
        <select value={selection[side]} style={{ minWidth: 0, width: '100%' }} aria-invalid={Boolean(selection[side]
          && !ordered.some((version) => version.profileVersionId === selection[side]))}
          onChange={(event) => setSelection((current) => ({ ...current, [side]: event.target.value }))}>
          <option value="">Choose a revision</option>
          {selection[side] && !ordered.some((version) => version.profileVersionId === selection[side])
            && <option value={selection[side]}>Selected revision unavailable</option>}
          {ordered.map((version) => <option key={version.profileVersionId} value={version.profileVersionId}>Revision {version.revision}</option>)}
        </select>
      </label>)}
    </div>
    {!comparison && <p role="status">Choose two available revisions to compare. A previous selection may no longer be in the loaded history.</p>}
    {comparison && <div style={{ display: 'grid', gap: 10, minWidth: 0 }}>
      <strong>Revision {from!.revision} → revision {to!.revision}</strong>
      {!comparison.fields.length && !comparison.operations.length
        && <p role="status">No configuration changes between these revisions.</p>}
      {comparison.fields.map((change) => <div key={change.field} style={{ display: 'grid', gap: 4, overflowWrap: 'anywhere', minWidth: 0 }}>
        <strong>{labels[change.field]}</strong>
        <span>Before: {change.before ?? 'none'}</span>
        <span>After: {change.after ?? 'none'}</span>
      </div>)}
      {comparison.operations.map((change) => <article key={change.operationId} aria-label={`Operation ${change.operationId} ${change.kind}`}
        style={{ borderTop: '1px solid var(--border)', paddingTop: 8, display: 'grid', gap: 4, overflowWrap: 'anywhere', minWidth: 0 }}>
        <strong>{change.operationId}: {change.kind === 'added' ? 'Added' : change.kind === 'removed' ? 'Removed' : 'Changed'}</strong>
        {change.kind === 'changed' ? change.changedFields.map((name) => <div key={name} style={{ display: 'grid', gap: 2 }}>
          <strong>{operationLabels[name]}</strong>
          <span>Before: {operationValue(change.before!, name)}</span>
          <span>After: {operationValue(change.after!, name)}</span>
        </div>) : <span>{change.kind === 'added' ? 'After' : 'Before'}: {operationSummary((change.after ?? change.before)!)}</span>}
      </article>)}
    </div>}
    <small>These are saved configuration revisions. Credential aliases are references, not credential values. Comparing does not change a profile or a published workflow.</small>
  </section>
}

function operationValue(operation: HttpOperation, fieldName: 'method' | 'relativePath' | 'successStatuses') {
  return fieldName === 'successStatuses' ? operation.successStatuses.join(', ') : operation[fieldName]
}

function operationSummary(operation: HttpOperation) {
  return `${operation.method} ${operation.relativePath} · accepts ${operation.successStatuses.join(', ')}`
}
