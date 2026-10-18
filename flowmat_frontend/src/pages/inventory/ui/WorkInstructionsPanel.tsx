import { InstructionAttachmentsPanel } from './InstructionAttachmentsPanel'
import { useState, type FormEvent } from 'react'
import {
  useWorkInstructionMutations,
  useWorkInstructionsQuery,
  type WorkInstructionDto,
} from '../../../entities/production/api/useWorkInstructions'
import { useInspectionStandardsQuery } from '../../../entities/quality/api/useInspectionStandards'
import { useWorkflowsQuery } from '../../../entities/workflow/api/useWorkflowsQuery'
import { useWorkflowCanvasQuery } from '../../../entities/workflow/api/useWorkflowCanvasQuery'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { ItemDto } from '../../../shared/types/api'
import { pickableItems } from '../model/itemStatusModel'
import {
  EMPTY_STEP,
  documentUrlProblem,
  followableStandards,
  instructionStatus,
  limitText,
  revisionsByItem,
  shownRevision,
  standardNote,
  standardOptionText,
  stepPayload,
} from '../model/workInstructionModel'

const cell = { padding: '6px 6px' } as const

/**
 * Work instructions per product (docs/domain/work-instruction.md): write a draft revision with numbered steps, release it,
 * and start the next revision from it. Runs of the product work through the released steps.
 */
export function WorkInstructionsPanel({ projectId, items }: { projectId: string; items: ItemDto[] }) {
  const query = useWorkInstructionsQuery(projectId)
  const mutations = useWorkInstructionMutations(projectId)
  const byItem = revisionsByItem(query.data ?? [])
  const [itemId, setItemId] = useState('')
  const [title, setTitle] = useState('')
  const products = pickableItems(items)
  const label = new Map(items.map((item) => [item.itemId, `${item.itemCode} · ${item.itemName}`]))
  const revisions = itemId ? byItem.get(itemId) ?? [] : []
  const shown = shownRevision(revisions)

  function start(event: FormEvent) {
    event.preventDefault()
    mutations.create.mutate({ itemId, title: title.trim() }, { onSuccess: () => setTitle('') })
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.4fr)', gap: 24, alignItems: 'start' }}>
      <section>
        <h2>Work instructions</h2>
        {query.isError && <p role="alert">{errorMessage(query.error)}</p>}
        <label style={{ display: 'grid', gap: 4, marginBottom: 12 }}>Product
          <select aria-label="Product" value={itemId} onChange={(event) => setItemId(event.target.value)}>
            <option value="">Choose a product</option>
            {products.map((item) => (
              <option key={item.itemId} value={item.itemId}>{label.get(item.itemId)} · {instructionStatus(byItem.get(item.itemId) ?? [])}</option>
            ))}
          </select>
        </label>
        {byItem.size === 0
          ? <p className="inspector-hint">No work instructions yet.</p>
          : <table aria-label="Products with instructions" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <tbody>{[...byItem.entries()].map(([id, list]) => (
              <tr key={id} onClick={() => setItemId(id)}
                style={{ borderBottom: '1px solid var(--border)', cursor: 'pointer', background: id === itemId ? 'var(--accent-bg)' : undefined }}>
                <td style={cell}>{label.get(id) ?? list[0].itemCode ?? id}</td>
                <td style={cell}>{list[0].title}</td>
                <td style={cell}>{instructionStatus(list)}</td>
              </tr>
            ))}</tbody>
          </table>}
      </section>

      <section aria-label="Instruction" style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
        {!itemId && <p className="inspector-hint">Choose a product to write or read its instruction.</p>}
        {itemId && !shown && (
          <form aria-label="Start instruction" onSubmit={start} style={{ display: 'grid', gap: 8 }}>
            <h3 style={{ margin: 0 }}>{label.get(itemId)}</h3>
            <label style={{ display: 'grid', gap: 4 }}>Title
              <input value={title} required maxLength={200} onChange={(event) => setTitle(event.target.value)} />
            </label>
            <button type="submit" disabled={mutations.create.isPending}>Start instruction</button>
            {mutations.create.isError && <p role="alert">{errorMessage(mutations.create.error)}</p>}
          </form>
        )}
        {shown && <InstructionDetail key={shown.instructionId} instruction={shown} revisions={revisions} projectId={projectId} />}
      </section>
    </div>
  )
}

function InstructionDetail({ instruction, revisions, projectId }: {
  instruction: WorkInstructionDto
  revisions: WorkInstructionDto[]
  projectId: string
}) {
  const mutations = useWorkInstructionMutations(projectId)
  const draft = instruction.status === 'draft'
  const [text, setText] = useState({
    title: instruction.title, body: instruction.body ?? '', documentUrl: instruction.documentUrl ?? '', blocksFinish: instruction.blocksFinish,
  })
  const [step, setStep] = useState(EMPTY_STEP)
  const [problem, setProblem] = useState<string | null>(null)
  // Only a draft adds steps, so only a draft needs the standards it can follow.
  const standardsQuery = useInspectionStandardsQuery(projectId, draft)
  const standards = followableStandards(standardsQuery.data ?? [], instruction.itemId)
  const followed = standards.find((one) => one.standardId === step.standardId)
  // A step can name the process it belongs to (docs/architecture/adr/ADR-011-production-steps.md decision 6).
  const workflowsQuery = useWorkflowsQuery(draft ? projectId : '')
  const [processWorkflowId, setProcessWorkflowId] = useState('')
  const processesQuery = useWorkflowCanvasQuery(processWorkflowId)
  const processes = processesQuery.data?.nodes ?? []
  const released = revisions.find((one) => one.status === 'released')
  const failure = [mutations.update, mutations.addStep, mutations.removeStep, mutations.release, mutations.revise, mutations.remove]
    .find((mutation) => mutation.isError)

  function saveText(event: FormEvent) {
    event.preventDefault()
    const urlProblem = documentUrlProblem(text.documentUrl)
    setProblem(urlProblem)
    if (urlProblem) return
    mutations.update.mutate({
      instructionId: instruction.instructionId, title: text.title.trim(), body: text.body.trim() || null,
      documentUrl: text.documentUrl.trim() || null, blocksFinish: text.blocksFinish,
    })
  }

  function addStep(event: FormEvent) {
    event.preventDefault()
    const payload = stepPayload(step)
    setProblem(payload.error)
    if (!payload.input) return
    // Clear the form only if nothing was typed while saving, so the next step's text is not lost.
    const sent = step
    mutations.addStep.mutate({ instructionId: instruction.instructionId, ...payload.input }, {
      onSuccess: () => setStep((current) => (current === sent ? EMPTY_STEP : current)),
    })
  }

  return (
    <div style={{ display: 'grid', gap: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 8 }}>
        <h3 style={{ margin: 0 }}>{instruction.title} <code>v{instruction.revisionNo}</code></h3>
        <strong role="status">{instruction.status}</strong>
      </div>
      <span className="inspector-hint">
        {instructionStatus(revisions)}{released?.releasedBy ? ` · released by ${released.releasedBy}` : ''}
      </span>

      {draft ? (
        <form aria-label="Instruction text" onSubmit={saveText} style={{ display: 'grid', gap: 8 }}>
          <label style={{ display: 'grid', gap: 4 }}>Title
            <input value={text.title} required maxLength={200} onChange={(event) => setText({ ...text, title: event.target.value })} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Instructions
            <textarea rows={4} value={text.body} maxLength={20000} onChange={(event) => setText({ ...text, body: event.target.value })} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Document link
            <input value={text.documentUrl} maxLength={500} placeholder="https://…"
              onChange={(event) => setText({ ...text, documentUrl: event.target.value })} />
          </label>
          <label style={{ fontSize: 13 }}>
            <input type="checkbox" checked={text.blocksFinish} onChange={(event) => setText({ ...text, blocksFinish: event.target.checked })} />
            {' '}Runs cannot finish until the required steps are confirmed
          </label>
          <button type="submit" disabled={mutations.update.isPending}>Save text</button>
        </form>
      ) : (
        <>
          {instruction.body && <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{instruction.body}</p>}
          {instruction.documentUrl && <a href={instruction.documentUrl} target="_blank" rel="noreferrer">Open document</a>}
          {instruction.blocksFinish && <span className="inspector-hint">Runs cannot finish until the required steps are confirmed.</span>}
        </>
      )}

      <InstructionAttachmentsPanel key={instruction.instructionId} projectId={projectId} instructionId={instruction.instructionId} editable={draft} />
      <ol aria-label="Steps" style={{ margin: 0, paddingLeft: 20, display: 'grid', gap: 4 }}>
        {instruction.steps.map((one) => (
          <li key={one.stepId}>
            {one.text}
            {!one.required && <span className="inspector-hint"> (optional)</span>}
            {one.processName && <span className="inspector-hint"> · at {one.processName}</span>}
            {one.recordsValue && <span className="inspector-hint">
              {' '}· records {one.valueLabel ?? 'a value'}{limitText(one) && ` (${limitText(one)})`}
              {one.standardId && ` · follows ${one.standardName ?? 'a standard'}`}</span>}
            {standardNote(one, draft) && <div role="note" style={{ color: '#b45309', fontSize: 12 }}>{standardNote(one, draft)}</div>}
            {draft && (
              <button type="button" style={{ marginLeft: 6, fontSize: 11 }} disabled={mutations.removeStep.isPending}
                onClick={() => mutations.removeStep.mutate({ instructionId: instruction.instructionId, stepId: one.stepId })}>Remove</button>
            )}
          </li>
        ))}
      </ol>
      {instruction.steps.length === 0 && <p className="inspector-hint">No steps yet.</p>}

      {draft && (
        <form aria-label="Add step" onSubmit={addStep} style={{ display: 'grid', gap: 6 }}>
          <label style={{ display: 'grid', gap: 4 }}>Step
            <input value={step.text} maxLength={500} onChange={(event) => setStep({ ...step, text: event.target.value })} />
          </label>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center', fontSize: 13 }}>
            <label><input type="checkbox" checked={step.required} onChange={(event) => setStep({ ...step, required: event.target.checked })} /> Required</label>
            <label><input type="checkbox" checked={step.recordsValue}
              onChange={(event) => setStep({ ...step, recordsValue: event.target.checked })} /> Records a value</label>
            {step.recordsValue && standards.length > 0 && <label>Limits from
              <select value={step.standardId ?? ''} onChange={(event) => setStep({ ...step, standardId: event.target.value })}>
                <option value="">(typed here)</option>
                {standards.map((one) => <option key={one.standardId} value={one.standardId}>{standardOptionText(one)}</option>)}
              </select></label>}
            {step.recordsValue && <label>Value label <input value={step.valueLabel} maxLength={100}
              placeholder={followed ? `${followed.inspectionType}${followed.unit ? ` ${followed.unit}` : ''}` : 'Oven °C'}
              onChange={(event) => setStep({ ...step, valueLabel: event.target.value })} /></label>}
            {step.recordsValue && !followed && <label>Min <input type="number" step="any" value={step.valueMin ?? ''} style={{ width: 80 }}
              onChange={(event) => setStep({ ...step, valueMin: event.target.value })} /></label>}
            {step.recordsValue && !followed && <label>Max <input type="number" step="any" value={step.valueMax ?? ''} style={{ width: 80 }}
              onChange={(event) => setStep({ ...step, valueMax: event.target.value })} /></label>}
          </div>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center', fontSize: 13 }}>
            <label>Workflow <select aria-label="Belongs to workflow" value={processWorkflowId}
              onChange={(event) => { setProcessWorkflowId(event.target.value); setStep({ ...step, processId: '' }) }}>
              <option value="">(the whole run)</option>
              {(workflowsQuery.data ?? []).map((one) => <option key={one.workflowId} value={one.workflowId}>{one.workflowName}</option>)}
            </select></label>
            {processWorkflowId && <label>At process <select aria-label="Belongs to process" value={step.processId ?? ''}
              onChange={(event) => setStep({ ...step, processId: event.target.value })}>
              <option value="">(the whole run)</option>
              {processes.map((node) => <option key={node.processId} value={node.processId}>{node.name}</option>)}
            </select></label>}
          </div>
          <button type="submit" disabled={mutations.addStep.isPending}>Add step</button>
        </form>
      )}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {draft && <button type="button" disabled={mutations.release.isPending || instruction.steps.length === 0}
          onClick={() => mutations.release.mutate(instruction.instructionId)}>Release</button>}
        {draft && <button type="button" disabled={mutations.remove.isPending} onClick={() => {
          if (window.confirm(`Delete draft v${instruction.revisionNo}?`)) mutations.remove.mutate(instruction.instructionId)
        }}>Delete draft</button>}
        {!draft && <button type="button" disabled={mutations.revise.isPending}
          onClick={() => mutations.revise.mutate(instruction.instructionId)}>New revision</button>}
      </div>
      {(problem ?? (failure ? errorMessage(failure.error) : null)) && <p role="alert">{problem ?? errorMessage(failure?.error)}</p>}
    </div>
  )
}
