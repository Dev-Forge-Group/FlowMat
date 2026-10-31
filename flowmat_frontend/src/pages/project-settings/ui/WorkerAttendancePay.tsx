import { useState, type FormEvent } from 'react'
import { useAttendancePayQuery } from '../../../entities/workforce/api/useLaborPremiums'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { exportRange } from '../model/attendanceModel'
import { payDayText, payTotalText } from '../model/premiumModel'

/**
 * A worker's attendance as pay with the project's premiums, for the project owner only (docs/architecture/adr/ADR-028-pay-premiums.md):
 * the 7 days to today first, or a chosen period of up to 62 days.
 */
export function WorkerAttendancePay({ projectId, userId }: { projectId: string; userId: string }) {
  const [range, setRange] = useState<{ from: string; to: string } | null>(null)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const query = useAttendancePayQuery(projectId, userId, range)
  const data = !query.isError && query.data && Array.isArray(query.data.days) ? query.data : undefined
  const shownFrom = from || data?.from || ''
  const shownTo = to || data?.to || ''

  function submit(event: FormEvent) {
    event.preventDefault()
    const chosen = exportRange(shownFrom, shownTo)
    setProblem(chosen.error)
    if (chosen.from) setRange({ from: chosen.from, to: chosen.to })
  }

  return (
    <section aria-label="Pay with premiums" style={{ display: 'grid', gap: 4, marginTop: 8 }}>
      <strong>Pay with premiums</strong>
      <form aria-label="Pay period" noValidate onSubmit={submit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
        <label style={{ display: 'grid', gap: 4 }}>From
          <input aria-label="Pay from" type="date" value={shownFrom} onChange={(event) => setFrom(event.target.value)} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>To
          <input aria-label="Pay to" type="date" value={shownTo} onChange={(event) => setTo(event.target.value)} />
        </label>
        <button type="submit">Show pay</button>
      </form>
      {problem && <p role="alert">{problem}</p>}
      {query.isError && <p role="alert">{errorMessage(query.error, 'Could not load the pay.')}</p>}
      {data && data.days.length > 0 && (
        <ul aria-label="Pay by day" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {data.days.map((day) => <li key={day.workDate}>{payDayText(day)}</li>)}
        </ul>
      )}
      {data && <p aria-label="Total pay">{payTotalText(data)}</p>}
    </section>
  )
}
