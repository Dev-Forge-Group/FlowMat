import { useState, type FormEvent } from 'react'
import type { LaborPremiumInput } from '../../../entities/workforce/api/useLaborPremiums'
import { EMPTY_PREMIUM_FORM, premiumPayload, WEEKDAYS, type PremiumForm } from '../model/premiumModel'

/**
 * The premiums of one save (docs/architecture/adr/ADR-028-pay-premiums.md decision 3): each premium optional, checked before
 * sending and cleared once saved. The project's premiums and a worker's own (decision 20) use the same form.
 */
export function PremiumRuleForm({ label, today, pending, onSave }: {
  label: string
  today: string
  pending: boolean
  onSave: (payload: LaborPremiumInput, saved: () => void) => void
}) {
  const [form, setForm] = useState<PremiumForm>(EMPTY_PREMIUM_FORM)
  const [problem, setProblem] = useState<string | null>(null)
  const field = (name: Exclude<keyof PremiumForm, 'restDaysAsHolidays'>) => ({
    value: form[name],
    onChange: (event: { target: { value: string } }) => setForm((current) => ({ ...current, [name]: event.target.value })),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const checked = premiumPayload(form)
    setProblem(checked.problem)
    if (checked.payload) onSave(checked.payload, () => setForm(EMPTY_PREMIUM_FORM))
  }

  return (
    <>
      <form aria-label={label} noValidate onSubmit={submit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
        <label style={{ display: 'grid', gap: 4 }}>Overtime after (h)
          <input aria-label="Overtime after hours" inputMode="decimal" placeholder="8" {...field('overtimeAfterHours')} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Overtime +%
          <input aria-label="Overtime percent" inputMode="numeric" placeholder="50" {...field('overtimePercent')} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Week over (h)
          <input aria-label="Weekly hours before overtime" inputMode="decimal" placeholder="40" {...field('weeklyOvertimeAfterHours')} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Week +%
          <input aria-label="Percent past the weekly hours" inputMode="numeric" placeholder="50" {...field('weeklyOvertimePercent')} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Week starts
          <select aria-label="Week starts on" {...field('weekStartsOn')}>
            {WEEKDAYS.map((name, index) => <option key={name} value={String(index + 1)}>{name}</option>)}
          </select>
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Night from
          <input aria-label="Night start" type="time" {...field('nightStart')} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Night to
          <input aria-label="Night end" type="time" {...field('nightEnd')} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Night +%
          <input aria-label="Night percent" inputMode="numeric" placeholder="50" {...field('nightPercent')} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Holidays +%
          <input aria-label="Holiday percent" inputMode="numeric" placeholder="50" {...field('holidayPercent')} />
        </label>
        <label style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
          <input type="checkbox" aria-label="Rest days as holidays" checked={form.restDaysAsHolidays}
            onChange={(event) => setForm((current) => ({ ...current, restDaysAsHolidays: event.target.checked }))} />
          Rest days count as holidays
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Holiday over (h)
          <input aria-label="Holiday hours before more" inputMode="decimal" placeholder="8" {...field('holidayOvertimeAfterHours')} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Holiday over +%
          <input aria-label="Percent past the holiday hours" inputMode="numeric" placeholder="50" {...field('holidayOvertimePercent')} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Applies from
          <input aria-label="Premiums apply from" type="date" min={today} {...field('effectiveFrom')} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Reason
          <input aria-label="Premiums reason" maxLength={200} {...field('reason')} />
        </label>
        <button type="submit" disabled={pending}>Save premiums</button>
      </form>
      {problem && <p role="alert">{problem}</p>}
    </>
  )
}
