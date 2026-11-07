import { describe, expect, it } from 'vitest'
import { usableZone, zoneCaption, zoneTimeText } from './zoneTime'

const nineUtc = Date.UTC(2026, 10, 1, 9, 0)
const clock = { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' } as const

describe('times in the project time zone', () => {
  it('writes a time in the project zone, whatever the browser zone is', () => {
    expect(zoneTimeText(nineUtc, 'Asia/Seoul', clock)).toMatch(/18\D+00/)
    expect(zoneTimeText(nineUtc, 'UTC', clock)).toMatch(/09\D+00/)
    // 2026-11-01 09:00 UTC is after New York leaves daylight time (06:00 UTC), so it is 04:00 there.
    expect(zoneTimeText(nineUtc, 'America/New_York', clock)).toMatch(/04\D+00/)
  })

  it('falls back to the browser zone for a missing or unknown zone name and says so', () => {
    expect(usableZone('Asia/Seoul')).toBe('Asia/Seoul')
    expect(usableZone('Mars/Olympus')).toBeUndefined()
    expect(usableZone('')).toBeUndefined()
    expect(usableZone(undefined)).toBeUndefined()
    expect(zoneTimeText(nineUtc, undefined, clock)).toBe(new Date(nineUtc).toLocaleString(undefined, clock))
    expect(zoneCaption('Asia/Seoul')).toBe('Times in Asia/Seoul.')
    expect(zoneCaption(undefined)).toBe("Times in this browser's time zone.")
  })
})
