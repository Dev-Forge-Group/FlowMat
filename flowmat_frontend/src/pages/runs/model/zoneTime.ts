/**
 * Times on a run's time axis as the project reads them (archify's timeline keeps one axis in the plan's time zone): in the
 * project's time zone when it is known and the browser knows its name, else in the browser's own, with a caption that
 * says which.
 */
export function usableZone(timeZone: string | null | undefined): string | undefined {
  if (!timeZone) return undefined
  try {
    new Intl.DateTimeFormat(undefined, { timeZone })
    return timeZone
  } catch {
    return undefined
  }
}

export function zoneTimeText(time: number, zone: string | undefined, options: Intl.DateTimeFormatOptions = {}): string {
  return new Date(time).toLocaleString(undefined, zone ? { ...options, timeZone: zone } : options)
}

export function zoneCaption(zone: string | undefined): string {
  return zone ? `Times in ${zone}.` : "Times in this browser's time zone."
}
