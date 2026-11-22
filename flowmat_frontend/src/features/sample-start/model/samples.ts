import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/**
 * Industry samples for a first run (docs/status/ROADMAP-2026-11-23.md §6 and §8 next month 1), the same as
 * scripts/demo/melon-milk.mjs and scripts/demo/assembly.mjs: a new project with a process, its items, stock (one material
 * short for the target), equipment and execution requirements, published so a plan can be asked for at once. They go
 * through the public API only, so stock is written by the inventory command as any receipt is.
 */
export interface SampleSpec {
  key: string
  title: string
  summary: string
  projectName: string
  workflowName: string
  /** What to ask the Plan panel, for the project description. */
  target: string
  items: { code: string; name: string; type: 'material' | 'semi' | 'product'; unitId: string }[]
  stock: [code: string, quantity: number][]
  equipment: string[]
  nodes: {
    name: string
    ports: [direction: 'input' | 'output', code: string, quantity: number, unit: string][]
    requirements?: { kind: 'equipment' | 'crew' | 'time'; equipment?: string; amount: number; unit?: string; per?: 'output_unit' }[]
  }[]
  /** From a node's output of an item to a node's input of the same item. */
  connections: { from: string; to: string; item: string; lossRate?: number }[]
}

export const SAMPLES: SampleSpec[] = [
  {
    key: 'melon-milk',
    title: '메론우유',
    summary: '원유 입고 → 배합 → 충전 → 포장. 1,000병에는 원유가 모자란다.',
    projectName: 'Melon milk sample',
    workflowName: 'Melon milk',
    target: 'Ask the Plan panel for 1,000 bottles.',
    items: [
      { code: 'MM-MILK', name: 'Raw milk', type: 'material', unitId: 'unit_kg' },
      { code: 'MM-SYRUP', name: 'Melon syrup', type: 'material', unitId: 'unit_kg' },
      { code: 'MM-SUGAR', name: 'Sugar', type: 'material', unitId: 'unit_kg' },
      { code: 'MM-BOTTLE', name: 'Bottle', type: 'material', unitId: 'unit_ea' },
      { code: 'MM-BOX', name: 'Box of 24', type: 'material', unitId: 'unit_ea' },
      { code: 'MM-MIX', name: 'Melon milk mix', type: 'semi', unitId: 'unit_kg' },
      { code: 'MM-FILLED', name: 'Filled bottle', type: 'semi', unitId: 'unit_ea' },
      { code: 'MM-PRODUCT', name: 'Melon milk', type: 'product', unitId: 'unit_ea' },
    ],
    // Raw milk is short for 1,000 bottles: 150 kg of the 184.0564 kg needed.
    stock: [['MM-MILK', 150], ['MM-SYRUP', 20], ['MM-SUGAR', 50], ['MM-BOTTLE', 2000], ['MM-BOX', 50]],
    equipment: ['MIXER-1', 'FILLER-1'],
    nodes: [
      { name: 'Receive milk', ports: [['input', 'MM-MILK', 1, 'kg'], ['output', 'MM-MILK', 1, 'kg']] },
      { name: 'Mix', ports: [['input', 'MM-MILK', 100, 'kg'], ['input', 'MM-SYRUP', 10, 'kg'], ['input', 'MM-SUGAR', 5, 'kg'],
        ['output', 'MM-MIX', 112, 'kg']],
      requirements: [{ kind: 'equipment', equipment: 'MIXER-1', amount: 1 }, { kind: 'time', amount: 30, unit: 'min' }] },
      { name: 'Fill', ports: [['input', 'MM-MIX', 0.2, 'kg'], ['input', 'MM-BOTTLE', 1, 'ea'], ['output', 'MM-FILLED', 1, 'ea']],
        requirements: [{ kind: 'equipment', equipment: 'FILLER-1', amount: 1 },
          { kind: 'time', amount: 3, unit: 's', per: 'output_unit' }] },
      { name: 'Pack', ports: [['input', 'MM-FILLED', 24, 'ea'], ['input', 'MM-BOX', 1, 'ea'], ['output', 'MM-PRODUCT', 24, 'ea']],
        requirements: [{ kind: 'crew', amount: 2 }, { kind: 'time', amount: 1, unit: 'min' }] },
    ],
    connections: [
      { from: 'Receive milk', to: 'Mix', item: 'MM-MILK' },
      { from: 'Mix', to: 'Fill', item: 'MM-MIX', lossRate: 0.02 },
      { from: 'Fill', to: 'Pack', item: 'MM-FILLED', lossRate: 0.01 },
    ],
  },
  {
    key: 'stool',
    title: '의자 조립',
    summary: '재단 → 샌딩 → 조립 → 포장. 200개에는 나사가 모자란다.',
    projectName: 'Stool assembly sample',
    workflowName: 'Stool',
    target: 'Ask the Plan panel for 200 stools.',
    items: [
      { code: 'ST-BOARD', name: 'Oak board', type: 'material', unitId: 'unit_ea' },
      { code: 'ST-SEAT', name: 'Seat', type: 'material', unitId: 'unit_ea' },
      { code: 'ST-SCREW', name: 'Screw', type: 'material', unitId: 'unit_ea' },
      { code: 'ST-BOX', name: 'Box', type: 'material', unitId: 'unit_ea' },
      { code: 'ST-LEG', name: 'Leg (cut)', type: 'semi', unitId: 'unit_ea' },
      { code: 'ST-LEG-S', name: 'Leg (sanded)', type: 'semi', unitId: 'unit_ea' },
      { code: 'ST-RAW', name: 'Stool (assembled)', type: 'semi', unitId: 'unit_ea' },
      { code: 'ST-STOOL', name: 'Stool', type: 'product', unitId: 'unit_ea' },
    ],
    stock: [['ST-BOARD', 250], ['ST-SEAT', 220], ['ST-SCREW', 1200], ['ST-BOX', 300]],
    equipment: ['SAW-1', 'SANDER-1'],
    nodes: [
      { name: 'Cut legs', ports: [['input', 'ST-BOARD', 1, 'ea'], ['output', 'ST-LEG', 4, 'ea']],
        requirements: [{ kind: 'equipment', equipment: 'SAW-1', amount: 1 }, { kind: 'time', amount: 2, unit: 'min' }] },
      { name: 'Sand', ports: [['input', 'ST-LEG', 1, 'ea'], ['output', 'ST-LEG-S', 1, 'ea']],
        requirements: [{ kind: 'equipment', equipment: 'SANDER-1', amount: 1 }, { kind: 'time', amount: 45, unit: 's' }] },
      { name: 'Assemble', ports: [['input', 'ST-LEG-S', 4, 'ea'], ['input', 'ST-SEAT', 1, 'ea'], ['input', 'ST-SCREW', 8, 'ea'],
        ['output', 'ST-RAW', 1, 'ea']],
      requirements: [{ kind: 'crew', amount: 1 }, { kind: 'time', amount: 6, unit: 'min' }] },
      { name: 'Pack', ports: [['input', 'ST-RAW', 1, 'ea'], ['input', 'ST-BOX', 1, 'ea'], ['output', 'ST-STOOL', 1, 'ea']],
        requirements: [{ kind: 'crew', amount: 1 }, { kind: 'time', amount: 1, unit: 'min' }] },
    ],
    connections: [
      { from: 'Cut legs', to: 'Sand', item: 'ST-LEG', lossRate: 0.05 },
      { from: 'Sand', to: 'Assemble', item: 'ST-LEG-S' },
      { from: 'Assemble', to: 'Pack', item: 'ST-RAW' },
    ],
  },
]

export interface SampleCreated {
  projectId: string
  workflowId: string
}

export type Send = <T>(method: 'POST' | 'PUT', path: string, body?: unknown) => Promise<T>

const send: Send = async <T,>(method: 'POST' | 'PUT', path: string, body?: unknown) => {
  const envelope = method === 'PUT'
    ? await httpClient.put<ApiEnvelope<T>>(path, body)
    : await httpClient.post<ApiEnvelope<T>>(path, body)
  return unwrapApiResponse(envelope)
}

/** Steps it goes through, for the progress line. */
export const SAMPLE_STEPS = ['Project', 'Items', 'Stock', 'Equipment', 'Process', 'Publish'] as const
export type SampleStep = (typeof SAMPLE_STEPS)[number]

export async function buildSample(spec: SampleSpec, ownerId: string, onStep: (step: SampleStep) => void = () => {},
  call: Send = send): Promise<SampleCreated> {
  onStep('Project')
  const { projectId } = await call<{ projectId: string }>('POST', '/projects', {
    projectName: spec.projectName, ownerId, projectDesc: spec.target, visibility: 'private',
  })
  await call('POST', '/storage-locations', { projectId, locationCode: 'RAW', locationType: 'location' })

  onStep('Items')
  const items = new Map<string, { itemId: string; type: string }>()
  for (const item of spec.items) {
    const { itemId } = await call<{ itemId: string }>('POST', '/items',
      { projectId, itemCode: item.code, itemName: item.name, itemType: item.type, unitId: item.unitId })
    items.set(item.code, { itemId, type: item.type })
  }
  const itemId = (code: string) => {
    const found = items.get(code)
    if (!found) throw new Error(`The sample has no item ${code}.`)
    return found.itemId
  }

  onStep('Stock')
  for (const [code, quantity] of spec.stock) {
    await call('POST', '/inventories', { projectId, itemId: itemId(code), location: 'RAW', quantity })
  }

  onStep('Equipment')
  const equipment = new Map<string, string>()
  for (const code of spec.equipment) {
    const { equipmentId } = await call<{ equipmentId: string }>('POST', '/equipments',
      { projectId, equipmentCode: code, equipmentName: code.toLowerCase(), equipmentType: 'machine' })
    equipment.set(code, equipmentId)
  }

  onStep('Process')
  const { workflowId } = await call<{ workflowId: string }>('POST', '/workflows', { projectId, workflowName: spec.workflowName })
  const processes = new Map<string, string>()
  const ports = new Map<string, string>()
  let x = 80
  for (const node of spec.nodes) {
    const { processId } = await call<{ processId: string }>('POST', '/processes',
      { workflowId, processName: node.name, nodeType: 'process', posX: x, posY: 240 })
    x += 340
    processes.set(node.name, processId)
    for (const [direction, code, quantity, unit] of node.ports) {
      // Materials drawn from stock have no connection in, so they are not required inputs.
      const { processIoId } = await call<{ processIoId: string }>('POST', '/process-ios', {
        processId, itemId: itemId(code), direction, quantity, unit,
        resourceType: items.get(code)?.type === 'product' ? 'product' : 'material', requiredYn: 'N',
      })
      ports.set(`${node.name}/${direction}/${code}`, processIoId)
    }
  }
  for (const connection of spec.connections) {
    await call('POST', '/process-connections', {
      workflowId, fromProcessId: processes.get(connection.from), toProcessId: processes.get(connection.to),
      fromIoId: ports.get(`${connection.from}/output/${connection.item}`), toIoId: ports.get(`${connection.to}/input/${connection.item}`),
      ...(connection.lossRate ? { lossRate: connection.lossRate } : {}),
    })
  }
  for (const node of spec.nodes) {
    if (!node.requirements) continue
    await call('PUT', `/processes/${processes.get(node.name)}/requirements`, {
      requirements: node.requirements.map(({ equipment: code, ...rest }) => code ? { ...rest, ref: equipment.get(code) } : rest),
    })
  }

  onStep('Publish')
  await call('POST', `/workflows/${workflowId}/revisions`)
  return { projectId, workflowId }
}
