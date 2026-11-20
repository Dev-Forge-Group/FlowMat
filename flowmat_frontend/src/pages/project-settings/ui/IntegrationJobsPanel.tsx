import { useState, type FormEvent } from 'react'
import { useHttpProfiles } from '../../../entities/connector/api/useHttpConnectors'
import type { HttpProfile } from '../../../entities/connector/model/types'
import { useIntegrationJobMutations, useIntegrationJobs, useIntegrationRuns } from '../../../entities/integration/api/useIntegrationJobs'
import {
  KIND_LABELS, emptyJobForm, exampleMapping, jobBody, jobForm, operationsFor, resultLabel, runSummary, type IntegrationJobForm,
} from '../../../entities/integration/model/integrationJobForm'
import type { IntegrationJob, IntegrationKind, IntegrationRun } from '../../../entities/integration/model/types'
import { useIsProjectOwner } from '../../../entities/project/api/useIsProjectOwner'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'

const field = { display: 'grid', gap: 4 } as const
const card = { borderTop: '1px solid var(--border)', paddingTop: 12, display: 'grid', gap: 6, minWidth: 0 } as const

/**
 * Integration jobs (docs/architecture/adr/ADR-038-integration-jobs.md): pull items or stock from another system through
 * the import's rules, or push stock and read it back. Every member sees them; the owner makes, previews, runs and
 * schedules them.
 */
export function IntegrationJobsPanel({ projectId }: { projectId: string }) {
  const owner = useIsProjectOwner(projectId)
  const query = useIntegrationJobs(projectId)
  const profilesQuery = useHttpProfiles(projectId)
  const { save, remove, schedule, preview, run } = useIntegrationJobMutations(projectId)
  const [form, setForm] = useState<IntegrationJobForm | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [historyId, setHistoryId] = useState('')
  const [result, setResult] = useState<{ jobId: string; run: IntegrationRun } | null>(null)
  const accessDenied = [401, 403, 404].includes(errorStatus(query.error) ?? 0)
  const jobs = !accessDenied && Array.isArray(query.data) ? query.data : []
  const profiles = Array.isArray(profilesQuery.data) ? profilesQuery.data : []
  const pending = save.isPending || remove.isPending || schedule.isPending || preview.isPending || run.isPending

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!form || pending) return
    setProblem(null)
    try {
      await save.mutateAsync({ jobId: form.jobId, body: jobBody(form) })
      setForm(null)
    } catch (error) { setProblem(errorMessage(error, 'Could not save the integration job.')) }
  }

  async function act(job: IntegrationJob, action: 'preview' | 'run') {
    setProblem(null)
    setResult(null)
    try {
      const done = action === 'preview' ? await preview.mutateAsync(job.jobId) : await run.mutateAsync(job.jobId)
      setResult({ jobId: job.jobId, run: done })
    } catch (error) {
      setProblem(errorMessage(error, action === 'preview' ? 'Could not preview the job.'
        : 'Could not run the job. Check its runs before running it again.'))
    }
  }

  return <section aria-label="Integration jobs" style={{ border: '1px solid var(--border)', borderRadius: 16, padding: 24,
    display: 'grid', gap: 12, minWidth: 0 }}>
    <h2 style={{ margin: 0 }}>Integration jobs</h2>
    <p style={{ margin: 0 }}>Bring items or stock in from another system through an HTTP profile, with the same checks as the CSV
      imports, or send stock out and read it back. A preview reads and checks without saving anything.</p>
    {!accessDenied && (owner ? <button type="button" disabled={pending || Boolean(form) || profiles.length === 0} onClick={() => {
      setForm(emptyJobForm(profiles[0])); setProblem(null)
    }}>Add integration job</button> : <p>Only the project owner can create, run or schedule integration jobs.</p>)}
    {owner && !profilesQuery.isPending && profiles.length === 0 && <p>Add an HTTP profile first; a job reads or sends through one.</p>}
    {query.isPending && <p>Loading integration jobs...</p>}
    {query.isError && <p role="alert">{errorMessage(query.error, 'Could not load integration jobs.')}</p>}
    {!query.isPending && !query.isError && jobs.length === 0 && <p>No integration jobs yet.</p>}
    {jobs.map((job) => <article key={job.jobId} aria-label={`Integration job ${job.name}`} style={card}>
      <strong>{job.name}</strong>
      <span>{KIND_LABELS[job.kind] ?? job.kind} · {profileName(profiles, job.profileVersionId)} · {job.operationId}
        {job.verifyOperationId ? `, read with ${job.verifyOperationId}` : ''}</span>
      <span>{job.scheduleEnabled
        ? `Scheduled ${job.scheduleCron} (project time) as ${job.runAs}; next ${formatTime(job.nextRunAt)}`
        : job.scheduleCron ? `Schedule off (${job.scheduleCron})` : 'Runs by hand'}</span>
      {job.pausedReason && <span role="status">{job.pausedReason}</span>}
      {!job.pausedReason && job.failuresInRow > 0 && <span>{job.failuresInRow} scheduled runs failed in a row.</span>}
      <span>Last run: {job.lastRun ? `${runSummary(job.lastRun)} (${formatTime(job.lastRun.startedAt)})` : 'none yet'}</span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        <button type="button" onClick={() => setHistoryId(historyId === job.jobId ? '' : job.jobId)}>
          {historyId === job.jobId ? 'Hide' : 'View'} runs of {job.name}
        </button>
        {owner && <>
          <button type="button" disabled={pending || Boolean(form)} onClick={() => void act(job, 'preview')}>Preview {job.name}</button>
          <button type="button" disabled={pending || Boolean(form)} onClick={() => void act(job, 'run')}>Run {job.name} now</button>
          <button type="button" disabled={pending || Boolean(form)} onClick={() => { setForm(jobForm(job)); setProblem(null) }}>
            Edit {job.name}</button>
          <button type="button" disabled={pending || Boolean(form)} onClick={async () => {
            if (!window.confirm(`Remove ${job.name}? Its schedule stops; its runs stay in the history.`)) return
            setProblem(null)
            try { await remove.mutateAsync(job.jobId) }
            catch (error) { setProblem(errorMessage(error, 'Could not remove the integration job.')) }
          }}>Remove {job.name}</button>
        </>}
      </div>
      {owner && <ScheduleForm key={`${job.jobId}-${job.version}`} job={job} disabled={pending || Boolean(form)}
        onSave={async (cron, enabled) => {
          setProblem(null)
          try { await schedule.mutateAsync({ jobId: job.jobId, cron, enabled, version: job.version }) }
          catch (error) { setProblem(errorMessage(error, 'Could not change the schedule.')) }
        }} />}
      {result?.jobId === job.jobId && <RunResult run={result.run} />}
      {historyId === job.jobId && <RunHistory projectId={projectId} job={job} />}
    </article>)}
    {form && owner && <JobForm form={form} profiles={profiles} pending={save.isPending} onChange={setForm} onSubmit={submit}
      onCancel={() => { setForm(null); setProblem(null) }} />}
    {problem && <p role="alert">{problem}</p>}
    <small>A scheduled job runs as the owner who switched it on, with no more access than they have, and stops after three failed
      runs in a row. Stock pulls run by hand only: reading the same list again would receive it again.</small>
  </section>
}

function JobForm({ form, profiles, pending, onChange, onSubmit, onCancel }: {
  form: IntegrationJobForm; profiles: HttpProfile[]; pending: boolean
  onChange: (form: IntegrationJobForm) => void; onSubmit: (event: FormEvent) => void; onCancel: () => void
}) {
  const set = (patch: Partial<IntegrationJobForm>) => onChange({ ...form, ...patch })
  const profile = profiles.find((candidate) => candidate.profileVersionId === form.profileVersionId)
  const push = form.kind === 'push_stock'
  const main = operationsFor(profile, push ? 'send' : 'read')
  const reads = operationsFor(profile, 'read')
  return <form aria-label="Edit integration job" onSubmit={onSubmit}>
    <fieldset disabled={pending} style={{ display: 'grid', gap: 10, minWidth: 0, margin: 0, padding: 0, border: 0 }}>
      <legend>{form.jobId ? 'Edit job' : 'New job'}</legend>
      <label style={field}>Job name<input required maxLength={100} value={form.name} onChange={(event) => set({ name: event.target.value })} /></label>
      <label style={field}>Kind<select value={form.kind} onChange={(event) => {
        const kind = event.target.value as IntegrationKind
        const operations = operationsFor(profile, kind === 'push_stock' ? 'send' : 'read')
        set({ kind, mapping: exampleMapping(kind), operationId: operations[0]?.operationId ?? '',
          verifyOperationId: kind === 'push_stock' ? reads[0]?.operationId ?? '' : '' })
      }}>
        {(Object.keys(KIND_LABELS) as IntegrationKind[]).map((kind) => <option key={kind} value={kind}>{KIND_LABELS[kind]}</option>)}
      </select></label>
      <label style={field}>HTTP profile<select value={form.profileVersionId} onChange={(event) => {
        const next = profiles.find((candidate) => candidate.profileVersionId === event.target.value)
        set({ profileVersionId: event.target.value, operationId: operationsFor(next, push ? 'send' : 'read')[0]?.operationId ?? '',
          verifyOperationId: push ? operationsFor(next, 'read')[0]?.operationId ?? '' : '' })
      }}>
        {!profile && <option value={form.profileVersionId}>{form.profileVersionId ? 'Saved profile revision' : 'Choose a profile'}</option>}
        {profiles.map((candidate) => <option key={candidate.profileVersionId} value={candidate.profileVersionId}>
          {candidate.name} (revision {candidate.currentRevision})</option>)}
      </select></label>
      <label style={field}>{push ? 'Operation that sends' : 'Operation that reads'}<select value={form.operationId}
        onChange={(event) => set({ operationId: event.target.value })}>
        {!main.some((operation) => operation.operationId === form.operationId) && <option value={form.operationId}>
          {form.operationId || 'Choose an operation'}</option>}
        {main.map((operation) => <option key={operation.operationId} value={operation.operationId}>
          {operation.operationId}: {operation.method} {operation.relativePath}</option>)}
      </select></label>
      {push && <label style={field}>Operation that reads the target<select value={form.verifyOperationId}
        onChange={(event) => set({ verifyOperationId: event.target.value })}>
        {!reads.some((operation) => operation.operationId === form.verifyOperationId) && <option value={form.verifyOperationId}>
          {form.verifyOperationId || 'Choose an operation'}</option>}
        {reads.map((operation) => <option key={operation.operationId} value={operation.operationId}>
          {operation.operationId}: {operation.method} {operation.relativePath}</option>)}
      </select></label>}
      {form.kind === 'pull_stock' && <label style={field}>Receipt note<input maxLength={200} value={form.note}
        onChange={(event) => set({ note: event.target.value })} /></label>}
      <label style={field}>Mapping (JSON)<textarea rows={9} spellCheck={false} value={form.mapping}
        onChange={(event) => set({ mapping: event.target.value })} style={{ width: '100%', boxSizing: 'border-box' }} /></label>
      <small>{push
        ? 'rowsAt points to the target\'s list; row reads itemCode and quantity from each record. An optional request maps FlowMat\'s {"items":[{"itemCode","quantity","unit"}]} to the body sent.'
        : 'rowsAt is a JSON pointer to the list of records (empty for the whole response); row turns one record into the import\'s fields.'}</small>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <button type="submit">{pending ? 'Saving...' : 'Save job'}</button>
        <button type="button" onClick={onCancel}>Cancel job edit</button>
      </div>
    </fieldset>
  </form>
}

function ScheduleForm({ job, disabled, onSave }: {
  job: IntegrationJob; disabled: boolean; onSave: (cron: string, enabled: boolean) => Promise<void>
}) {
  const [cron, setCron] = useState(job.scheduleCron ?? '0 6 * * 1-5')
  if (job.kind === 'pull_stock') return null
  return <form aria-label={`Schedule of ${job.name}`} style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'end' }}
    onSubmit={(event) => { event.preventDefault(); void onSave(cron, true) }}>
    <label style={{ ...field, flex: '1 1 180px' }}>Schedule (minute hour day month weekday)
      <input value={cron} spellCheck={false} disabled={disabled} onChange={(event) => setCron(event.target.value)} /></label>
    <button type="submit" disabled={disabled}>{job.scheduleEnabled ? 'Change schedule' : 'Switch schedule on'}</button>
    {job.scheduleEnabled && <button type="button" disabled={disabled} onClick={() => void onSave('', false)}>Switch schedule off</button>}
  </form>
}

function RunResult({ run }: { run: IntegrationRun }) {
  return <div aria-label={run.trigger === 'preview' ? 'Preview' : 'Run result'} role="status" style={{ display: 'grid', gap: 6 }}>
    <strong>{run.trigger === 'preview' ? 'Preview (nothing saved)' : 'Run'}: {runSummary(run)}</strong>
    <RunItems run={run} />
  </div>
}

function RunHistory({ projectId, job }: { projectId: string; job: IntegrationJob }) {
  const runs = useIntegrationRuns(projectId, job.jobId)
  const [open, setOpen] = useState('')
  return <div aria-label={`Runs of ${job.name}`} style={{ display: 'grid', gap: 6 }}>
    {runs.isPending && <p>Loading runs...</p>}
    {runs.isError && <p role="alert">{errorMessage(runs.error, 'Could not load runs.')}</p>}
    {runs.data && runs.data.length === 0 && <p>No runs yet.</p>}
    {runs.data?.map((entry) => <div key={entry.runId} style={{ display: 'grid', gap: 4 }}>
      <button type="button" style={{ justifySelf: 'start', textAlign: 'left' }} onClick={() => setOpen(open === entry.runId ? '' : entry.runId ?? '')}>
        {formatTime(entry.startedAt)} · {entry.trigger === 'schedule' ? `scheduled for ${formatTime(entry.scheduledFor)}` : 'by hand'}
        {' · '}{entry.runBy} · {runSummary(entry)}
      </button>
      {open === entry.runId && <RunItems run={entry} />}
    </div>)}
  </div>
}

function RunItems({ run }: { run: IntegrationRun }) {
  if (!run.items.length) return null
  return <div style={{ overflowX: 'auto' }}><table style={{ borderCollapse: 'collapse', minWidth: 360 }}>
    <thead><tr><th style={{ textAlign: 'left' }}>#</th><th style={{ textAlign: 'left' }}>Item</th>
      <th style={{ textAlign: 'left' }}>Result</th><th style={{ textAlign: 'left' }}>Why</th></tr></thead>
    <tbody>{run.items.map((item) => <tr key={item.no}>
      <td>{item.no}</td><td>{item.key ?? ''}</td><td>{resultLabel(item.result)}</td><td>{item.message ?? ''}</td>
    </tr>)}</tbody>
  </table></div>
}

function profileName(profiles: HttpProfile[], profileVersionId: string) {
  const profile = profiles.find((candidate) => candidate.profileVersionId === profileVersionId)
  return profile ? profile.name : 'an earlier profile revision'
}

function formatTime(value: string | null | undefined) {
  if (!value) return '—'
  const time = new Date(value)
  return Number.isNaN(time.getTime()) ? value : time.toLocaleString()
}
