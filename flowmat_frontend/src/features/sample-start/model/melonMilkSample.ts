import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/**
 * The melon milk sample (docs/status/ROADMAP-2026-11-23.md §6 first run, the same as scripts/demo/melon-milk.mjs): a new
 * project with raw milk in → mix → fill → pack, its materials, stock (raw milk short for 1,000 bottles), equipment and
 * execution requirements, published so a plan can be asked for at once. It goes through the public API only, so stock is
 * written by the inventory command as any receipt is.
 */
export interface SampleCreated {
  projectId: string
  workflowId: string
}

type Send = <T>(method: 'POST' | 'PUT', path: string, body?: unknown) => Promise<T>

const send: Send = async <T,>(method: 'POST' | 'PUT', path: string, body?: unknown) => {
  const envelope = method === 'PUT'
    ? await httpClient.put<ApiEnvelope<T>>(path, body)
    : await httpClient.post<ApiEnvelope<T>>(path, body)
  return unwrapApiResponse(envelope)
}

/** Steps it goes through, for the progress line. */
export const SAMPLE_STEPS = ['Project', 'Items', 'Stock', 'Equipment', 'Process', 'Publish'] as const
export type SampleStep = (typeof SAMPLE_STEPS)[number]

export async function buildMelonMilkSample(ownerId: string, onStep: (step: SampleStep) => void = () => {},
  call: Send = send): Promise<SampleCreated> {
  onStep('Project')
  const { projectId } = await call<{ projectId: string }>('POST', '/projects', {
    projectName: 'Melon milk sample', ownerId, projectDesc: 'Raw milk in, melon milk out: ask the Plan panel for 1,000 bottles.',
    visibility: 'private',
  })
  await call('POST', '/storage-locations', { projectId, locationCode: 'RAW', locationType: 'location' })

  onStep('Items')
  const products = new Set<string>()
  const item = async (itemCode: string, itemName: string, itemType: string, unitId: string) => {
    const { itemId } = await call<{ itemId: string }>('POST', '/items', { projectId, itemCode, itemName, itemType, unitId })
    if (itemType === 'product') products.add(itemId)
    return itemId
  }
  const milk = await item('MM-MILK', 'Raw milk', 'material', 'unit_kg')
  const syrup = await item('MM-SYRUP', 'Melon syrup', 'material', 'unit_kg')
  const sugar = await item('MM-SUGAR', 'Sugar', 'material', 'unit_kg')
  const bottle = await item('MM-BOTTLE', 'Bottle', 'material', 'unit_ea')
  const box = await item('MM-BOX', 'Box of 24', 'material', 'unit_ea')
  const mixed = await item('MM-MIX', 'Melon milk mix', 'semi', 'unit_kg')
  const filled = await item('MM-FILLED', 'Filled bottle', 'semi', 'unit_ea')
  const melonMilk = await item('MM-PRODUCT', 'Melon milk', 'product', 'unit_ea')

  onStep('Stock')
  // Raw milk is short for 1,000 bottles: 150 kg of the 184.0564 kg needed.
  for (const [itemId, quantity] of [[milk, 150], [syrup, 20], [sugar, 50], [bottle, 2000], [box, 50]] as const) {
    await call('POST', '/inventories', { projectId, itemId, location: 'RAW', quantity })
  }

  onStep('Equipment')
  const equipment = async (equipmentCode: string) => (await call<{ equipmentId: string }>('POST', '/equipments', {
    projectId, equipmentCode, equipmentName: equipmentCode.toLowerCase(), equipmentType: 'machine',
  })).equipmentId
  const mixer = await equipment('MIXER-1')
  const filler = await equipment('FILLER-1')

  onStep('Process')
  const { workflowId } = await call<{ workflowId: string }>('POST', '/workflows', { projectId, workflowName: 'Melon milk' })
  let x = 80
  const node = async (processName: string) => {
    const { processId } = await call<{ processId: string }>('POST', '/processes',
      { workflowId, processName, nodeType: 'process', posX: x, posY: 240 })
    x += 340
    return processId
  }
  // Materials drawn from stock have no connection in, so they are not required inputs.
  const port = async (processId: string, direction: 'input' | 'output', itemId: string, quantity: number, unit: string) =>
    (await call<{ processIoId: string }>('POST', '/process-ios', {
      processId, itemId, direction, quantity, unit, resourceType: products.has(itemId) ? 'product' : 'material', requiredYn: 'N',
    })).processIoId
  const connect = (fromProcessId: string, fromIoId: string, toProcessId: string, toIoId: string, lossRate?: number) =>
    call('POST', '/process-connections', { workflowId, fromProcessId, toProcessId, fromIoId, toIoId, ...(lossRate ? { lossRate } : {}) })
  const requirements = (processId: string, list: unknown[]) =>
    call('PUT', `/processes/${processId}/requirements`, { requirements: list })

  const receive = await node('Receive milk')
  const mix = await node('Mix')
  const fill = await node('Fill')
  const pack = await node('Pack')
  await port(receive, 'input', milk, 1, 'kg')
  const receiveOut = await port(receive, 'output', milk, 1, 'kg')
  const mixMilk = await port(mix, 'input', milk, 100, 'kg')
  await port(mix, 'input', syrup, 10, 'kg')
  await port(mix, 'input', sugar, 5, 'kg')
  const mixOut = await port(mix, 'output', mixed, 112, 'kg')
  const fillMix = await port(fill, 'input', mixed, 0.2, 'kg')
  await port(fill, 'input', bottle, 1, 'ea')
  const fillOut = await port(fill, 'output', filled, 1, 'ea')
  const packFilled = await port(pack, 'input', filled, 24, 'ea')
  await port(pack, 'input', box, 1, 'ea')
  await port(pack, 'output', melonMilk, 24, 'ea')
  await connect(receive, receiveOut, mix, mixMilk)
  await connect(mix, mixOut, fill, fillMix, 0.02)
  await connect(fill, fillOut, pack, packFilled, 0.01)
  await requirements(mix, [{ kind: 'equipment', ref: mixer, amount: 1 }, { kind: 'time', amount: 30, unit: 'min' }])
  await requirements(fill, [{ kind: 'equipment', ref: filler, amount: 1 },
    { kind: 'time', amount: 3, unit: 's', per: 'output_unit' }])
  await requirements(pack, [{ kind: 'crew', amount: 2 }, { kind: 'time', amount: 1, unit: 'min' }])

  onStep('Publish')
  await call('POST', `/workflows/${workflowId}/revisions`)
  return { projectId, workflowId }
}
