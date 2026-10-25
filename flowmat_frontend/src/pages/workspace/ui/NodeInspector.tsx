import { useEffect, useMemo, useState } from 'react'
import { ExecutionPolicySection } from './ExecutionPolicySection'
import { ProcessExecutionBindingSection } from './ProcessExecutionBindingSection'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { useItemsQuery } from '../../../entities/catalog/api/useItemsQuery'
import { getWorkflowNodeDefinition, getWorkflowPaletteDefinitions } from '../../../entities/workflow/model/nodeCatalog'
import {
  applyItemDefaults,
  createDefaultPortFormState,
  hasValidPortSelection,
  portNeedsMeasure,
  isValidSchemaJson,
  toCreateProcessIoInput,
  toPortFormState,
  toUpdateProcessIoInput,
  type PortFormState,
} from '../../../entities/workflow/model/portPolicy'
import type {
  CanvasNodeViewModel,
  CanvasPortViewModel,
  FlowRuleViewModel,
  UpdateProcessInput,
  CreateProcessIoInput,
  UpdateProcessIoInput,
  RuleTargetInput,
} from '../../../entities/workflow/model/types'

interface Props {
  node: CanvasNodeViewModel | null
  selectedPort: CanvasPortViewModel | null
  rules: FlowRuleViewModel[]
  onNodeSubmit(input: UpdateProcessInput): Promise<void>
  onNodeDelete(processId: string): Promise<void>
  onPortCreate(input: CreateProcessIoInput): Promise<void>
  onPortUpdate(input: UpdateProcessIoInput): Promise<void>
  onPortDelete(processIoId: string): Promise<void>
  onOpenRuleBuilder(target: RuleTargetInput): void
}

const COLOR_OPTIONS = ['slate', 'sky', 'emerald', 'amber', 'violet', 'rose', 'gray']
const IO_TYPE_OPTIONS = ['material', 'signal', 'energy', 'labor', 'data']

export function NodeInspector({
  node,
  selectedPort,
  onNodeSubmit,
  onNodeDelete,
  onPortCreate,
  onPortUpdate,
  onPortDelete,
}: Props) {
  const paletteDefinitions = useMemo(() => getWorkflowPaletteDefinitions(), [])
  const [processName, setProcessName] = useState('')
  const [nodeType, setNodeType] = useState('process')
  const [colorScheme, setColorScheme] = useState('slate')
  const [processDesc, setProcessDesc] = useState('')
  const [portForm, setPortForm] = useState<PortFormState>(createDefaultPortFormState())
  const [editingPortId, setEditingPortId] = useState<string | null>(null)
  const [portError, setPortError] = useState<string | null>(null)

  const projectId = node?.projectId ?? ''
  const itemsQuery = useItemsQuery(projectId)
  const itemOptions = itemsQuery.data ?? []
  const allPorts = useMemo(() => (node ? [...node.inputs, ...node.outputs] : []), [node])

  useEffect(() => {
    if (!node) return
    setProcessName(node.name)
    setNodeType(node.nodeType)
    setColorScheme(node.colorScheme)
    setProcessDesc(node.description ?? '')
  }, [node])

  useEffect(() => {
    if (!node) {
      setEditingPortId(null)
      setPortForm(createDefaultPortFormState())
      return
    }

    const preferredPort =
      (selectedPort && selectedPort.processId === node.id ? selectedPort : null) ?? allPorts[0] ?? null

    if (preferredPort) {
      setEditingPortId(preferredPort.processIoId)
      setPortForm(toPortFormState(preferredPort))
      return
    }

    setEditingPortId(null)
    setPortForm(createDefaultPortFormState())
  }, [node, selectedPort, allPorts])

  if (!node) return null

  const selectedDefinition = getWorkflowNodeDefinition(nodeType)

  async function handleNodeSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()

    await onNodeSubmit({
      processId: node!.id,
      processName: processName.trim(),
      nodeType: selectedDefinition.nodeType,
      processType: selectedDefinition.processType,
      colorScheme: colorScheme.trim().toLowerCase(),
      processDesc: processDesc.trim() || undefined,
    })
  }

  async function handlePortSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!hasValidPortSelection(portForm)) return
    setPortError(null)
    try {
      if (editingPortId) {
        await onPortUpdate(toUpdateProcessIoInput(portForm))
      } else {
        await onPortCreate(toCreateProcessIoInput(node!.id, portForm))
      }
    } catch (error) {
      setPortError(errorMessage(error, 'Could not save port.'))
    }
  }

  async function handlePortDelete() {
    if (!editingPortId) return
    await onPortDelete(editingPortId)
    setEditingPortId(null)
    setPortForm(createDefaultPortFormState())
  }

  function selectPortForEdit(port: CanvasPortViewModel) {
    setPortError(null)
    setEditingPortId(port.processIoId)
    setPortForm(toPortFormState(port))
  }

  function startNewPort(direction: 'input' | 'output') {
    setPortError(null)
    setEditingPortId(null)
    setPortForm(createDefaultPortFormState(direction))
  }

  function handleItemChange(itemId: string) {
    const nextItem = itemOptions.find((item) => item.itemId === itemId)
    setPortForm((current) => applyItemDefaults({ ...current, itemId }, nextItem))
  }

  return (
    <div className="inspector">
      <h3 className="inspector__title">{node.name}</h3>
      <dl className="inspector__fields">
        <dt>Type</dt>
        <dd>{node.nodeType}</dd>
        <dt>Status</dt>
        <dd>{node.status}</dd>
        <dt>Color</dt>
        <dd>{node.colorScheme}</dd>
        <dt>Position</dt>
        <dd>
          x {node.position.x.toFixed(0)} | y {node.position.y.toFixed(0)}
        </dd>
      </dl>

      <form
        onSubmit={handleNodeSubmit}
        style={{ display: 'grid', gap: '10px', marginTop: '16px', marginBottom: '16px' }}
      >
        <label style={{ display: 'grid', gap: '4px' }}>
          <span>Name</span>
          <input value={processName} onChange={(event) => setProcessName(event.target.value)} />
        </label>

        <label style={{ display: 'grid', gap: '4px' }}>
          <span>Node Type</span>
          <select value={nodeType} onChange={(event) => setNodeType(event.target.value)}>
            {paletteDefinitions.map((definition) => (
              <option key={definition.nodeType} value={definition.nodeType}>
                {definition.label}
              </option>
            ))}
          </select>
        </label>

        <label style={{ display: 'grid', gap: '4px' }}>
          <span>Color</span>
          <select value={colorScheme} onChange={(event) => setColorScheme(event.target.value)}>
            {COLOR_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>

        <label style={{ display: 'grid', gap: '4px' }}>
          <span>Description</span>
          <textarea
            rows={4}
            value={processDesc}
            onChange={(event) => setProcessDesc(event.target.value)}
          />
        </label>

        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
          <button type="submit">Save Node</button>
          <button
            type="button"
            onClick={() => void onNodeDelete(node.id)}
            style={{
              border: '1px solid #fca5a5',
              color: '#991b1b',
              background: '#fef2f2',
            }}
          >
            Delete Node
          </button>
        </div>
      </form>

      <section className="inspector__section">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h4 style={{ margin: 0 }}>Ports</h4>
          <div style={{ display: 'flex', gap: '8px' }}>
            <button type="button" onClick={() => startNewPort('input')}>
              Add Input
            </button>
            <button type="button" onClick={() => startNewPort('output')}>
              Add Output
            </button>
          </div>
        </div>

        {allPorts.length === 0 ? (
          <p className="panel-placeholder" style={{ marginTop: '10px' }}>
            No ports yet. Add one to create explicit connection handles.
          </p>
        ) : (
          <div style={{ display: 'grid', gap: '8px', marginTop: '10px' }}>
            {allPorts.map((port) => (
              <button
                key={port.id}
                type="button"
                onClick={() => selectPortForEdit(port)}
                className={`inspector__port-row ${editingPortId === port.processIoId ? 'inspector__port-row--selected' : ''}`}
                style={{ textAlign: 'left' }}
              >
                <span className="inspector__port-name">{port.name}</span>
                <span className="inspector__port-unit">{port.direction}</span>
                {port.unit && <span className="inspector__port-unit">{port.unit}</span>}
                {port.required && <span className="inspector__port-badge">required</span>}
              </button>
            ))}
          </div>
        )}
      </section>

      <section className="inspector__section" style={{ marginTop: '16px' }}>
        <h4 style={{ marginTop: 0 }}>{editingPortId ? 'Edit Port' : 'Create Port'}</h4>
        {itemsQuery.isLoading ? (
          <p className="panel-placeholder">Loading project items...</p>
        ) : (
          <form onSubmit={handlePortSubmit} style={{ display: 'grid', gap: '10px' }}>
            <label style={{ display: 'grid', gap: '4px' }}>
              <span>Direction</span>
              <select
                value={portForm.direction}
                onChange={(event) =>
                  setPortForm((current) => ({
                    ...current,
                    direction: event.target.value as 'input' | 'output',
                    colorScheme:
                      event.target.value === 'input'
                        ? current.colorScheme === 'emerald'
                          ? 'sky'
                          : current.colorScheme
                        : current.colorScheme === 'sky'
                          ? 'emerald'
                          : current.colorScheme,
                  }))
                }
              >
                <option value="input">input</option>
                <option value="output">output</option>
              </select>
            </label>

            <label style={{ display: 'grid', gap: '4px' }}>
              <span>Item</span>
              <select value={portForm.itemId} onChange={(event) => handleItemChange(event.target.value)}>
                <option value="">No item (data, file, API…)</option>
                {itemOptions.map((item) => (
                  <option key={item.itemId} value={item.itemId}>
                    {item.itemName} ({item.itemCode})
                  </option>
                ))}
              </select>
              {itemOptions.length === 0 && (
                <span className="inspector-hint">This project has no catalog items; the port describes what flows by resource type and schema.</span>
              )}
            </label>

            <label style={{ display: 'grid', gap: '4px' }}>
              <span>Port Name</span>
              <input maxLength={100} value={portForm.ioName} onChange={(event) => setPortForm((current) => ({ ...current, ioName: event.target.value }))} />
            </label>

            <label style={{ display: 'grid', gap: '4px' }}>
              <span>I/O Type</span>
              <select value={portForm.ioType} onChange={(event) => setPortForm((current) => ({
                ...current,
                ioType: event.target.value,
                resourceType: current.resourceType === current.ioType ? event.target.value : current.resourceType,
              }))}>
                {IO_TYPE_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
              <label style={{ display: 'grid', gap: '4px' }}>
                <span>Role</span>
                <input maxLength={50} value={portForm.role} onChange={(event) => setPortForm((current) => ({ ...current, role: event.target.value }))} placeholder="feed, product..." />
              </label>
              <label style={{ display: 'grid', gap: '4px' }}>
                <span>Resource Type</span>
                <input maxLength={50} value={portForm.resourceType} onChange={(event) => setPortForm((current) => ({ ...current, resourceType: event.target.value }))} />
              </label>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
              <label style={{ display: 'grid', gap: '4px' }}>
                <span>Quantity</span>
                <input
                  type="number"
                  min="0"
                  step="0.0001"
                  required={portNeedsMeasure(portForm)}
                  value={portForm.quantity}
                  onChange={(event) => setPortForm((current) => ({ ...current, quantity: event.target.value }))}
                />
              </label>

              <label style={{ display: 'grid', gap: '4px' }}>
                <span>Unit</span>
                <input maxLength={20} required={portNeedsMeasure(portForm)} value={portForm.unit} onChange={(event) => setPortForm((current) => ({ ...current, unit: event.target.value }))} />
              </label>
            </div>
            <small className="inspector-hint">
              {portNeedsMeasure(portForm)
                ? 'Material, product and item ports need a quantity and unit.'
                : 'Quantity and unit are optional for this resource type; a quantity needs a unit.'}
            </small>

            <label style={{ display: 'grid', gap: '4px' }}>
              <span>Formula</span>
              <input value={portForm.formula} onChange={(event) => setPortForm((current) => ({ ...current, formula: event.target.value }))} />
            </label>

            <label style={{ display: 'grid', gap: '4px' }}>
              <span>Data schema (JSON object contract)</span>
              <textarea value={portForm.schemaJson} onChange={(event) => setPortForm((current) => ({ ...current, schemaJson: event.target.value }))} rows={4} aria-invalid={!isValidSchemaJson(portForm.schemaJson)} />
              {!isValidSchemaJson(portForm.schemaJson) && <small role="alert">Enter an object schema with valid properties and required names.</small>}
            </label>

            <label style={{ display: 'grid', gap: '4px' }}>
              <span>Validation rule</span>
              <input maxLength={2000} value={portForm.validationRule} onChange={(event) => setPortForm((current) => ({ ...current, validationRule: event.target.value }))} />
            </label>

            <label style={{ display: 'grid', gap: '4px' }}>
              <span>Color</span>
              <select value={portForm.colorScheme} onChange={(event) => setPortForm((current) => ({ ...current, colorScheme: event.target.value }))}>
                {COLOR_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
              <label style={{ display: 'grid', gap: '4px' }}>
                <span>Required</span>
                <select value={portForm.requiredYn} onChange={(event) => setPortForm((current) => ({ ...current, requiredYn: event.target.value as 'Y' | 'N' }))}>
                  <option value="Y">Y</option>
                  <option value="N">N</option>
                </select>
              </label>

              <label style={{ display: 'grid', gap: '4px' }}>
                <span>Allow Shortage</span>
                <select value={portForm.allowShortageYn} onChange={(event) => setPortForm((current) => ({ ...current, allowShortageYn: event.target.value as 'Y' | 'N' }))}>
                  <option value="N">N</option>
                  <option value="Y">Y</option>
                </select>
              </label>
            </div>

            <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
              {portError && <p role="alert" style={{ color: '#991b1b' }}>{portError}</p>}
              <button type="submit" disabled={!hasValidPortSelection(portForm)}>
                {editingPortId ? 'Save Port' : 'Create Port'}
              </button>
              {editingPortId && (
                <button
                  type="button"
                  onClick={() => void handlePortDelete()}
                  style={{
                    border: '1px solid #fca5a5',
                    color: '#991b1b',
                    background: '#fef2f2',
                  }}
                >
                  Delete Port
                </button>
              )}
            </div>
          </form>
        )}
      </section>

      <ExecutionPolicySection key={node.id} processId={node.id} />
      {!['exclusive', 'join', 'split'].includes(node.nodeType.toLowerCase()) && <ProcessExecutionBindingSection
        key={`http-${node.id}`} processId={node.id} projectId={node.projectId} outputs={node.outputs} />}
    </div>
  )
}
