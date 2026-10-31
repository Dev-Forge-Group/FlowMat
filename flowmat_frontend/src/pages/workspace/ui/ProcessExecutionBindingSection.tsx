import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useHttpBinding, useHttpProfiles, useHttpProfileVersions, useJsonMappingPreview, usePinnedHttpProfileVersion, useSaveHttpBinding } from '../../../entities/connector/api/useHttpConnectors'
import { bindingBody, type BindingForm } from '../../../entities/connector/model/connectorForms'
import { useProjectRole } from '../../../entities/project/api/useProjectRole'
import type { CanvasPortViewModel } from '../../../entities/workflow/model/types'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'

const field = { display: 'grid', gap: 4, minWidth: 0 } as const
const control = { minWidth: 0, width: '100%', boxSizing: 'border-box' } as const

export function ProcessExecutionBindingSection({ processId, projectId, outputs }: {
  processId: string; projectId: string; outputs: CanvasPortViewModel[]
}) {
  const role = useProjectRole(projectId)
  const mayEdit = role === 'owner' || role === 'editor'
  const query = useHttpBinding(processId)
  const profiles = useHttpProfiles(projectId)
  const save = useSaveHttpBinding(processId)
  const preview = useJsonMappingPreview(projectId)
  const [form, setForm] = useState<BindingForm | null>(null)
  // null follows the pinned parent; an explicit selection (including blank) must survive late reads.
  const [profileId, setProfileId] = useState<string | null>(null)
  const [source, setSource] = useState('{}')
  const [previewResult, setPreviewResult] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [readRefusal, setReadRefusal] = useState<string | null>(null)
  const [recovering, setRecovering] = useState(false)
  const editGeneration = useRef(0)
  const binding = query.data
  const profileOptions = (profiles.data ?? []).filter((profile) => profile.enabled)
  const pinned = usePinnedHttpProfileVersion(projectId, form?.profileVersionId ?? '')
  const pinnedVersion = pinned.data?.profileVersionId === form?.profileVersionId ? pinned.data : undefined
  const selectedProfileId = profileId ?? (profileOptions.find((profile) => profile.profileVersionId === form?.profileVersionId)?.profileId
    ?? pinnedVersion?.profileId ?? '')
  const selectedProfile = (profiles.data ?? []).find((profile) => profile.profileId === selectedProfileId)
  const history = useHttpProfileVersions(projectId, selectedProfile?.enabled ? selectedProfileId : '')
  const historyVersions = (history.data ?? []).filter((version) => version.profileId === selectedProfileId)
  const selectedMetadata = pinnedVersion?.profileId === selectedProfileId ? pinnedVersion : undefined
  const versionOptions = selectedMetadata && !historyVersions.some((version) => version.profileVersionId === selectedMetadata.profileVersionId)
    ? [...historyVersions, selectedMetadata] : historyVersions
  const selectedVersion = versionOptions.find((version) => version.profileVersionId === form?.profileVersionId)
  const operations = selectedVersion?.operations ?? []
  const locked = save.isPending || preview.isPending
  const refusedRead = [query, profiles].find((read) => [401, 403, 404].includes(errorStatus(read.error) ?? 0))
    ?? [pinned, history].find((read) => [401, 403].includes(errorStatus(read.error) ?? 0))
  const accessDenied = readRefusal !== null || Boolean(refusedRead)
  const refusalMessage = refusedRead ? errorMessage(refusedRead.error, 'HTTP binding access is unavailable.') : readRefusal

  useEffect(() => {
    if (!refusalMessage) return
    editGeneration.current += 1
    setReadRefusal(refusalMessage)
    setSource('{}'); setPreviewResult(null); setProblem(null); setSaved(false)
  }, [refusalMessage])

  useEffect(() => () => { editGeneration.current += 1 }, [])

  async function refreshAccess() {
    if (save.isPending || recovering) return
    setRecovering(true)
    try {
      // Keep the hidden editor's query keys until every refused read has been checked again.
      const reads = await Promise.all([
        query.refetch().then((read) => ({ read, optional: false })),
        profiles.refetch().then((read) => ({ read, optional: false })),
        ...(form?.profileVersionId ? [pinned.refetch().then((read) => ({ read, optional: true }))] : []),
        ...(selectedProfile?.enabled ? [history.refetch().then((read) => ({ read, optional: true }))] : []),
      ])
      const failed = reads.find(({ read, optional }) => read.isError && !(optional && errorStatus(read.error) === 404))
      if (failed) {
        setReadRefusal(errorMessage(failed.read.error, 'Could not refresh HTTP binding access.'))
        return
      }
      editGeneration.current += 1
      setForm(null); setProfileId(null); setSource('{}'); setPreviewResult(null)
      setProblem(null); setSaved(false); setReadRefusal(null)
    } finally { setRecovering(false) }
  }
  const set = (patch: Partial<BindingForm>) => {
    setForm((current) => current ? { ...current, ...patch } : null)
    setPreviewResult(null)
  }

  function edit() {
    if (!binding || accessDenied) return
    editGeneration.current += 1
    setForm({ profileVersionId: binding.profileVersionId ?? '', operationId: binding.operationId ?? '',
      outputPortId: binding.outputPortId ?? '', requestMapping: binding.requestMappingJson ?? '{"from":""}',
      responseMapping: binding.responseMappingJson ?? '{"from":""}', expectedVersion: binding.version })
    setProfileId(null)
    setProblem(null); setSaved(false); setPreviewResult(null)
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!form || locked || accessDenied) return
    const generation = editGeneration.current
    setProblem(null)
    try {
      if (!form.profileVersionId || !form.operationId || !form.outputPortId) throw new Error('Select a profile revision, operation and output port.')
      if (!selectedProfile?.enabled || !selectedVersion || !operations.some((operation) => operation.operationId === form.operationId)) {
        throw new Error('Select an enabled profile revision and one of its approved operations.')
      }
      await save.mutateAsync(bindingBody(form))
      if (generation !== editGeneration.current) return
      setForm(null); setSaved(true)
    } catch (error) {
      if (generation === editGeneration.current) setProblem(errorMessage(error, 'Could not save the HTTP binding.'))
    }
  }

  async function showPreview(kind: 'request' | 'response') {
    if (!form || locked || accessDenied) return
    const generation = editGeneration.current
    setProblem(null); setPreviewResult(null)
    try {
      const result = await preview.mutateAsync({ source, mapping: kind === 'request' ? form.requestMapping : form.responseMapping })
      if (generation !== editGeneration.current) return
      setPreviewResult(result.resultJson ?? 'Exact preview JSON is unavailable. Reload after updating the server.')
    } catch (error) {
      if (generation === editGeneration.current) setProblem(errorMessage(error, 'Could not preview the mapping.'))
    }
  }

  return <section aria-label="HTTP execution binding" className="inspector__section" style={{ marginTop: 16 }}>
    <h4 style={{ marginTop: 0 }}>HTTP execution</h4>
    {query.isPending && <p>Loading HTTP binding...</p>}
    {!accessDenied && query.isError && <p role="alert">{errorMessage(query.error, 'Could not load the HTTP binding.')}</p>}
    {accessDenied && <>
      <p role="alert">{refusalMessage}</p>
      <button type="button" disabled={save.isPending || recovering} onClick={() => { void refreshAccess() }}>Refresh HTTP binding</button>
    </>}
    {!accessDenied && binding && !form && <>
      <p>{binding.profileVersionId ? `Operation ${binding.operationId} · profile revision ${binding.profileVersionId}` : 'No HTTP operation bound.'}</p>
      {mayEdit && <button type="button" onClick={edit}>Edit HTTP binding</button>}
      {!mayEdit && <p>Editors and the project owner can change this binding.</p>}
    </>}
    {!accessDenied && saved && !form && <p role="status">Saved. Publish a revision to use this binding in new runs.</p>}
    {!accessDenied && form && mayEdit && <form aria-label="Edit HTTP binding" onSubmit={submit}>
      <fieldset disabled={locked} style={{ display: 'grid', gap: 8, minWidth: 0, border: 0, padding: 0, margin: 0 }}>
        <legend>Bind an approved HTTP operation</legend>
        <label style={field}>HTTP profile<select style={control} value={selectedProfileId} onChange={(event) => {
          setProfileId(event.target.value)
          set({ profileVersionId: '', operationId: '' })
        }}><option value="">{form.profileVersionId ? 'Keep pinned revision or choose a profile' : 'Choose a profile'}</option>
          {selectedProfileId && !profileOptions.some((profile) => profile.profileId === selectedProfileId)
            && <option value={selectedProfileId} disabled>{selectedProfile?.name ?? 'Pinned profile'} (disabled or removed)</option>}
          {profileOptions.map((profile) => <option key={profile.profileId} value={profile.profileId}>{profile.name}</option>)}
        </select></label>
        {profiles.isPending && <p>Loading HTTP profiles...</p>}
        {profiles.isError && <p role="alert">{errorMessage(profiles.error, 'Could not load profiles.')}</p>}
        {selectedProfileId && !selectedProfile?.enabled && !profiles.isPending && !profiles.isError
          && <p>This profile is disabled or removed. Choose an enabled profile or clear the binding.</p>}
        <Link to={`/projects/${encodeURIComponent(projectId)}/settings`}>Manage HTTP profiles</Link>
        <label style={field}>Profile revision<select style={control} value={form.profileVersionId} onChange={(event) => {
          setProfileId(selectedProfileId)
          set({ profileVersionId: event.target.value, operationId: '' })
        }}><option value="">Choose a revision</option>
          {form.profileVersionId && !selectedVersion && <option value={form.profileVersionId}>Pinned {form.profileVersionId}</option>}
          {versionOptions.map((version) => <option key={version.profileVersionId} value={version.profileVersionId}>
            Revision {version.revision} · {version.baseOrigin}
          </option>)}
        </select></label>
        {selectedProfile?.enabled && history.isPending && <p>Loading profile revisions...</p>}
        {history.isError && <p role="alert">{errorMessage(history.error, 'Could not load profile revisions.')}</p>}
        {form.profileVersionId && !selectedVersion && pinned.isPending && <p>Loading pinned profile revision...</p>}
        {form.profileVersionId && !selectedVersion && pinned.isError && <>
          <p role="alert">{errorMessage(pinned.error, 'Could not load the pinned profile revision.')}</p>
          <button type="button" onClick={() => { void pinned.refetch() }}>Retry pinned revision</button>
        </>}
        <label style={field}>Approved operation<select style={control} value={form.operationId} onChange={(event) => set({ operationId: event.target.value })}>
          <option value="">Choose an operation</option>
          {form.operationId && !operations.some((operation) => operation.operationId === form.operationId)
            && <option value={form.operationId}>{form.operationId} (pinned)</option>}
          {operations.map((operation) => <option key={operation.operationId} value={operation.operationId}>
            {operation.operationId} · {operation.method} {operation.relativePath}
          </option>)}
        </select></label>
        <label style={field}>Result output port<select style={control} value={form.outputPortId} onChange={(event) => set({ outputPortId: event.target.value })}>
          <option value="">Choose an output port</option>
          {outputs.map((port) => <option key={port.processIoId} value={port.processIoId}>{port.name}</option>)}
        </select></label>
        {outputs.length === 0 && <p>Add an output port before saving an HTTP binding.</p>}
        <label style={field}>Request mapping (JSON)<textarea style={control} rows={5} spellCheck={false} value={form.requestMapping}
          onChange={(event) => set({ requestMapping: event.target.value })} /></label>
        <label style={field}>Response mapping (JSON)<textarea style={control} rows={5} spellCheck={false} value={form.responseMapping}
          onChange={(event) => set({ responseMapping: event.target.value })} /></label>
        <small>Use {`{"from":"/path"}`} to copy a value, {`{"literal":value}`} for a fixed value, or {`{"object":{"field":mapping}}`} to build an object.
          {' '}An empty path copies the whole source. Preview only transforms JSON and sends no external HTTP request.</small>
        <label style={field}>Preview source (JSON)<textarea style={control} rows={4} spellCheck={false} value={source}
          onChange={(event) => { setSource(event.target.value); setPreviewResult(null) }} /></label>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button type="button" onClick={() => { void showPreview('request') }}>Preview request mapping</button>
          <button type="button" onClick={() => { void showPreview('response') }}>Preview response mapping</button>
        </div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button type="submit" disabled={!selectedProfile?.enabled || !selectedVersion
            || !operations.some((operation) => operation.operationId === form.operationId)}>{save.isPending ? 'Saving...' : 'Save HTTP binding'}</button>
          <button type="button" onClick={() => { setForm(null); setProblem(null); setPreviewResult(null) }}>Cancel HTTP edit</button>
          {binding?.profileVersionId && <button type="button" onClick={async () => {
            if (locked || accessDenied) return
            const generation = editGeneration.current
            setProblem(null)
            try {
              await save.mutateAsync(bindingBody(null, form.expectedVersion))
              if (generation === editGeneration.current) { setForm(null); setSaved(true) }
            } catch (error) {
              if (generation === editGeneration.current) setProblem(errorMessage(error, 'Could not clear the HTTP binding.'))
            }
          }}>Clear HTTP binding</button>}
        </div>
      </fieldset>
    </form>}
    {!accessDenied && mayEdit && previewResult !== null && <pre aria-label="Mapping preview result" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{previewResult}</pre>}
    {!accessDenied && problem && <p role="alert">{problem}</p>}
    <p className="inspector-hint">External requests require an actual graph run and an explicit action by the project owner.</p>
  </section>
}
