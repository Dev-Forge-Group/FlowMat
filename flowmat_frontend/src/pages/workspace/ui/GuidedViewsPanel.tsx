import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useGuidedViewMutations, type GuidedViewInput } from '../../../entities/workflow/api/useGuidedViews'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import { BEAT_MS, liveFocus, neighbourChapter, nextBeat, type GuidedChapter } from '../model/guidedViews'

const MAX_CHAPTERS = 8
const MAX_PROCESSES = 30

const count = (value: number, one: string, many = `${one}s`) => `${value} ${value === 1 ? one : many}`

/**
 * archify's guided views (adapted from archify viewer/guided-views.js, Apache-2.0): the workflow told in chapters. Each
 * chapter shows some processes strongly and the rest receded; choosing one brings it into view, and playing walks each
 * chapter's processes in order, one beat each, then goes on to the next chapter. The address keeps the chapter and the
 * process reached (`#view=`, `&beat=`), so a copied link opens the same moment. Whoever may edit the workflow makes a
 * chapter from the processes selected on the canvas, renames it, moves it and deletes it (ADR-037).
 */
export function GuidedViewsPanel({
  workflowId, chapters, activeId, beat, known, nameOf, canEdit, selectedProcessIds, onShow, onClear, onClose,
}: {
  workflowId: string
  chapters: readonly GuidedChapter[]
  activeId: string | null
  beat: string | null
  known(processId: string): boolean
  nameOf(processId: string): string
  canEdit: boolean
  /** The processes selected on the canvas, in reading order, for a new chapter. */
  selectedProcessIds: readonly string[]
  onShow(viewId: string, beat?: string | null): void
  onClear(): void
  onClose(): void
}) {
  const queryClient = useQueryClient()
  const { create, update, remove, reorder } = useGuidedViewMutations(workflowId)
  const [playing, setPlaying] = useState(false)
  const [form, setForm] = useState<{ mode: 'new' | 'edit'; label: string; note: string } | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [message, setMessage] = useState<{ error: boolean; text: string } | null>(null)
  const busy = create.isPending || update.isPending || remove.isPending || reorder.isPending
  const active = chapters.find((chapter) => chapter.id === activeId) ?? null
  const activeIndex = active ? chapters.indexOf(active) : -1
  const showRef = useRef(onShow)
  showRef.current = onShow

  // Playing: one beat at a time, on to the next chapter after the last process, and stop after the last chapter.
  useEffect(() => {
    if (!playing) return
    const timer = window.setTimeout(() => {
      const start = activeId ?? chapters.find((chapter) => liveFocus(chapter, known).length > 0)?.id
      const next = start ? nextBeat(chapters, { viewId: start, beat: activeId ? beat : null }, known) : null
      if (next) showRef.current(next.viewId, next.beat)
      else setPlaying(false)
    }, activeId ? BEAT_MS : 0)
    return () => window.clearTimeout(timer)
  }, [activeId, beat, chapters, known, playing])

  // A form or a delete question belongs to the chapter it was opened for.
  useEffect(() => {
    setForm((current) => (current?.mode === 'edit' ? null : current))
    setConfirmDelete(false)
  }, [activeId])

  async function run(action: () => Promise<unknown>, done: string) {
    setMessage(null)
    try {
      await action()
      setMessage({ error: false, text: done })
      return true
    } catch (error) {
      if (errorStatus(error) === 409) {
        void queryClient.invalidateQueries({ queryKey: ['guided-views', workflowId] })
        setMessage({ error: true, text: 'Someone changed this chapter meanwhile. It has been reloaded; make the change again.' })
      } else {
        setMessage({ error: true, text: errorMessage(error, 'The chapter could not be saved.') })
      }
      return false
    }
  }

  async function submit() {
    if (!form) return
    const input: GuidedViewInput = {
      label: form.label.trim(),
      note: form.note.trim() || null,
      focusProcessIds: form.mode === 'new' || !active ? [...selectedProcessIds] : [...active.focus],
    }
    const saved = form.mode === 'new'
      ? await run(async () => {
        const made = await create.mutateAsync(input)
        onShow(made.guidedViewId)
      }, `Added chapter ${input.label}`)
      : active && await run(() => update.mutateAsync({ ...input, guidedViewId: active.id, version: active.version }),
        `Saved chapter ${input.label}`)
    if (saved) setForm(null)
  }

  const tooManySelected = selectedProcessIds.length > MAX_PROCESSES

  return (
    <section className="process-index guided-views" role="region" aria-label="Guided views"
      onKeyDown={(event) => {
        if (event.key === 'Escape' && !form) {
          event.preventDefault()
          event.stopPropagation()
          setPlaying(false)
          onClose()
        }
      }}>
      <header className="process-index-head">
        <strong>Guided views</strong>
        <small>{count(chapters.length, 'chapter')}</small>
        <button type="button" className="process-index-close" aria-label="Close guided views" title="Close (Esc)"
          onClick={() => {
            setPlaying(false)
            onClose()
          }}>&#215;</button>
      </header>
      <div className="process-index-list">
        {chapters.length === 0 && (
          <p className="process-index-empty">
            No chapters yet.{canEdit ? ' Select processes on the canvas and add one to walk a reader through the workflow.' : ''}
          </p>
        )}
        <ol className="guided-views-chapters" aria-label="Chapters">
          {chapters.map((chapter, index) => {
            const shown = liveFocus(chapter, known).length
            return (
              <li key={chapter.id}>
                <button type="button" className="process-index-item guided-views-chapter"
                  aria-current={chapter.id === activeId ? 'true' : undefined}
                  onClick={() => {
                    setPlaying(false)
                    onShow(chapter.id)
                  }}>
                  <span className="guided-views-number" aria-hidden="true">{index + 1}</span>
                  <strong>{chapter.label}</strong>
                  <small>{shown === 0 ? 'no processes left' : count(shown, 'process', 'processes')}</small>
                </button>
              </li>
            )
          })}
        </ol>
        {active && (
          <div className="guided-views-caption" aria-label="Chapter being read">
            <strong>{activeIndex + 1} / {chapters.length} · {active.label}</strong>
            {active.note && <p>{active.note}</p>}
            {beat && <p className="guided-views-beat">At {nameOf(beat)}</p>}
          </div>
        )}
        <div className="guided-views-controls">
          <button type="button" disabled={!active || activeIndex <= 0} onClick={() => {
            const previous = active && neighbourChapter(chapters, active.id, -1)
            if (previous) onShow(previous.id)
          }}>Previous</button>
          <button type="button" aria-pressed={playing} disabled={chapters.every((chapter) => liveFocus(chapter, known).length === 0)}
            onClick={() => setPlaying((on) => !on)}>{playing ? 'Pause' : 'Play'}</button>
          <button type="button" disabled={!active || activeIndex >= chapters.length - 1} onClick={() => {
            const following = active && neighbourChapter(chapters, active.id, 1)
            if (following) onShow(following.id)
          }}>Next</button>
          {active && (
            <button type="button" onClick={() => {
              void navigator.clipboard?.writeText(window.location.href).then(
                () => setMessage({ error: false, text: 'Copied a link to this moment' }),
                () => setMessage({ error: true, text: 'The link could not be copied' }))
            }}>Copy link</button>
          )}
        </div>
        {canEdit && !form && (
          <div className="guided-views-controls">
            <button type="button" disabled={busy || selectedProcessIds.length === 0 || tooManySelected || chapters.length >= MAX_CHAPTERS}
              title={chapters.length >= MAX_CHAPTERS ? `A workflow takes at most ${MAX_CHAPTERS} chapters`
                : selectedProcessIds.length === 0 ? 'Select the processes for the chapter on the canvas first'
                  : tooManySelected ? `A chapter shows at most ${MAX_PROCESSES} processes` : undefined}
              onClick={() => setForm({ mode: 'new', label: `Chapter ${chapters.length + 1}`, note: '' })}>
              New chapter from {count(selectedProcessIds.length, 'selected process', 'selected processes')}
            </button>
            {active && (
              <>
                <button type="button" disabled={busy}
                  onClick={() => setForm({ mode: 'edit', label: active.label, note: active.note ?? '' })}>Edit</button>
                <button type="button" disabled={busy || selectedProcessIds.length === 0 || tooManySelected}
                  title="Show the processes selected on the canvas instead"
                  onClick={() => void run(() => update.mutateAsync({ guidedViewId: active.id, version: active.version,
                    label: active.label, note: active.note, focusProcessIds: [...selectedProcessIds] }),
                  `Chapter ${active.label} now shows ${count(selectedProcessIds.length, 'process', 'processes')}`)}>
                  Use selection
                </button>
                <button type="button" aria-label="Move chapter up" disabled={busy || activeIndex <= 0}
                  onClick={() => {
                    const ids = chapters.map((chapter) => chapter.id)
                    ;[ids[activeIndex - 1], ids[activeIndex]] = [ids[activeIndex], ids[activeIndex - 1]]
                    void run(() => reorder.mutateAsync(ids), `Moved ${active.label} up`)
                  }}>&#8593;</button>
                <button type="button" aria-label="Move chapter down" disabled={busy || activeIndex >= chapters.length - 1}
                  onClick={() => {
                    const ids = chapters.map((chapter) => chapter.id)
                    ;[ids[activeIndex + 1], ids[activeIndex]] = [ids[activeIndex], ids[activeIndex + 1]]
                    void run(() => reorder.mutateAsync(ids), `Moved ${active.label} down`)
                  }}>&#8595;</button>
                {confirmDelete ? (
                  <>
                    <button type="button" disabled={busy} onClick={() => {
                      const label = active.label
                      void run(() => remove.mutateAsync(active.id), `Deleted chapter ${label}`).then((done) => {
                        if (done) onClear()
                      })
                    }}>Delete {active.label}</button>
                    <button type="button" onClick={() => setConfirmDelete(false)}>Keep</button>
                  </>
                ) : (
                  <button type="button" disabled={busy} onClick={() => setConfirmDelete(true)}>Delete</button>
                )}
              </>
            )}
          </div>
        )}
        {form && (
          <form className="guided-views-form" aria-label={form.mode === 'new' ? 'New chapter' : 'Edit chapter'}
            onSubmit={(event) => {
              event.preventDefault()
              void submit()
            }}>
            <label>
              <span>Name</span>
              <input value={form.label} maxLength={48} required autoFocus
                onChange={(event) => setForm({ ...form, label: event.target.value })} />
            </label>
            <label>
              <span>Note</span>
              <textarea value={form.note} maxLength={140} rows={2}
                onChange={(event) => setForm({ ...form, note: event.target.value })} />
            </label>
            {form.mode === 'new' && (
              <small>Shows {selectedProcessIds.map(nameOf).join(', ')}, in that order.</small>
            )}
            <div className="guided-views-controls">
              <button type="submit" disabled={busy || !form.label.trim()}>{form.mode === 'new' ? 'Add chapter' : 'Save'}</button>
              <button type="button" disabled={busy} onClick={() => setForm(null)}>Cancel</button>
            </div>
          </form>
        )}
        {message && (
          <p role={message.error ? 'alert' : 'status'} className="guided-views-message" data-error={message.error || undefined}>
            {message.text}
          </p>
        )}
      </div>
    </section>
  )
}
