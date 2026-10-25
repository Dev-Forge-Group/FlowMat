import { useState, type FormEvent } from 'react'
import { useItemsQuery } from '../../../entities/catalog/api/useItemsQuery'
import { useInventoriesQuery } from '../../../entities/inventory/api/useInventoriesQuery'
import {
  useStorageLocationMutations,
  useStorageLocationsQuery,
  type StorageLocationDto,
  type StorageLocationType,
} from '../../../entities/inventory/api/useStorageLocations'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { labelSheetHtml } from '../model/labelModel'
import { conditionMismatches, conditionText, knownConditions, mismatchText } from '../model/storageConditionModel'
import { StorageConditionPolicy } from './StorageConditionPolicy'
import {
  EMPTY_LOCATION_FORM,
  LOCATION_TYPES,
  LOCATION_TYPE_LABELS,
  createPayload,
  filterLocations,
  locationForm,
  parentOptions,
  stockText,
  stockWithin,
  unlistedPlaces,
  updatePayload,
} from '../model/locationModel'

/**
 * The project's places to keep stock (docs/domain/storage-location.md): sites, warehouses, zones, locations and bins as
 * a tree. Once it lists a place, new stock, moves and imports go only to an active listed place.
 */
export function LocationsPanel({ projectId }: { projectId: string }) {
  const locationsQuery = useStorageLocationsQuery(projectId)
  const inventoriesQuery = useInventoriesQuery(projectId)
  const itemsQuery = useItemsQuery(projectId)
  const { create, update, toggle, remove, adopt } = useStorageLocationMutations(projectId)
  const [editing, setEditing] = useState<StorageLocationDto | null>(null)
  const [form, setForm] = useState(EMPTY_LOCATION_FORM)
  const [formError, setFormError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [showInactive, setShowInactive] = useState(false)
  const all = locationsQuery.data ?? []
  const shown = filterLocations(all, search, showInactive)
  // Labels for the active places the list shows (storage-location.md L10); a new window holds the sheet to print.
  const labelPlaces = shown.filter((location) => location.active)
  const [labelError, setLabelError] = useState<string | null>(null)

  function printLabels() {
    const sheet = window.open('', '_blank')
    if (!sheet) {
      setLabelError('Allow pop-ups for this site to print labels.')
      return
    }
    setLabelError(null)
    sheet.document.write(labelSheetHtml(labelPlaces))
    sheet.document.close()
  }
  const unlisted = unlistedPlaces(all, (inventoriesQuery.data ?? []).map((row) => row.location))
  const held = stockWithin(all, inventoriesQuery.data ?? [])
  // Storage conditions are pointed out (storage-location.md SL1-SL4); stock is refused only when the project chose so (SL5-SL7).
  const mismatches = conditionMismatches(all, inventoriesQuery.data ?? [], itemsQuery.data ?? [])
  const mismatchCount = [...mismatches.values()].reduce((sum, list) => sum + list.length, 0)
  const conditions = knownConditions(all, itemsQuery.data ?? [])
  const hasInside = new Set(all.map((one) => one.parentLocationId).filter((id): id is string => id !== null))
  const parents = parentOptions(all, form.locationType, editing)
  const saving = create.isPending || update.isPending
  const saveError = formError
    ?? (create.isError ? errorMessage(create.error) : null)
    ?? (update.isError ? errorMessage(update.error) : null)

  function reset() {
    setEditing(null)
    setForm(EMPTY_LOCATION_FORM)
    setFormError(null)
    create.reset()
    update.reset()
  }

  function startEdit(location: StorageLocationDto) {
    reset()
    setEditing(location)
    setForm(locationForm(location))
  }

  // A parent that cannot hold the new kind is dropped rather than sent and refused.
  function changeType(type: StorageLocationType) {
    const allowed = parentOptions(all, type, editing)
    setForm((current) => ({
      ...current,
      locationType: type,
      parentLocationId: allowed.some((one) => one.locationId === current.parentLocationId) ? current.parentLocationId : '',
    }))
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (editing) {
      const payload = updatePayload(form, editing)
      setFormError(payload.error)
      // A new code moves the stock records and tasks here along with it (storage-location.md L7).
      const renamed = form.locationCode.trim().toLowerCase() !== editing.locationCode.toLowerCase()
      if (payload.input && renamed && editing.stockRecords > 0 && !window.confirm(
        `${editing.locationCode} holds stock in ${editing.stockRecords} record${editing.stockRecords === 1 ? '' : 's'}. `
          + `Rename it to ${form.locationCode.trim()} and move them, and its warehouse tasks, along?`,
      )) return
      if (payload.input) update.mutate({ locationId: editing.locationId, input: payload.input }, { onSuccess: reset })
      return
    }
    const payload = createPayload(projectId, form)
    setFormError(payload.error)
    // Keep the kind and parent so the next place of a row of bins is quick to add.
    if (payload.input) {
      create.mutate(payload.input, {
        onSuccess: () => setForm({ ...EMPTY_LOCATION_FORM, locationType: form.locationType, parentLocationId: form.parentLocationId }),
      })
    }
  }

  return <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 300px', gap: 24 }}>
    <section style={{ overflowX: 'auto' }}>
      <h2>Locations</h2>
      <p className="inspector-hint">
        {all.length === 0
          ? 'No places are listed, so stock locations are free text. Once you add a place, new stock, moves and imports can only go to an active place on this list.'
          : 'New stock, moves and imports can only go to an active place on this list. Places nest as site > warehouse > zone > location > bin.'}
      </p>
      {unlisted.length > 0 && <p role="status" style={{ fontSize: 13 }}>
        Stock records name {unlisted.length} {unlisted.length === 1 ? 'place' : 'places'} not on the list:{' '}
        {unlisted.slice(0, 5).join(', ')}{unlisted.length > 5 ? ', ...' : ''}{' '}
        <button type="button" disabled={adopt.isPending} onClick={() => adopt.mutate()}>
          {adopt.isPending ? 'Adding...' : 'Add them as locations'}
        </button>
      </p>}
      {mismatchCount > 0 && <p role="status" aria-label="Storage condition mismatches" style={{ fontSize: 13, color: '#b45309' }}>
        {mismatchCount} stock {mismatchCount === 1 ? 'record is' : 'records are'} kept where the storage condition differs from the item's.
      </p>}
      {all.length > 0 && <StorageConditionPolicy projectId={projectId} />}
      {adopt.isError && <p role="alert">{errorMessage(adopt.error)}</p>}
      {locationsQuery.isPending && <p>Loading locations...</p>}
      {locationsQuery.isError && <p role="alert">{errorMessage(locationsQuery.error)}</p>}
      {toggle.isError && <p role="alert">{errorMessage(toggle.error)}</p>}
      {remove.isError && <p role="alert">{errorMessage(remove.error)}</p>}
      {all.length > 0 && <div role="search" aria-label="Filter locations"
        style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8, fontSize: 12 }}>
        <input value={search} onChange={(event) => setSearch(event.target.value)} aria-label="Search locations"
          placeholder="code, name or path" style={{ minWidth: 220 }} />
        <label><input type="checkbox" checked={showInactive} onChange={(event) => setShowInactive(event.target.checked)} /> Show inactive</label>
        {shown.length !== all.length && <span className="inspector-hint">{shown.length} of {all.length}</span>}
        <button type="button" style={{ fontSize: 12, marginLeft: 'auto' }} disabled={labelPlaces.length === 0} onClick={printLabels}>
          Print labels
        </button>
      </div>}
      {labelError && <p role="alert">{labelError}</p>}
      {all.length > 0 && <table aria-label="Storage locations" style={{ width: '100%', textAlign: 'left' }}>
        <thead><tr><th>Code</th><th>Name</th><th>Kind</th><th>Storage</th><th>Path</th><th>Stock</th><th>Status</th><th>Actions</th></tr></thead>
        <tbody>{shown.map((location) => <tr key={location.locationId} style={{ opacity: location.active ? 1 : 0.6 }}>
          <td style={{ paddingLeft: 6 + location.depth * 16 }}>{location.locationCode}</td>
          <td>{location.locationName ?? '—'}</td>
          <td>{LOCATION_TYPE_LABELS[location.locationType]}</td>
          <td>{conditionText(all, location)}</td>
          <td className="inspector-hint">{location.path}</td>
          <td>{stockText({ records: location.stockRecords, items: location.itemCount })}
            {/* A place with places inside also counts what they hold. */}
            {hasInside.has(location.locationId) && (held.get(location.locationId)?.records ?? 0) > location.stockRecords
              && ` · with places inside: ${stockText(held.get(location.locationId)!)}`}
            {mismatches.has(location.locationId) && <div role="note" style={{ fontSize: 12, color: '#b45309' }}>
              {mismatchText(mismatches.get(location.locationId)!)}
            </div>}</td>
          <td>{location.active ? 'active' : 'inactive'}</td>
          <td style={{ whiteSpace: 'nowrap' }}>
            <button type="button" onClick={() => startEdit(location)}>Edit</button>{' '}
            <button type="button" disabled={toggle.isPending}
              onClick={() => toggle.mutate({ locationId: location.locationId, active: !location.active })}>
              {location.active ? 'Deactivate' : 'Activate'}
            </button>{' '}
            <button type="button" disabled={remove.isPending} onClick={() => {
              if (window.confirm(`Delete ${location.locationCode}?`)) remove.mutate(location.locationId)
            }}>Delete</button>
          </td>
        </tr>)}</tbody>
      </table>}
    </section>
    <form onSubmit={submit} aria-label={editing ? 'Edit location' : 'Add location'}
      style={{ display: 'grid', gap: 10, alignContent: 'start' }}>
      <h2>{editing ? `Edit ${editing.locationCode}` : 'Add location'}</h2>
      <label>Code<input value={form.locationCode} required maxLength={100}
        onChange={(event) => setForm({ ...form, locationCode: event.target.value })} /></label>
      {editing && editing.stockRecords > 0 && (
        <small className="inspector-hint">A new code moves the {editing.stockRecords} stock record(s) and warehouse tasks here along.</small>
      )}
      <label>Name<input value={form.locationName} maxLength={100}
        onChange={(event) => setForm({ ...form, locationName: event.target.value })} /></label>
      <label>Kind<select value={form.locationType} onChange={(event) => changeType(event.target.value as StorageLocationType)}>
        {LOCATION_TYPES.map((type) => <option key={type} value={type}>{LOCATION_TYPE_LABELS[type]}</option>)}
      </select></label>
      <label>Inside<select value={form.parentLocationId}
        onChange={(event) => setForm({ ...form, parentLocationId: event.target.value })}>
        <option value="">(top level)</option>
        {parents.map((one) => <option key={one.locationId} value={one.locationId}>{one.path}</option>)}
      </select></label>
      <label>Storage condition<input value={form.storageCondition} maxLength={100} list="storage-condition-options"
        placeholder="e.g. Chilled 2-8 °C" onChange={(event) => setForm({ ...form, storageCondition: event.target.value })} /></label>
      <datalist id="storage-condition-options">{conditions.map((one) => <option key={one} value={one} />)}</datalist>
      <small className="inspector-hint">
        Places inside without their own take this one. Stock is not refused; a record whose item needs another condition is pointed out.
      </small>
      <label>Note<input value={form.note} maxLength={500}
        onChange={(event) => setForm({ ...form, note: event.target.value })} /></label>
      {saveError && <p role="alert">{saveError}</p>}
      <button type="submit" disabled={saving}>{saving ? 'Saving...' : 'Save'}</button>
      {editing && <button type="button" onClick={reset}>Cancel</button>}
    </form>
  </div>
}
