import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope, EquipmentDto } from '../../../shared/types/api'

export type DowntimeType = 'maintenance' | 'breakdown' | 'other'

/** A period the equipment cannot work (docs/domain/equipment-schedule.md). */
export interface EquipmentDowntimeDto {
  downtimeId: string
  downtimeType: DowntimeType
  startsAt: string
  endsAt: string
  hours: number
  reason: string | null
  createdBy: string | null
  createdAt: string | null
}

export interface EquipmentShiftDto {
  shiftId: string
  /** "09:00"; a shift that ends at or before its start runs past midnight, equal times are all day. */
  shiftStart: string
  shiftEnd: string
  /** ISO days the shift starts on, 1 = Monday. */
  workDays: number[]
  shiftHours: number
}

/** A date's own shifts in place of the calendar's (docs/domain/equipment-schedule.md "날짜별 교대"). */
export interface EquipmentDayDto {
  /** YYYY-MM-DD */
  date: string
  shifts: { shiftStart: string; shiftEnd: string; shiftHours: number }[]
  /** No shifts: the equipment does not work that day. */
  closed: boolean
  hours: number
  reason: string | null
  updatedBy: string | null
  updatedAt: string | null
}

/** One equipment's shifts and downtime. Without a calendar the equipment is available around the clock. */
export interface EquipmentScheduleDto {
  equipmentId: string
  /** The zone shift times are read in. */
  timeZone: string
  calendar: {
    /** Earliest start first; they never overlap. */
    shifts: EquipmentShiftDto[]
    /** All shifts on all their days, before holidays and downtime. */
    weeklyHours: number
    updatedBy: string | null
    updatedAt: string | null
  } | null
  /** Newest first. */
  downtimes: EquipmentDowntimeDto[]
  /** Dates whose shifts differ from the calendar's, earliest first. */
  days: EquipmentDayDto[]
  /** The calendar's versions, earliest first; {@code calendar} is today's (docs/architecture/adr/ADR-014-equipment-calendar-history.md). */
  versions?: EquipmentCalendarVersionLineDto[]
}

/** A calendar version from a date (null: from the start) until the next; without shifts the equipment runs around the clock. */
export interface EquipmentCalendarVersionLineDto {
  scheduleId: string
  /** YYYY-MM-DD; null is the version from the start. */
  effectiveFrom: string | null
  /** The version that applies today. */
  current: boolean
  alwaysRunning: boolean
  /** Why it was saved, and by whom when (docs/architecture/adr/ADR-017-version-reasons.md). */
  reason?: string | null
  updatedBy?: string | null
  updatedAt?: string | null
}

/** One calendar version with its shifts, to change one that has not started (ADR-014). */
export interface EquipmentCalendarVersionDto extends EquipmentCalendarVersionLineDto {
  shifts: EquipmentShiftDto[]
  weeklyHours: number
}

/** The shifts that start on one date; none closes the day. */
export interface EquipmentDayInput {
  shifts: { shiftStart: string; shiftEnd: string }[]
  reason: string | null
  /** The last date of a range given the same shifts (D6); none for one date. */
  throughDate?: string
  /** Only the range's dates on these days of the week (ISO, 1 = Monday); all of them when left out. */
  weekDays?: number[]
}

/** An equipment's own dates onto another equipment of the project (D7); without dates the range is open. */
export interface EquipmentDayCopyInput {
  toEquipmentId: string
  fromDate?: string
  throughDate?: string
}

/** Working time in [from, to): shifts in the window less the downtime inside them. */
export interface EquipmentAvailabilityDto {
  equipmentId: string
  from: string
  to: string
  calendarSet: boolean
  workingHours: number
  downtimeHours: number
  availableHours: number
  capacityPerHour: number | null
  /** Available hours times the capacity per hour; null without a capacity. */
  capacity: number | null
  /** Project holidays whose shift would have fallen in the window (YYYY-MM-DD). */
  holidays?: string[]
}

/** A date on which no calendar shift starts, for all of the project's equipment (docs/domain/equipment-schedule.md). */
export interface HolidayDto {
  holidayId: string
  /** YYYY-MM-DD */
  date: string
  name: string | null
  createdBy: string | null
}

/**
 * The equipment's shifts from {@code effectiveFrom}, today when left out (at most six; the server refuses overlaps). Earlier
 * dates keep the shifts they had (docs/architecture/adr/ADR-014-equipment-calendar-history.md).
 */
export interface EquipmentCalendarInput {
  shifts: { shiftStart: string; shiftEnd: string; workDays: number[] }[]
  /** YYYY-MM-DD, today or later. */
  effectiveFrom?: string
  /** Why, at most 200 characters (ADR-017). */
  reason?: string
}

export interface EquipmentDowntimeInput {
  downtimeType: DowntimeType
  /** ISO instants. */
  startsAt: string
  endsAt: string
  reason: string | null
}

const scheduleKey = (equipmentId: string) => ['equipment-schedule', equipmentId]

/** The project's equipment; the same cache the equipment tab uses. */
export function useEquipmentQuery(projectId: string) {
  return useQuery<EquipmentDto[]>({
    queryKey: ['equipment', projectId],
    queryFn: async () =>
      unwrapApiResponse(await httpClient.get<ApiEnvelope<EquipmentDto[]>>(`/equipments?projectId=${encodeURIComponent(projectId)}`)),
    enabled: Boolean(projectId),
  })
}

export function useEquipmentScheduleQuery(equipmentId: string) {
  return useQuery<EquipmentScheduleDto>({
    queryKey: scheduleKey(equipmentId),
    queryFn: async () =>
      unwrapApiResponse(
        await httpClient.get<ApiEnvelope<EquipmentScheduleDto>>(`/equipments/${encodeURIComponent(equipmentId)}/schedule`),
      ),
    enabled: Boolean(equipmentId),
  })
}

/** One save or removal of an equipment calendar version, with what it held then (docs/domain/equipment-schedule.md CV10). */
export interface EquipmentCalendarHistoryDto {
  historyId: string
  scheduleId: string
  /** YYYY-MM-DD; null for the version from the start. */
  effectiveFrom: string | null
  action: 'saved' | 'removed'
  reason: string | null
  /** None means around the clock from then. */
  shifts: { shiftStart: string; shiftEnd: string; workDays: number[] }[]
  savedBy: string
  savedAt: string
}

/** Loaded once asked for ({@code enabled}); every schedule change refreshes it. */
export function useEquipmentCalendarHistoryQuery(equipmentId: string, enabled: boolean) {
  return useQuery<EquipmentCalendarHistoryDto[]>({
    queryKey: [...scheduleKey(equipmentId), 'history'],
    enabled: enabled && Boolean(equipmentId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<EquipmentCalendarHistoryDto[]>>(
      `/equipments/${encodeURIComponent(equipmentId)}/calendar/history`)),
  })
}

/** One version of the equipment's calendar, to change one that has not started (ADR-014). */
export async function fetchEquipmentCalendarVersion(equipmentId: string, scheduleId: string): Promise<EquipmentCalendarVersionDto> {
  return unwrapApiResponse(await httpClient.get<ApiEnvelope<EquipmentCalendarVersionDto>>(
    `/equipments/${encodeURIComponent(equipmentId)}/calendar/versions/${encodeURIComponent(scheduleId)}`))
}

export function useEquipmentAvailabilityQuery(equipmentId: string, from: string, to: string) {
  return useQuery<EquipmentAvailabilityDto>({
    queryKey: [...scheduleKey(equipmentId), 'availability', from, to],
    queryFn: async () =>
      unwrapApiResponse(
        await httpClient.get<ApiEnvelope<EquipmentAvailabilityDto>>(
          `/equipments/${encodeURIComponent(equipmentId)}/availability?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
        ),
      ),
    enabled: Boolean(equipmentId && from && to),
  })
}

export function useHolidaysQuery(projectId: string) {
  return useQuery<HolidayDto[]>({
    queryKey: ['holidays', projectId],
    queryFn: async () =>
      unwrapApiResponse(await httpClient.get<ApiEnvelope<HolidayDto[]>>(`/holidays?projectId=${encodeURIComponent(projectId)}`)),
    enabled: Boolean(projectId),
  })
}

/** Every change returns the whole list; every equipment's available time, readiness and the load board are fetched again. */
export function useHolidayMutations(projectId: string) {
  const queryClient = useQueryClient()
  const onSuccess = (holidays: HolidayDto[]) => {
    queryClient.setQueryData(['holidays', projectId], holidays)
    void queryClient.invalidateQueries({ queryKey: ['equipment-schedule'] })
    void queryClient.invalidateQueries({ queryKey: ['work-order-readiness'] })
    void queryClient.invalidateQueries({ queryKey: ['equipment-load', projectId] })
  }
  return {
    add: useMutation({
      mutationFn: async (input: { date: string; name?: string }) =>
        unwrapApiResponse(await httpClient.post<ApiEnvelope<HolidayDto[]>>('/holidays', { projectId, ...input })),
      onSuccess,
    }),
    remove: useMutation({
      mutationFn: async (holidayId: string) =>
        unwrapApiResponse(await httpClient.delete<ApiEnvelope<HolidayDto[]>>(`/holidays/${encodeURIComponent(holidayId)}`)),
      onSuccess,
    }),
  }
}

/** Every change returns the new schedule; available time and work order readiness are fetched again. */
export function useEquipmentScheduleMutations(equipmentId: string) {
  const queryClient = useQueryClient()
  const base = `/equipments/${encodeURIComponent(equipmentId)}`
  const onSuccess = (schedule: EquipmentScheduleDto) => {
    queryClient.setQueryData(scheduleKey(equipmentId), schedule)
    void queryClient.invalidateQueries({ queryKey: [...scheduleKey(equipmentId), 'availability'] })
    void queryClient.invalidateQueries({ queryKey: [...scheduleKey(equipmentId), 'history'] })
    void queryClient.invalidateQueries({ queryKey: ['work-order-readiness'] })
  }
  return {
    setCalendar: useMutation({
      mutationFn: async (input: EquipmentCalendarInput) =>
        unwrapApiResponse(await httpClient.put<ApiEnvelope<EquipmentScheduleDto>>(`${base}/calendar`, input)),
      onSuccess,
    }),
    /** Around the clock from {@code effectiveFrom}, today when left out; earlier dates keep their shifts (ADR-014). */
    clearCalendar: useMutation({
      mutationFn: async (change?: { effectiveFrom?: string; reason?: string }) => {
        const query = new URLSearchParams()
        if (change?.effectiveFrom) query.set('effectiveFrom', change.effectiveFrom)
        if (change?.reason) query.set('reason', change.reason)
        const search = query.toString()
        return unwrapApiResponse(await httpClient.delete<ApiEnvelope<EquipmentScheduleDto>>(
          search ? `${base}/calendar?${search}` : `${base}/calendar`))
      },
      onSuccess,
    }),
    /** Deletes a calendar version that has not started; one that has started stays as history (ADR-014). */
    removeCalendarVersion: useMutation({
      mutationFn: async (scheduleId: string) => unwrapApiResponse(await httpClient.delete<ApiEnvelope<EquipmentScheduleDto>>(
        `${base}/calendar/versions/${encodeURIComponent(scheduleId)}`)),
      onSuccess,
    }),
    addDowntime: useMutation({
      mutationFn: async (input: EquipmentDowntimeInput) =>
        unwrapApiResponse(await httpClient.post<ApiEnvelope<EquipmentScheduleDto>>(`${base}/downtimes`, input)),
      onSuccess,
    }),
    setDay: useMutation({
      mutationFn: async ({ date, input }: { date: string; input: EquipmentDayInput }) =>
        unwrapApiResponse(await httpClient.put<ApiEnvelope<EquipmentScheduleDto>>(`${base}/days/${encodeURIComponent(date)}`, input)),
      onSuccess,
    }),
    clearDay: useMutation({
      mutationFn: async (date: string) =>
        unwrapApiResponse(await httpClient.delete<ApiEnvelope<EquipmentScheduleDto>>(`${base}/days/${encodeURIComponent(date)}`)),
      onSuccess,
    }),
    /** Every date of its own from {@code from} through {@code through} back to the calendar (D6). */
    clearDays: useMutation({
      mutationFn: async ({ from, through }: { from: string; through: string }) =>
        unwrapApiResponse(await httpClient.delete<ApiEnvelope<EquipmentScheduleDto>>(
          `${base}/days/${encodeURIComponent(from)}?through=${encodeURIComponent(through)}`,
        )),
      onSuccess,
    }),
    copyDays: useMutation({
      mutationFn: async (input: EquipmentDayCopyInput) =>
        unwrapApiResponse(await httpClient.post<ApiEnvelope<EquipmentScheduleDto>>(`${base}/days/copy`, input)),
      // The answer is the other equipment's schedule.
      onSuccess: (schedule: EquipmentScheduleDto) => {
        queryClient.setQueryData(scheduleKey(schedule.equipmentId), schedule)
        void queryClient.invalidateQueries({ queryKey: [...scheduleKey(schedule.equipmentId), 'availability'] })
        void queryClient.invalidateQueries({ queryKey: ['work-order-readiness'] })
      },
    }),
    removeDowntime: useMutation({
      mutationFn: async (downtimeId: string) =>
        unwrapApiResponse(
          await httpClient.delete<ApiEnvelope<EquipmentScheduleDto>>(`${base}/downtimes/${encodeURIComponent(downtimeId)}`),
        ),
      onSuccess,
    }),
  }
}
