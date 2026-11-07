import { useState } from 'react'
import { getWorkflowNodeDefinition } from '../../../entities/workflow/model/nodeCatalog'
import type { CanvasNodeViewModel } from '../../../entities/workflow/model/types'
import type { CanvasReading } from '../model/useCanvasReading'
import { formatReadingHash, withViewParams } from '../model/readingHash'
import { ProcessConnections } from './ProcessConnections'

/**
 * archify's Semantic Passport (adapted from archify focus.js and template.source.html, Apache-2.0): one compact surface
 * for the process in hand, with its name, kind and stable id, its authored reach upstream and downstream with counts,
 * the reach receipt, and its connected relationships. The single place for a process's reading details.
 */
export function SemanticPassport({ reading, node, onClose, onFollow }: {
  reading: CanvasReading
  node: CanvasNodeViewModel
  onClose(): void
  /** Goes to the process at the other end of a relationship. */
  onFollow(processId: string): void
}) {
  const [copied, setCopied] = useState<'copied' | 'failed' | null>(null)
  const processId = node.processId
  const counts = reading.counts(processId)
  const relations = reading.relationsOf(processId)
  const state = reading.state
  const reach = state?.kind === 'focus' && state.processId === processId ? state.reach : undefined
  const receipt = reach ? reading.view?.text ?? null : null
  const kind = getWorkflowNodeDefinition(node.nodeType).label

  function toggleReach(direction: 'upstream' | 'downstream') {
    if (reach === direction) reading.focus(processId)
    else reading.focus(processId, direction)
  }

  async function copyLink() {
    const hash = state?.kind === 'focus' && state.processId === processId
      ? window.location.hash
      : withViewParams(formatReadingHash({ kind: 'focus', processId, reach: null }), reading.run.id, reading.changes.id)
    const url = `${window.location.origin}${window.location.pathname}${window.location.search}${hash}`
    try {
      await navigator.clipboard.writeText(url)
      setCopied('copied')
    } catch {
      setCopied('failed')
    }
    window.setTimeout(() => setCopied(null), 1500)
  }

  return (
    <div className="focus-chip relationship-lens" role="region" aria-labelledby="semantic-passport-title">
      <div className="relationship-lens-head">
        <div className="relationship-lens-copy">
          <span className="relationship-lens-eyebrow">Semantic passport</span>
          <strong className="relationship-lens-title" id="semantic-passport-title">{node.name}</strong>
          {node.description && <span className="semantic-passport-detail">{node.description}</span>}
          <div className="semantic-passport-meta" aria-label="Process metadata">
            <span data-passport="kind">{kind}</span>
            <code data-passport="id">{processId}</code>
          </div>
          <span className="relationship-lens-summary">
            {relations.out.length} outgoing · {relations.in.length} incoming
            {relations.loop.length > 0 ? ` · ${relations.loop.length} loop` : ''}
          </span>
          <div className="semantic-passport-reach">
            <span className="semantic-passport-reach-label">Authored reach</span>
            <div className="semantic-passport-reach-actions" role="group" aria-label="Trace authored reachability">
              <button type="button" data-reach="upstream" aria-pressed={reach === 'upstream'} disabled={counts.upstream === 0}
                aria-label={counts.upstream === 0 ? 'No upstream processes'
                  : `Trace ${counts.upstream} upstream ${counts.upstream === 1 ? 'process' : 'processes'}`}
                onClick={() => toggleReach('upstream')}>
                <span>Upstream</span><strong>{counts.upstream}</strong>
              </button>
              <button type="button" data-reach="downstream" aria-pressed={reach === 'downstream'} disabled={counts.downstream === 0}
                aria-label={counts.downstream === 0 ? 'No downstream processes'
                  : `Trace ${counts.downstream} downstream ${counts.downstream === 1 ? 'process' : 'processes'}`}
                onClick={() => toggleReach('downstream')}>
                <span>Downstream</span><strong>{counts.downstream}</strong>
              </button>
            </div>
            {receipt && <small className="semantic-passport-reach-status" role="status">{receipt}</small>}
          </div>
        </div>
        <div className="relationship-lens-actions">
          <button type="button" className="relationship-lens-close" aria-label="Close semantic passport" title="Close"
            onClick={onClose}>&#215;</button>
          <button type="button" className="relationship-lens-copy-link" aria-label="Copy link to focused process"
            onClick={() => void copyLink()}>
            {copied === 'copied' ? 'Copied' : copied === 'failed' ? 'Copy failed' : 'Copy link'}
          </button>
        </div>
      </div>
      <ProcessConnections key={`passport-${processId}`} relations={relations} nameOf={reading.nameOf}
        onPreview={reading.previewConnection} onFollow={onFollow} />
    </div>
  )
}
