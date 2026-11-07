import { describe, expect, it } from 'vitest'
import { toEdgeViewModel, toNodeViewModel } from '../../../entities/workflow/model/toWorkflowCanvasViewModel'
import type { ProcessConnectionDto, ProcessDto, ProcessIoDto } from '../../../shared/types/api'
import { changesSummary, revisionChanges } from './revisionChanges'

const process = (processId: string, processName: string, extra: Partial<ProcessDto> = {}): ProcessDto => ({ processId,
  projectId: 'prj', workflowId: 'wf', processName, processType: 'process', nodeType: 'process', processStatus: 'ready',
  colorScheme: 'slate', posX: 0, posY: 0, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1, ...extra })
const port = (processIoId: string, processId: string, extra: Partial<ProcessIoDto> = {}): ProcessIoDto => ({ processIoId,
  processId, itemId: null, ioName: 'Feed', direction: 'input', ioType: 'material', quantity: 1, unit: 'kg', formula: null,
  colorScheme: null, requiredYn: 'Y', allowShortageYn: 'N', role: null, resourceType: 'material', schemaJson: null,
  validationRule: null, ...extra })
const connection = (connectionId: string, fromProcessId: string, toProcessId: string,
  extra: Partial<ProcessConnectionDto> = {}): ProcessConnectionDto => ({ connectionId, projectId: 'prj', workflowId: 'wf',
  fromProcessId, toProcessId, fromIoId: null, toIoId: null, sourceHandle: 'out', targetHandle: 'in',
  connectionType: 'material_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: 0, lossRate: 0,
  priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', version: 1, versionNonce: 1, ...extra })

/** The canvas as the page has it: the same mapping the editor uses. */
const draftOf = (processes: ProcessDto[], ports: ProcessIoDto[], connections: ProcessConnectionDto[]) => ({
  nodes: processes.map((dto) => toNodeViewModel(dto, ports.filter((io) => io.processId === dto.processId))),
  edges: connections.map(toEdgeViewModel),
})

describe('revision changes', () => {
  // v3: Mix → Bake → Wash. Draft: Mix only moved; Bake's port quantity changed; Wash gone; Pack added after Bake.
  const snapshot = {
    processes: [process('mix', 'Mix'), process('bake', 'Bake'), process('wash', 'Wash')],
    processIos: [port('bake-in', 'bake')],
    connections: [connection('mb', 'mix', 'bake'), connection('bw', 'bake', 'wash')],
  }
  const draft = draftOf(
    [process('mix', 'Mix', { posX: 300, version: 4, versionNonce: 9 }), process('bake', 'Bake'),
      process('pack', 'Pack', { nodeType: 'output' })],
    [port('bake-in', 'bake', { quantity: 2 })],
    [connection('mb', 'mix', 'bake', { lossRate: 0.05, sourceHandle: 'right' }), connection('bp', 'bake', 'pack')])

  it('marks added and changed processes and connections by id, and names what is gone', () => {
    const changes = revisionChanges(draft, snapshot)!
    expect([...changes.processes]).toEqual([['bake', 'changed'], ['pack', 'added']])
    expect(changes.fields.get('bake')).toEqual(['ports'])
    expect([...changes.connections]).toEqual([['mb', 'changed'], ['bp', 'added']])
    expect(changes.fields.get('mb')).toEqual(['lossRate'])
    expect(changes.removedProcesses).toEqual([{ processId: 'wash', name: 'Wash' }])
    expect(changes.removedConnections).toEqual([{ connectionId: 'bw', text: 'Bake → Wash' }])
    expect(changesSummary(3, changes))
      .toBe('Since v3: processes 1 added, 1 changed, 1 removed; connections 1 added, 1 changed, 1 removed.')
  })

  it('ignores layout, bookkeeping, fields an older revision left at their default and numbers written differently', () => {
    // A revision published before delay basis, default routes, split shares and flow rate were stored.
    const { flowRate: _flowRate, ...olderLoop } = connection('mm', 'mix', 'mix')
    const older = { processes: [process('mix', 'Mix')], connections: [{ ...olderLoop, lossRate: '0.0500' }] }
    const changes = revisionChanges(draftOf([process('mix', 'Mix', { posY: 80 })], [],
      [connection('mm', 'mix', 'mix', { lossRate: 0.05, delayBasis: 'elapsed', defaultRoute: false, splitKind: null })]), older)!
    expect(changes.processes.size).toBe(0)
    expect(changes.connections.size).toBe(0)
    expect(changesSummary(2, changes)).toBe('No changes since v2.')
  })

  it('sees a removed port as a change to its process and compares nested values by content', () => {
    const before = { processes: [process('bake', 'Bake')], processIos: [port('bake-in', 'bake', { schemaJson: { type: 'object' } }),
      port('bake-out', 'bake', { direction: 'output' })], connections: [] }
    const changes = revisionChanges(draftOf([process('bake', 'Bake')],
      [port('bake-in', 'bake', { schemaJson: { type: 'object' } })], []), before)!
    expect(changes.fields.get('bake')).toEqual(['ports'])
    const renamed = revisionChanges(draftOf([process('bake', 'Oven')], [], []), { processes: [process('bake', 'Bake')] })!
    expect(renamed.fields.get('bake')).toEqual(['name'])
  })

  it('reads an unreadable snapshot as none', () => {
    expect(revisionChanges(draft, null)).toBeNull()
    expect(revisionChanges(draft, { processes: 'x' })).toBeNull()
    expect(revisionChanges(draft, { processes: [{ processName: 'no id' }] })).toBeNull()
    expect(revisionChanges(draft, { processes: [process('a', 'A'), process('a', 'A again')] })).toBeNull()
  })
})
