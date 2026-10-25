import { describe, expect, it } from 'vitest'
import { compareHistory, type HistoryEntry } from './compareHistory'

const base: HistoryEntry = {
  historyId: 'h1', effectiveFrom: null, action: 'saved', reason: null, savedAt: '2041-09-01T09:00:00Z', savedBy: 'kim',
  shifts: [{ shiftStart: '09:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5], rotaWeek: 1 }], rota: null,
}

describe('saved schedule comparison', () => {
  it('shows the settings that changed, including the rota anchor', () => {
    const next: HistoryEntry = { ...base, effectiveFrom: '2041-09-09', reason: 'Night shift',
      shifts: [{ shiftStart: '22:00', shiftEnd: '06:00', workDays: [1, 3], rotaWeek: 2 }],
      rota: { weeks: 2, startsOn: '2041-09-02' } }
    const rows = compareHistory(base, next, 'worker')
    expect(rows.filter((row) => row.changed).map((row) => row.field)).toEqual(['Applies from', 'Shifts', 'Rota', 'Note'])
    expect(rows.find((row) => row.field === 'Shifts')?.after).toBe('Week 2 · Mon, Wed 22:00–06:00')
    expect(rows.find((row) => row.field === 'Rota')?.after).toBe('Every 2 weeks from 2041-09-02')
  })

  it('normalizes ordering and equivalent time spellings without changing the inputs', () => {
    const before = { ...base, shifts: [...base.shifts, { shiftStart: '18:00', shiftEnd: '20:00', workDays: [2], rotaWeek: 1 }] }
    const after = { ...before, shifts: [before.shifts[1], { ...before.shifts[0], workDays: [5, 4, 3, 2, 1], shiftStart: '09:00:00.000' }] }
    const original = JSON.stringify([before, after])
    expect(compareHistory(before, after, 'worker').every((row) => !row.changed)).toBe(true)
    expect(JSON.stringify([before, after])).toBe(original)
  })

  it('keeps meaningful subsecond differences', () => {
    const after = { ...base, shifts: [{ ...base.shifts[0], shiftStart: '09:00:00.000000001' }] }
    const row = compareHistory(base, after, 'worker').find((one) => one.field === 'Shifts')!
    expect(row.changed).toBe(true)
    expect(row.after).toContain('09:00:00.000000001')
  })

  it('distinguishes an equipment calendar without shifts from a worker without shifts', () => {
    expect(compareHistory(base, { ...base, shifts: [] }, 'equipment').find((row) => row.field === 'Shifts')?.after)
      .toBe('Around the clock')
    expect(compareHistory(base, { ...base, shifts: [] }, 'worker').find((row) => row.field === 'Shifts')?.after).toBe('No shifts')
    expect(compareHistory(base, base, 'equipment').some((row) => row.field === 'Rota')).toBe(false)
  })

  it('shows a removal separately from the snapshot it retained', () => {
    const rows = compareHistory(base, { ...base, action: 'removed' }, 'worker')
    expect(rows.filter((row) => row.changed)).toEqual([{ field: 'Action', before: 'Saved', after: 'Removed', changed: true }])
  })

  it('treats different save metadata as the same settings', () => {
    const next = { ...base, historyId: 'h2', savedBy: 'lee', savedAt: '2041-09-02T09:00:00Z' }
    expect(compareHistory(base, next, 'worker').every((row) => !row.changed)).toBe(true)
  })

  it('includes a change to the rota start even when the cycle is unchanged', () => {
    const before = { ...base, rota: { weeks: 2, startsOn: '2041-09-02' } }
    const after = { ...before, rota: { weeks: 2, startsOn: '2041-09-09' } }
    expect(compareHistory(before, after, 'worker').filter((row) => row.changed).map((row) => row.field)).toEqual(['Rota'])
  })

  it('keeps literal note content and swaps the direction of differences', () => {
    const next = { ...base, reason: '<script>hello</script>' }
    expect(compareHistory(base, next, 'equipment').find((row) => row.field === 'Note'))
      .toEqual({ field: 'Note', before: 'None', after: next.reason, changed: true })
    expect(compareHistory(next, base, 'equipment').find((row) => row.field === 'Note')?.after).toBe('None')
  })
})
