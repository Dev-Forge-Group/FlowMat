import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse, unwrapApiVoidResponse } from '../../../shared/api/unwrapApiResponse'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import type { ApiEnvelope, EquipmentDto } from '../../../shared/types/api'
import {
  EMPTY_DETAILS_FORM, detailsForm, detailsPayload, equipmentCsv, filterEquipment, makerModel, perHour, statusChangeText,
  type EquipmentDetailsForm,
} from '../model/equipmentModel'
import { EquipmentSchedulePanel } from './EquipmentSchedulePanel'
import { EquipmentLoadBoard } from './EquipmentLoadBoard'
import { HolidaysPanel } from './HolidaysPanel'

const emptyForm = { equipmentCode: '', equipmentName: '', equipmentType: '', equipmentStatus: 'active' }

const DETAIL_FIELDS: { key: Exclude<keyof EquipmentDetailsForm, 'concurrentWorkOrders'>; label: string; number?: boolean }[] = [
  { key: 'manufacturer', label: 'Manufacturer' },
  { key: 'modelName', label: 'Model' },
  { key: 'serialNo', label: 'Serial no.' },
  { key: 'location', label: 'Location' },
  { key: 'capacityPerHour', label: 'Capacity / hour', number: true },
  { key: 'efficiencyPercent', label: 'Efficiency %', number: true },
  { key: 'powerKwh', label: 'Power kWh', number: true },
  { key: 'waterLiter', label: 'Water L', number: true },
]

export function EquipmentPanel({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const queryKey = ['equipment', projectId]
  const equipmentQuery = useQuery({
    queryKey,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<EquipmentDto[]>>(
      `/equipments?projectId=${encodeURIComponent(projectId)}`,
    )),
    enabled: Boolean(projectId),
  })
  const [editingId, setEditingId] = useState<string | null>(null)
  // Why the status changes; kept in the equipment's status history (docs/domain/equipment.md).
  const [statusNote, setStatusNote] = useState('')
  const statusHistoryQuery = useQuery({
    queryKey: [...queryKey, 'status-history', editingId],
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<{
      previousStatus: string | null; equipmentStatus: string; note: string | null; changedBy: string; changedAt: string
    }[]>>(`/equipments/${encodeURIComponent(editingId ?? '')}/status-history`)),
    enabled: Boolean(editingId),
  })
  const [form, setForm] = useState(emptyForm)
  const [details, setDetails] = useState(EMPTY_DETAILS_FORM)
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [scheduleId, setScheduleId] = useState<string | null>(null)
  const [loadOpen, setLoadOpen] = useState(false)
  const all = equipmentQuery.data ?? []
  const shown = filterEquipment(all, search, statusFilter)
  const scheduled = all.find((equipment) => equipment.equipmentId === scheduleId)

  function download() {
    const url = URL.createObjectURL(new Blob([equipmentCsv(shown)], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `equipment-${new Date().toISOString().slice(0, 10)}.csv`
    link.click()
    URL.revokeObjectURL(url)
  }

  const save = useMutation({
    mutationFn: async () => {
      const parsed = detailsPayload(details)
      if (parsed.error !== null) throw new Error(parsed.error)
      const payload = editingId
        ? { equipmentName: form.equipmentName, equipmentType: form.equipmentType, equipmentStatus: form.equipmentStatus, details: parsed.details,
            statusNote: statusNote.trim() || null }
        : { projectId, equipmentCode: form.equipmentCode || null, equipmentName: form.equipmentName, equipmentType: form.equipmentType,
            details: parsed.details }
      const path = editingId ? `/equipments/${encodeURIComponent(editingId)}` : '/equipments'
      const response = editingId
        ? await httpClient.put<ApiEnvelope<EquipmentDto>>(path, payload)
        : await httpClient.post<ApiEnvelope<EquipmentDto>>(path, payload)
      return unwrapApiResponse(response)
    },
    onSuccess: () => {
      setEditingId(null)
      setForm(emptyForm)
      setDetails(EMPTY_DETAILS_FORM)
      setStatusNote('')
      void queryClient.invalidateQueries({ queryKey })
      void queryClient.invalidateQueries({ queryKey: ['equipment-load', projectId] })
    },
  })
  const remove = useMutation({
    mutationFn: async (id: string) => {
      unwrapApiVoidResponse(await httpClient.delete<ApiEnvelope<null>>(`/equipments/${encodeURIComponent(id)}`))
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey })
      void queryClient.invalidateQueries({ queryKey: ['equipment-load', projectId] })
    },
  })

  function edit(equipment: EquipmentDto) {
    setEditingId(equipment.equipmentId)
    setForm({ equipmentCode: equipment.equipmentCode ?? '', equipmentName: equipment.equipmentName,
      equipmentType: equipment.equipmentType, equipmentStatus: equipment.equipmentStatus })
    setDetails(detailsForm(equipment.details))
    setStatusNote('')
    save.reset()
  }

  function cancel() {
    setEditingId(null)
    setForm(emptyForm)
    setDetails(EMPTY_DETAILS_FORM)
    save.reset()
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  return <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 300px', gap: 24 }}>
    <section style={{ overflowX: 'auto' }}>
      <h2>Equipment</h2>
      {equipmentQuery.isPending && <p>Loading equipment...</p>}
      {equipmentQuery.isError && <p role="alert">{errorMessage(equipmentQuery.error)}</p>}
      {remove.isError && <p role="alert">{errorMessage(remove.error)}</p>}
      {equipmentQuery.data?.length === 0 && <p>No equipment has been added.</p>}
      {all.length > 0 && <div role="search" aria-label="Filter equipment"
        style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8, fontSize: 12 }}>
        <input value={search} onChange={(event) => setSearch(event.target.value)} aria-label="Search equipment"
          placeholder="code, name, maker, model, serial or location" style={{ minWidth: 260 }} />
        <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)} aria-label="Equipment status">
          <option value="">any status</option><option value="active">active</option>
          <option value="inactive">inactive</option><option value="maintenance">maintenance</option>
        </select>
        {shown.length !== all.length && <span className="inspector-hint">{shown.length} of {all.length}</span>}
        <button type="button" disabled={shown.length === 0} style={{ fontSize: 11 }} onClick={download}>Download CSV</button>
      </div>}
      <table style={{ width: '100%', textAlign: 'left' }}>
        <thead><tr><th>Code</th><th>Name</th><th>Type</th><th>Status</th><th>Maker / model</th><th>Location</th>
          <th>Per hour</th><th>Actions</th></tr></thead>
        <tbody>{shown.map((equipment) => <tr key={equipment.equipmentId}>
          <td>{equipment.equipmentCode ?? '—'}</td><td>{equipment.equipmentName}</td>
          <td>{equipment.equipmentType}</td><td>{equipment.equipmentStatus}
            {equipment.details?.concurrentWorkOrders && <div className="inspector-hint">side by side
              {equipment.details.concurrentLimit ? ` · up to ${equipment.details.concurrentLimit}` : ''}</div>}</td>
          <td>{makerModel(equipment.details) || '—'}
            {equipment.details?.serialNo && <div className="inspector-hint">S/N {equipment.details.serialNo}</div>}</td>
          <td>{equipment.details?.location ?? '—'}</td>
          <td>{perHour(equipment.details, formatQty) || '—'}</td>
          <td style={{ whiteSpace: 'nowrap' }}><button type="button" onClick={() => edit(equipment)}>Edit</button>{' '}
            <button type="button" aria-pressed={scheduleId === equipment.equipmentId}
              onClick={() => setScheduleId((current) => (current === equipment.equipmentId ? null : equipment.equipmentId))}>
              Schedule</button>{' '}
            <button type="button" disabled={remove.isPending} onClick={() => {
              if (window.confirm(`Delete ${equipment.equipmentName}?`)) remove.mutate(equipment.equipmentId)
            }}>Delete</button></td>
        </tr>)}</tbody>
      </table>
      {scheduled && <EquipmentSchedulePanel key={scheduled.equipmentId} equipment={scheduled}
        others={all.filter((one) => one.equipmentId !== scheduled.equipmentId)} onClose={() => setScheduleId(null)} />}
      <details style={{ marginTop: 16 }} onToggle={(event) => setLoadOpen(event.currentTarget.open)}>
        <summary>Load by week</summary>
        {loadOpen && <EquipmentLoadBoard projectId={projectId} />}
      </details>
      <HolidaysPanel projectId={projectId} />
    </section>
    <form onSubmit={submit} style={{ display: 'grid', gap: 10, alignContent: 'start' }}>
      <h2>{editingId ? 'Edit equipment' : 'Add equipment'}</h2>
      <label>Code<input value={form.equipmentCode} disabled={Boolean(editingId)} maxLength={50}
        onChange={(event) => setForm({ ...form, equipmentCode: event.target.value })} /></label>
      <label>Name<input value={form.equipmentName} required maxLength={100}
        onChange={(event) => setForm({ ...form, equipmentName: event.target.value })} /></label>
      <label>Type<input value={form.equipmentType} required maxLength={50}
        onChange={(event) => setForm({ ...form, equipmentType: event.target.value })} /></label>
      {editingId && <label>Status<select aria-label="Status" value={form.equipmentStatus}
        onChange={(event) => setForm({ ...form, equipmentStatus: event.target.value })}>
        <option value="active">Active</option><option value="inactive">Inactive</option>
        <option value="maintenance">Maintenance</option>
      </select></label>}
      {editingId && form.equipmentStatus !== all.find((one) => one.equipmentId === editingId)?.equipmentStatus && (
        <label>Status note<input value={statusNote} maxLength={200} placeholder="why, such as bearing replaced"
          onChange={(event) => setStatusNote(event.target.value)} /></label>
      )}
      <fieldset style={{ display: 'grid', gap: 8, border: '1px solid var(--border)', padding: 8 }}>
        <legend style={{ fontSize: 12 }}>Details (optional)</legend>
        {DETAIL_FIELDS.map((field) => <label key={field.key}>{field.label}<input
          value={details[field.key]}
          {...(field.number ? { type: 'number', min: 0, step: 'any', inputMode: 'decimal' as const } : { maxLength: 100 })}
          onChange={(event) => setDetails({ ...details, [field.key]: event.target.value })} /></label>)}
        <label><input type="checkbox" checked={details.concurrentWorkOrders}
          onChange={(event) => setDetails({ ...details, concurrentWorkOrders: event.target.checked })} /> Runs several work orders at once</label>
        {details.concurrentWorkOrders && <label>At most at once<input type="number" step={1} inputMode="numeric" placeholder="no limit"
          value={details.concurrentLimit} onChange={(event) => setDetails({ ...details, concurrentLimit: event.target.value })} /></label>}
        <span className="inspector-hint">Plans then let its work orders overlap, and the load board does not call it overloaded.</span>
      </fieldset>
      {save.isError && <p role="alert">{errorMessage(save.error)}</p>}
      <button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving...' : 'Save'}</button>
      {editingId && <button type="button" onClick={cancel}>Cancel</button>}
      {editingId && (statusHistoryQuery.data ?? []).length > 0 && (
        <div aria-label="Status history" style={{ fontSize: 12 }}>
          <strong>Status history</strong>
          <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
            {(statusHistoryQuery.data ?? []).slice(0, 8).map((change, index) => (
              <li key={`${change.changedAt}-${index}`}>
                {statusChangeText(change)}{' '}
                <span className="inspector-hint">· {change.changedBy} · {new Date(change.changedAt).toLocaleString()}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </form>
  </div>
}
