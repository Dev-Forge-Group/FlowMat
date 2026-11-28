import type { ItemDto } from '../../../shared/types/api'
import type { CanvasPortViewModel, CreateProcessIoInput, UpdateProcessIoInput } from './types'

export interface PortFormState {
  processIoId: string | null
  itemId: string
  ioName: string
  direction: 'input' | 'output'
  ioType: string
  role: string
  resourceType: string
  schemaJson: string
  validationRule: string
  quantity: string
  unit: string
  formula: string
  colorScheme: string
  requiredYn: 'Y' | 'N'
  allowShortageYn: 'Y' | 'N'
}

const DEFAULT_DIRECTION: PortFormState['direction'] = 'input'
const DEFAULT_IO_TYPE = 'material'
const DEFAULT_COLOR_BY_DIRECTION: Record<PortFormState['direction'], string> = {
  input: 'sky',
  output: 'emerald',
}

export function createDefaultPortFormState(direction: PortFormState['direction'] = DEFAULT_DIRECTION): PortFormState {
  return {
    processIoId: null,
    itemId: '',
    ioName: '',
    direction,
    ioType: DEFAULT_IO_TYPE,
    role: '',
    resourceType: DEFAULT_IO_TYPE,
    schemaJson: '',
    validationRule: '',
    quantity: '0',
    unit: '',
    formula: '',
    colorScheme: DEFAULT_COLOR_BY_DIRECTION[direction],
    requiredYn: 'Y',
    allowShortageYn: 'N',
  }
}

export function toPortFormState(port: CanvasPortViewModel): PortFormState {
  const ioType = port.ioType ?? DEFAULT_IO_TYPE
  return {
    processIoId: port.processIoId,
    itemId: port.itemId ?? '',
    ioName: port.name ?? '',
    direction: port.direction,
    ioType,
    role: port.role ?? '',
    resourceType: port.resourceType ?? ioType,
    schemaJson: port.schemaJson ? JSON.stringify(port.schemaJson, null, 2) : '',
    validationRule: port.validationRule ?? '',
    quantity: port.quantity ?? '',
    unit: port.unit ?? '',
    formula: port.formula ?? '',
    colorScheme: port.colorScheme ?? DEFAULT_COLOR_BY_DIRECTION[port.direction],
    requiredYn: port.required ? 'Y' : 'N',
    allowShortageYn: port.allowShortage ? 'Y' : 'N',
  }
}

/** Material and product ports and ports bound to an item need a quantity and unit (docs/domain/port-measurement.md PM2). */
export function portNeedsMeasure(state: Pick<PortFormState, 'resourceType' | 'ioType' | 'itemId'>): boolean {
  const type = (state.resourceType.trim() || state.ioType.trim() || DEFAULT_IO_TYPE).toLowerCase()
  return type === 'material' || type === 'product' || Boolean(state.itemId.trim())
}

/**
 * A port bound to an item takes the item's name and unit where they are still empty. The unit is the unit's code
 * (`kg`), which ports and plans use, not the item's unit id (`unit_kg`); left empty while the code is not known.
 */
export function applyItemDefaults(state: PortFormState, item: ItemDto | undefined, unitCode?: string | null): PortFormState {
  if (!item) return state
  return {
    ...state,
    itemId: item.itemId,
    ioName: state.ioName || item.itemName,
    unit: state.unit || unitCode || '',
  }
}

export function toCreateProcessIoInput(processId: string, state: PortFormState): CreateProcessIoInput {
  return {
    processId,
    itemId: normalizeOptionalText(state.itemId),
    ioName: state.ioName.trim(),
    direction: state.direction,
    ioType: state.ioType.trim().toLowerCase(),
    role: normalizeOptionalText(state.role),
    resourceType: state.resourceType.trim().toLowerCase(),
    schemaJson: parseSchemaJson(state.schemaJson),
    validationRule: normalizeOptionalText(state.validationRule),
    // Left blank, they are left out; only ports that need them must have them (PM4).
    quantity: state.quantity.trim() ? normalizeQuantity(state.quantity) : undefined,
    unit: normalizeOptionalText(state.unit),
    formula: normalizeOptionalText(state.formula),
    colorScheme: state.colorScheme.trim().toLowerCase(),
    requiredYn: state.requiredYn,
    allowShortageYn: state.allowShortageYn,
  }
}

export function toUpdateProcessIoInput(state: PortFormState): UpdateProcessIoInput {
  const itemId = normalizeOptionalText(state.itemId)
  return {
    processIoId: state.processIoId ?? '',
    // "No item" removes the binding; the server keeps the item when itemId is merely blank.
    ...(itemId ? { itemId } : { clearItem: true }),
    ioName: state.ioName.trim(),
    direction: state.direction,
    ioType: state.ioType.trim().toLowerCase(),
    role: state.role.trim(),
    resourceType: state.resourceType.trim().toLowerCase(),
    schemaJson: parseSchemaJson(state.schemaJson),
    clearSchema: !state.schemaJson.trim(),
    validationRule: state.validationRule.trim(),
    // A blank quantity or unit is emptied on the server; the other is set again (PM5).
    ...(!state.quantity.trim() || !state.unit.trim() ? { clearMeasure: true } : {}),
    quantity: state.quantity.trim() ? normalizeQuantity(state.quantity) : undefined,
    unit: normalizeOptionalText(state.unit),
    formula: state.formula.trim(),
    colorScheme: state.colorScheme.trim().toLowerCase(),
    requiredYn: state.requiredYn,
    allowShortageYn: state.allowShortageYn,
  }
}

export function hasValidPortSelection(state: PortFormState): boolean {
  const quantity = state.quantity.trim()
  const unit = state.unit.trim()
  // PM2-PM3: material and product need both; any other port may leave both out, but a quantity needs a unit.
  const measured = portNeedsMeasure(state) ? Boolean(quantity) && Boolean(unit) : !quantity || Boolean(unit)
  return measured && Boolean(state.resourceType.trim())
    && (!quantity || isValidQuantity(quantity))
    && state.ioName.trim().length <= 100 && state.ioType.trim().length <= 30
    && state.role.trim().length <= 50 && state.resourceType.trim().length <= 50
    && state.unit.trim().length <= 20 && state.colorScheme.trim().length <= 30
    && state.validationRule.trim().length <= 2000
    && isValidSchemaJson(state.schemaJson)
}

export function isValidSchemaJson(value: string): boolean {
  if (!value.trim()) return true
  try {
    const parsed: unknown = JSON.parse(value)
    if (!isObject(parsed) || parsed.type !== 'object' || JSON.stringify(parsed).length > 65536) return false
    const properties = parsed.properties
    if (properties !== undefined && !isObject(properties)) return false
    const declared = properties ?? {}
    for (const [name, definition] of Object.entries(declared)) {
      if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name) || !isObject(definition)
        || !['string', 'number', 'boolean'].includes(String(definition.type))) return false
    }
    const required = parsed.required
    if (required !== undefined && (!Array.isArray(required) || required.some(
      (name: unknown) => typeof name !== 'string'
        || !Object.prototype.hasOwnProperty.call(declared, name)))) return false
    return true
  } catch {
    return false
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseSchemaJson(value: string): Record<string, unknown> | undefined {
  if (!value.trim()) return undefined
  const parsed: unknown = JSON.parse(value)
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('Port schema must be a JSON object.')
  }
  return parsed as Record<string, unknown>
}

function normalizeQuantity(value: string): number {
  if (!isValidQuantity(value)) {
    throw new Error('Port quantity must fit 10 integer and 4 decimal digits.')
  }
  return Number(value)
}

function isValidQuantity(value: string): boolean {
  const normalized = value.trim()
  return /^\d+(?:\.\d{1,4})?$/.test(normalized)
    && Number(normalized) <= 9999999999.9999
}

function normalizeOptionalText(value: string): string | undefined {
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}
