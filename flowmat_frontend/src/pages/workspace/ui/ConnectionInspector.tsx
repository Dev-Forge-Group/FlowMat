import { useEffect, useRef, useState } from 'react'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { splitInput, type SplitKind } from '../model/splitShare'
import { lossPercentText, lossRateInput } from '../model/lossPercent'
import type {
  CanvasEdgeViewModel,
  FlowRuleViewModel,
  UpdateProcessConnectionInput,
  RuleTargetInput,
} from '../../../entities/workflow/model/types'

interface Props {
  edge: CanvasEdgeViewModel | null
  rules: FlowRuleViewModel[]
  onSubmit(input: UpdateProcessConnectionInput): Promise<void>
  onDelete(connectionId: string): Promise<void>
  onOpenRuleBuilder(target: RuleTargetInput): void
  focusLabel?: boolean
  /** The connection leaves an Exclusive gateway, so it can be the gateway's default (ADR-006). */
  sourceIsExclusive?: boolean
  /** The connection leaves a Split gateway, so it takes a share of what the Split divides (ADR-008). */
  sourceIsSplit?: boolean
}

const CONNECTION_TYPE_OPTIONS = [
  'material',
  'material_flow',
  'fluid_flow',
  'energy_flow',
  'data_flow',
  'event_flow',
  'control_flow',
  'human_flow',
  'equipment_flow',
]

export function ConnectionInspector({ edge, onSubmit, onDelete, focusLabel, sourceIsExclusive = false, sourceIsSplit = false }: Props) {
  const labelRef = useRef<HTMLInputElement>(null)
  const [label, setLabel] = useState('')
  const [connectionType, setConnectionType] = useState('material')
  const [flowRate, setFlowRate] = useState('')
  const [unit, setUnit] = useState('')
  const [delayTimeSec, setDelayTimeSec] = useState('')
  const [lossRate, setLossRate] = useState('')
  const [priority, setPriority] = useState('')
  const [conditionExpr, setConditionExpr] = useState('')
  const [capacity, setCapacity] = useState('')
  const [failurePolicy, setFailurePolicy] = useState<'stop' | 'skip' | 'retry'>('stop')
  const [defaultRoute, setDefaultRoute] = useState(false)
  const [splitKind, setSplitKind] = useState<SplitKind | ''>('')
  const [splitValue, setSplitValue] = useState('')
  const [saveError, setSaveError] = useState<string | null>(null)

  useEffect(() => {
    if (focusLabel) setTimeout(() => labelRef.current?.focus(), 50)
  }, [focusLabel, edge?.id])

  useEffect(() => {
    if (!edge) return
    setSaveError(null)
    setLabel(edge.label ?? '')
    setConnectionType(edge.connectionType ?? 'material')
    setFlowRate(edge.flowRate ?? '')
    setUnit(edge.unit ?? '')
    setDelayTimeSec(edge.delayTimeSec !== null ? String(edge.delayTimeSec) : '')
    setLossRate(lossPercentText(edge.lossRate))
    setPriority(edge.priority !== null ? String(edge.priority) : '')
    setConditionExpr(edge.conditionExpr ?? '')
    setCapacity(edge.capacity !== null ? String(edge.capacity) : '')
    setFailurePolicy(edge.failurePolicy)
    setDefaultRoute(edge.defaultRoute ?? false)
    setSplitKind(edge.splitKind ?? '')
    setSplitValue(edge.splitValue != null ? String(edge.splitValue) : '')
  }, [edge])

  if (!edge) return null
  const currentEdge = edge

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setSaveError(null)
    const split = splitInput(splitKind, splitValue)
    if (sourceIsSplit && 'error' in split) {
      setSaveError(split.error)
      return
    }
    const loss = lossRateInput(lossRate)
    if ('error' in loss) {
      setSaveError(loss.error)
      return
    }
    // A connection's delay schedules the next step (docs/architecture/adr/ADR-010-connection-delay.md decision 8).
    if (delayTimeSec.trim() !== '' && !(Number(delayTimeSec) >= 0)) {
      setSaveError('Delay is 0 or more seconds.')
      return
    }
    try {
      await onSubmit({
      connectionId: currentEdge.id,
      connectionLabel: label.trim() || null,
      connectionType: connectionType.trim(),
      flowRate: flowRate !== '' ? Number(flowRate) : null,
      unit: unit.trim() || null,
      delayTimeSec: delayTimeSec !== '' ? Number(delayTimeSec) : null,
      lossRate: loss.lossRate,
      priority: priority !== '' ? Number(priority) : null,
      conditionExpr: conditionExpr.trim(),
      capacity: capacity !== '' ? Number(capacity) : null,
      clearCapacity: capacity === '',
      failurePolicy,
      ...(sourceIsExclusive ? { defaultRoute } : {}),
      ...(sourceIsSplit && !('error' in split) ? split : {}),
      })
    } catch (error) {
      setSaveError(errorMessage(error, 'Could not save connection.'))
    }
  }

  return (
    <div className="inspector">
      <h3 className="inspector__title">{currentEdge.label || 'Connection'}</h3>
      <dl className="inspector__fields">
        <dt>From</dt>
        <dd style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>{currentEdge.fromProcessId}</dd>
        <dt>To</dt>
        <dd style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>{currentEdge.toProcessId}</dd>
      </dl>

      <form onSubmit={handleSubmit} style={{ display: 'grid', gap: '10px', marginTop: '16px' }}>
        <label style={{ display: 'grid', gap: '4px' }}>
          <span>Label</span>
          <input
            ref={labelRef}
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="(none)"
          />
        </label>

        <label style={{ display: 'grid', gap: '4px' }}>
          <span>Connection Type</span>
          <select value={connectionType} onChange={(e) => setConnectionType(e.target.value)}>
            {CONNECTION_TYPE_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
          <label style={{ display: 'grid', gap: '4px' }}>
            <span>Flow Rate</span>
            <input
              type="number"
              step="0.01"
              value={flowRate}
              onChange={(e) => setFlowRate(e.target.value)}
              placeholder="optional"
            />
          </label>
          <label style={{ display: 'grid', gap: '4px' }}>
            <span>Unit</span>
            <input
              value={unit}
              onChange={(e) => setUnit(e.target.value)}
              placeholder="optional"
            />
          </label>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '10px' }}>
          <label style={{ display: 'grid', gap: '4px' }}>
            <span>Delay (s)</span>
            <input
              type="number"
              step="0.1"
              value={delayTimeSec}
              onChange={(e) => setDelayTimeSec(e.target.value)}
              placeholder="0"
            />
          </label>
          <label style={{ display: 'grid', gap: '4px' }}>
            <span>Loss %</span>
            <input
              type="number"
              step="0.01"
              min="0"
              value={lossRate}
              onChange={(e) => setLossRate(e.target.value)}
              placeholder="0"
            />
          </label>
          <label style={{ display: 'grid', gap: '4px' }}>
            <span>Priority</span>
            <input
              type="number"
              step="1"
              value={priority}
              onChange={(e) => setPriority(e.target.value)}
              placeholder="0"
            />
          </label>
        </div>

        <label style={{ display: 'grid', gap: '4px' }}>
          <span>Condition expression</span>
          <input value={conditionExpr} onChange={(e) => setConditionExpr(e.target.value)} />
        </label>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
          <label style={{ display: 'grid', gap: '4px' }}>
            <span>Capacity</span>
            <input type="number" min="0" step="0.01" value={capacity} onChange={(e) => setCapacity(e.target.value)} placeholder="optional" />
          </label>
          <label style={{ display: 'grid', gap: '4px' }}>
            <span>Failure policy (metadata)</span>
            <select value={failurePolicy} onChange={(e) => setFailurePolicy(e.target.value as 'stop' | 'skip' | 'retry')}>
              <option value="stop">stop</option>
              <option value="skip">skip</option>
              <option value="retry">retry</option>
            </select>
          </label>
        </div>

        {sourceIsExclusive && (
          <div style={{ display: 'grid', gap: '4px' }}>
            <label>
              <input type="checkbox" checked={defaultRoute} onChange={(e) => setDefaultRoute(e.target.checked)} /> Default route
            </label>
            <span className="inspector-hint">
              This gateway tries its connections by priority, lowest first, and takes the first whose condition holds; the
              default route is taken when none holds. Keep one default at most.
            </span>
          </div>
        )}

        {sourceIsSplit && (
          <div style={{ display: 'grid', gap: '4px' }}>
            <label style={{ display: 'grid', gap: '4px' }}>
              <span>Share</span>
              <select aria-label="Split share" value={splitKind} onChange={(e) => setSplitKind(e.target.value as SplitKind | '')}>
                <option value="">(none)</option>
                <option value="share">Percent</option>
                <option value="fixed">Fixed quantity</option>
                <option value="rest">The rest</option>
              </select>
            </label>
            {(splitKind === 'share' || splitKind === 'fixed') && (
              <label style={{ display: 'grid', gap: '4px' }}>
                <span>{splitKind === 'share' ? 'Percent' : 'Quantity'}</span>
                <input aria-label="Split value" type="number" min="0" step="any" value={splitValue}
                  onChange={(e) => setSplitValue(e.target.value)} />
              </label>
            )}
            <span className="inspector-hint">
              The Split divides what arrives: fixed quantities and percents of the whole go first, and the rest takes what is
              left. Without a rest, the percents must add up to 100.
            </span>
          </div>
        )}

        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
          {saveError && <p role="alert" style={{ color: '#991b1b' }}>{saveError}</p>}
          <button type="submit">Save Connection</button>
          <button
            type="button"
            onClick={() => void onDelete(currentEdge.id)}
            style={{ border: '1px solid #fca5a5', color: '#991b1b', background: '#fef2f2' }}
          >
            Delete Connection
          </button>
        </div>
      </form>
    </div>
  )
}
