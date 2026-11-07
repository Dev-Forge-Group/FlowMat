import { useRef, useState, type FormEvent } from 'react'
import { useHolidayMutations, useHolidaysQuery } from '../../../entities/catalog/api/useEquipment'
import { errorMessage } from '../../../shared/lib/errorMessage'

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

/** "Wed" for 2032-03-03; read as a calendar date, not an instant, so no time zone moves it. */
function weekday(date: string): string {
  const [year, month, day] = date.split('-').map(Number)
  return WEEKDAYS[new Date(Date.UTC(year, month - 1, day)).getUTCDay()]
}

/**
 * The project's holidays (docs/domain/equipment-schedule.md "휴일"): no calendar shift starts on them, for every piece of
 * equipment with a calendar. Equipment without a calendar is available around the clock and is not affected.
 */
export function HolidaysPanel({ projectId }: { projectId: string }) {
  const holidaysQuery = useHolidaysQuery(projectId)
  const { add, remove } = useHolidayMutations(projectId)
  const inputRevision = useRef(0)
  const [date, setDate] = useState('')
  const [name, setName] = useState('')
  const holidays = holidaysQuery.data ?? []
  const today = new Date().toISOString().slice(0, 10)
  const upcoming = holidays.filter((holiday) => holiday.date >= today).length

  function submit(event: FormEvent) {
    event.preventDefault()
    const submittedRevision = inputRevision.current
    add.mutate(
      { date, name: name.trim() || undefined },
      {
        onSuccess: () => {
          if (inputRevision.current !== submittedRevision) return
          setDate('')
          setName('')
        },
      },
    )
  }

  const error = add.error ?? remove.error
  return (
    <details aria-label="Holidays" style={{ marginTop: 16 }}>
      <summary>
        Holidays <span className="inspector-hint">{upcoming} upcoming</span>
      </summary>
      <p className="inspector-hint" style={{ margin: '6px 0' }}>
        No calendar shift starts on these dates. A night shift that starts the evening before still runs. Equipment without a
        calendar is not affected.
      </p>
      <form onSubmit={submit} style={{ display: 'flex', gap: 8, alignItems: 'end', flexWrap: 'wrap', fontSize: 12 }}>
        <label style={{ display: 'grid', gap: 4 }}>
          <span>Date</span>
          <input type="date" value={date} onChange={(e) => { inputRevision.current += 1; setDate(e.target.value) }} required />
        </label>
        <label style={{ display: 'grid', gap: 4, flex: 1, minWidth: 140 }}>
          <span>Name</span>
          <input value={name} maxLength={100} onChange={(e) => { inputRevision.current += 1; setName(e.target.value) }} placeholder="e.g. Founding day" />
        </label>
        <button type="submit" disabled={add.isPending || !date}>
          Add holiday
        </button>
      </form>
      {error && <p style={{ color: '#dc2626', fontSize: 12, margin: '6px 0 0' }}>{errorMessage(error, 'The holiday change failed.')}</p>}
      {holidays.length === 0 ? (
        <p className="inspector-hint">No holidays yet.</p>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginTop: 8 }}>
          <tbody>
            {holidays.map((holiday) => (
              <tr key={holiday.holidayId} style={{ borderBottom: '1px solid var(--border)', opacity: holiday.date < today ? 0.55 : 1 }}>
                <td style={{ padding: '4px 6px', whiteSpace: 'nowrap' }}>
                  {holiday.date} <span className="inspector-hint">{weekday(holiday.date)}</span>
                </td>
                <td style={{ padding: '4px 6px', width: '100%' }}>{holiday.name ?? ''}</td>
                <td style={{ padding: '4px 6px' }}>
                  <button type="button" disabled={remove.isPending} onClick={() => remove.mutate(holiday.holidayId)} style={{ fontSize: 11 }}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </details>
  )
}
