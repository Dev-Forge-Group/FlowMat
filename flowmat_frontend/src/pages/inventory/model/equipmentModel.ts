import type { EquipmentDetailsDto, EquipmentDto } from '../../../shared/types/api'
import { csvCell } from './ledgerModel'

/** The details as typed in the form: every field is text, empty when not recorded, but the side-by-side switch. */
export interface EquipmentDetailsForm {
  manufacturer: string
  modelName: string
  serialNo: string
  capacityPerHour: string
  powerKwh: string
  waterLiter: string
  location: string
  efficiencyPercent: string
  /** Several work orders at once (docs/domain/equipment.md E12). */
  concurrentWorkOrders: boolean
  /** At most this many at once while side by side, as typed; empty is no limit (E16). */
  concurrentLimit: string
}

export const EMPTY_DETAILS_FORM: EquipmentDetailsForm = {
  manufacturer: '', modelName: '', serialNo: '', capacityPerHour: '', powerKwh: '', waterLiter: '', location: '', efficiencyPercent: '',
  concurrentWorkOrders: false, concurrentLimit: '',
}

const NUMBERS = [
  ['capacityPerHour', 'Capacity / hour'],
  ['powerKwh', 'Power kWh'],
  ['waterLiter', 'Water L'],
] as const

export function detailsForm(details: EquipmentDetailsDto | null | undefined): EquipmentDetailsForm {
  if (!details) return EMPTY_DETAILS_FORM
  const text = (value: string | null) => value ?? ''
  const num = (value: number | null) => (value === null ? '' : String(value))
  return {
    manufacturer: text(details.manufacturer), modelName: text(details.modelName), serialNo: text(details.serialNo),
    capacityPerHour: num(details.capacityPerHour), powerKwh: num(details.powerKwh), waterLiter: num(details.waterLiter),
    location: text(details.location), efficiencyPercent: num(details.efficiencyPercent ?? null),
    concurrentWorkOrders: details.concurrentWorkOrders ?? false,
    concurrentLimit: details.concurrentLimit != null ? String(details.concurrentLimit) : '',
  }
}

/**
 * The details to send. The server replaces them all, so an empty field clears it. A number field that is not a number of
 * 0 or more gives an error naming it instead.
 */
export function detailsPayload(form: EquipmentDetailsForm): { details: EquipmentDetailsDto; error: null } | { details: null; error: string } {
  const numbers: Partial<Record<(typeof NUMBERS)[number][0], number | null>> = {}
  for (const [key, label] of NUMBERS) {
    const raw = form[key].trim()
    if (raw === '') {
      numbers[key] = null
      continue
    }
    const value = Number(raw)
    if (!Number.isFinite(value) || value < 0) return { details: null, error: `${label} must be a number of 0 or more.` }
    numbers[key] = value
  }
  // Above 0 to 100 (E8); empty is 100, the rated output.
  const efficiencyRaw = form.efficiencyPercent.trim()
  const efficiency = efficiencyRaw === '' ? null : Number(efficiencyRaw)
  if (efficiency !== null && (!Number.isFinite(efficiency) || efficiency <= 0 || efficiency > 100)) {
    return { details: null, error: 'Efficiency % must be above 0 and at most 100.' }
  }
  // While side by side, at most 2 to 99 at once (E16); empty is no limit.
  const limitRaw = form.concurrentWorkOrders ? form.concurrentLimit.trim() : ''
  const limit = limitRaw === '' ? null : Number(limitRaw)
  if (limit !== null && (!Number.isInteger(limit) || limit < 2 || limit > 99)) {
    return { details: null, error: 'At most at once is a whole number from 2 to 99.' }
  }
  const text = (value: string) => (value.trim() === '' ? null : value.trim())
  return {
    details: {
      manufacturer: text(form.manufacturer), modelName: text(form.modelName), serialNo: text(form.serialNo),
      capacityPerHour: numbers.capacityPerHour ?? null, powerKwh: numbers.powerKwh ?? null, waterLiter: numbers.waterLiter ?? null,
      location: text(form.location), efficiencyPercent: efficiency, concurrentWorkOrders: form.concurrentWorkOrders,
      concurrentLimit: limit,
    },
    error: null,
  }
}

/** What it takes and gives per hour of running, e.g. "120.5 out · 4 kWh · 30 L"; empty when none is recorded. */
export function perHour(details: EquipmentDetailsDto | null | undefined, format: (value: number) => string = String): string {
  if (!details) return ''
  return [
    details.capacityPerHour !== null ? `${format(details.capacityPerHour)} out` : null,
    details.powerKwh !== null ? `${format(details.powerKwh)} kWh` : null,
    details.waterLiter !== null ? `${format(details.waterLiter)} L` : null,
    details.efficiencyPercent != null && details.efficiencyPercent !== 100 ? `${format(details.efficiencyPercent)}% efficient` : null,
  ].filter(Boolean).join(' · ')
}

/** Maker and model on one line, e.g. "Acme M-200"; empty when neither is recorded. */
export function makerModel(details: EquipmentDetailsDto | null | undefined): string {
  return [details?.manufacturer, details?.modelName].filter(Boolean).join(' ')
}

/**
 * Equipment whose code, name, type, maker, model, serial number or location contains the text (ignoring case), in the
 * status asked for ('' for any).
 */
export function filterEquipment(equipment: EquipmentDto[], text: string, status: string): EquipmentDto[] {
  const needle = text.trim().toLowerCase()
  return equipment.filter(
    (one) =>
      (!status || one.equipmentStatus === status)
      && (!needle
        || [one.equipmentCode, one.equipmentName, one.equipmentType, one.details?.manufacturer, one.details?.modelName, one.details?.serialNo, one.details?.location]
          .some((value) => (value ?? '').toLowerCase().includes(needle))),
  )
}

/** The equipment list as CSV, with a byte order mark like the stock exports. */
export function equipmentCsv(equipment: EquipmentDto[]): string {
  const header = ['code', 'name', 'type', 'status', 'manufacturer', 'model', 'serial_no', 'location', 'capacity_per_hour', 'power_kwh', 'water_liter']
  const lines = equipment.map((one) =>
    [
      one.equipmentCode, one.equipmentName, one.equipmentType, one.equipmentStatus, one.details?.manufacturer, one.details?.modelName,
      one.details?.serialNo, one.details?.location, one.details?.capacityPerHour, one.details?.powerKwh, one.details?.waterLiter,
    ]
      .map((value) => csvCell(value ?? null))
      .join(','),
  )
  return '\ufeff' + [header.join(','), ...lines].join('\r\n') + '\r\n'
}

/** "active → maintenance · Bearing replaced", or "added as active" for the first status (docs/domain/equipment.md). */
export function statusChangeText(change: { previousStatus: string | null; equipmentStatus: string; note: string | null }): string {
  const what = change.previousStatus ? `${change.previousStatus} → ${change.equipmentStatus}` : `added as ${change.equipmentStatus}`
  return change.note ? `${what} · ${change.note}` : what
}
