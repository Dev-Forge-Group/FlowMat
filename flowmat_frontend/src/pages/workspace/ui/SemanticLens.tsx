import { useEffect, useRef, useState } from 'react'

/**
 * archify's Semantic Lens (adapted from archify semantic-lens.js and template.source.html, Apache-2.0): each kind on the
 * canvas is a swatch with its count; one kind reveals its real traffic, two compare only the direct connections between
 * them, and a third waits (disabled) until one is turned off. The receipt, Copy link and Clear sit below. Esc or the
 * close button puts focus back where it was; the lens itself stays.
 */
export function SemanticLens({ kinds, chosen, status, onToggle, onClear, onClose }: {
  kinds: { kind: string; label: string; count: number }[]
  chosen: readonly string[]
  /** What the lens shows, while it shows something. */
  status: string | null
  onToggle(kind: string): void
  onClear(): void
  onClose(): void
}) {
  const panelRef = useRef<HTMLDivElement>(null)
  const openerRef = useRef<HTMLElement | null>(null)
  const [copied, setCopied] = useState<'copied' | 'failed' | null>(null)

  useEffect(() => {
    openerRef.current = document.activeElement as HTMLElement | null
    // The first kind that can be pressed, else the close button. One selector list would match in document order, and
    // the close button comes first.
    const panel = panelRef.current
    ;(panel?.querySelector<HTMLButtonElement>('.semantic-lens-kind:not(:disabled)')
      ?? panel?.querySelector<HTMLButtonElement>('.semantic-lens-close'))?.focus()
  }, [])

  function close() {
    openerRef.current?.focus?.()
    onClose()
  }

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(window.location.href)
      setCopied('copied')
    } catch {
      setCopied('failed')
    }
    window.setTimeout(() => setCopied(null), 1500)
  }

  return (
    <div ref={panelRef} className="semantic-lens" role="dialog" aria-modal="false" aria-labelledby="semantic-lens-title"
      onKeyDown={(event) => {
        if (event.key !== 'Escape') return
        event.preventDefault()
        event.stopPropagation()
        close()
      }}>
      <div className="semantic-lens-head">
        <span>
          <span className="semantic-lens-eyebrow">Semantic lens</span>
          <strong className="semantic-lens-title" id="semantic-lens-title">Compare process kinds</strong>
        </span>
        <button type="button" className="semantic-lens-close" aria-label="Close semantic lens" onClick={close}>&#215;</button>
      </div>
      <p className="semantic-lens-instruction">
        Choose up to two kinds. One reveals its real traffic; two compare only direct connections.
      </p>
      <div className="semantic-lens-kinds" role="group" aria-label="Process kinds">
        {kinds.map((entry) => {
          const on = chosen.includes(entry.kind)
          return (
            <button key={entry.kind} type="button" className="semantic-lens-kind" data-kind={entry.kind} aria-pressed={on}
              aria-label={`${entry.label}, ${entry.count} ${entry.count === 1 ? 'process' : 'processes'}`}
              disabled={!on && chosen.length >= 2} onClick={() => onToggle(entry.kind)}>
              <span className="semantic-lens-swatch" aria-hidden="true" />
              <strong>{entry.label}</strong>
              <em>{entry.count}</em>
            </button>
          )
        })}
      </div>
      <p className="semantic-lens-status" role="status">
        {kinds.length === 0 ? 'There are no processes yet.'
          : status ?? 'Choose a kind to inspect its processes and touching connections.'}
      </p>
      <div className="semantic-lens-actions">
        {chosen.length > 0 && (
          <button type="button" className="semantic-lens-copy"
            aria-label={copied === 'copied' ? 'Copied' : copied === 'failed' ? 'Copy failed' : 'Copy link to semantic lens'}
            onClick={() => void copyLink()}>
            {copied === 'copied' ? 'Copied' : copied === 'failed' ? 'Copy failed' : 'Copy link'}
          </button>
        )}
        <button type="button" aria-label="Clear semantic lens" disabled={chosen.length === 0} onClick={onClear}>Clear</button>
      </div>
    </div>
  )
}
