import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export interface WorkerShiftDto {
  shiftId: string
  /** "HH:mm" */
  shiftStart: string
  shiftEnd: string
  /** ISO days, 1 (Monday) to 7 (Sunday). */
  workDays: number[]
  hours: number
  /** The rota week it runs in; 1 without a rota (docs/domain/worker-calendar.md WC44). Older answers leave it out. */
  rotaWeek?: number
}

/** How a worker's shifts repeat (WC44): every {@code weeks} weeks from the Monday startsOn; currentWeek is today's. */
export interface WorkerRotaDto {
  weeks: number
  startsOn: string
  currentWeek: number
}

export interface WorkerDayOffDto {
  dayOffId: string
  firstDay: string
  lastDay: string
  reason: string | null
  createdBy: string
}

/** A date's own shifts in place of the weekly ones (docs/domain/worker-calendar.md WC29); off without any. */
export interface WorkerDayDto {
  /** YYYY-MM-DD */
  date: string
  shifts: { shiftStart: string; shiftEnd: string; hours: number }[]
  off: boolean
  hours: number
  reason: string | null
  updatedBy: string
}

/** One version of a worker's weekly shifts and rota: from the start (null) or a date, until the next (ADR-013). */
export interface WorkerScheduleLineDto {
  scheduleId: string
  /** YYYY-MM-DD; null for the version from the start. */
  effectiveFrom: string | null
  /** The version that applies today. */
  current: boolean
  /** Why it was saved, and by whom when (docs/architecture/adr/ADR-017-version-reasons.md). */
  reason?: string | null
  updatedBy?: string | null
  updatedAt?: string | null
}

/** One version in full (docs/architecture/adr/ADR-013-rota-history.md). */
export interface WorkerScheduleDto extends WorkerScheduleLineDto {
  calendarSet: boolean
  weeklyHours: number
  shifts: WorkerShiftDto[]
  rota: WorkerRotaDto | null
}

/** A worker's calendar in a project (docs/domain/worker-calendar.md WC5). */
export interface WorkerCalendarDto {
  projectId: string
  userId: string
  owner: boolean
  calendarSet: boolean
  weeklyHours: number
  shifts: WorkerShiftDto[]
  daysOff: WorkerDayOffDto[]
  /** Dates with their own shifts from 31 days ago on (WC29). */
  days?: WorkerDayDto[]
  /** Null or left out for one week (WC44). */
  rota?: WorkerRotaDto | null
  /** The versions, earliest first; shifts and rota above are today's (ADR-013). Older answers leave it out. */
  schedules?: WorkerScheduleLineDto[]
}

export type WorkerShiftInput = { shiftStart: string; shiftEnd: string; workDays: number[]; rotaWeek?: number }

/** One date's shifts, or none for a day without work; a range with the same shifts when throughDate is given. */
export interface WorkerDayInput {
  shifts: { shiftStart: string; shiftEnd: string }[]
  reason: string | null
  throughDate?: string
  weekDays?: number[]
}

const key = (projectId: string) => ['worker-calendars', projectId]
const base = (projectId: string) => `/projects/${encodeURIComponent(projectId)}/workers`

export function useWorkerCalendarsQuery(projectId: string) {
  return useQuery<WorkerCalendarDto[]>({
    queryKey: key(projectId),
    enabled: Boolean(projectId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkerCalendarDto[]>>(base(projectId))),
  })
}

/** One save or removal of a worker's schedule version, with what it held then (docs/domain/worker-calendar.md WC63). */
export interface WorkerScheduleHistoryDto {
  historyId: string
  scheduleId: string
  /** YYYY-MM-DD; null for the version from the start. */
  effectiveFrom: string | null
  action: 'saved' | 'removed'
  reason: string | null
  shifts: { shiftStart: string; shiftEnd: string; workDays: number[]; rotaWeek: number }[]
  rota: { weeks: number; startsOn: string } | null
  savedBy: string
  savedAt: string
}

/** Loaded once asked for ({@code enabled}); every calendar change refreshes it with the calendars (same key prefix). */
export function useWorkerScheduleHistoryQuery(projectId: string, userId: string, enabled: boolean) {
  return useQuery<WorkerScheduleHistoryDto[]>({
    queryKey: [...key(projectId), 'history', userId],
    enabled: enabled && Boolean(projectId) && Boolean(userId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkerScheduleHistoryDto[]>>(
      `${base(projectId)}/${encodeURIComponent(userId)}/schedule-history`)),
  })
}

/** One save or removal of a worker's own dates or day off, with what it held (docs/domain/worker-calendar.md WC65). */
export interface WorkerDayHistoryDto {
  historyId: string
  kind: 'day' | 'day_off'
  action: 'saved' | 'removed'
  /** YYYY-MM-DD */
  firstDate: string
  lastDate: string
  /** For a day change, each date with its shifts (none: no work) and reason; empty for a day off. */
  days: { date: string; shifts: { shiftStart: string; shiftEnd: string }[]; reason: string | null }[]
  dayOffId: string | null
  reason: string | null
  /** The worker the dates were copied from. */
  copiedFrom: string | null
  savedBy: string
  savedAt: string
}

/** Loaded once asked for ({@code enabled}); every calendar change refreshes it with the calendars (same key prefix). */
export function useWorkerDayHistoryQuery(projectId: string, userId: string, enabled: boolean) {
  return useQuery<WorkerDayHistoryDto[]>({
    queryKey: [...key(projectId), 'day-history', userId],
    enabled: enabled && Boolean(projectId) && Boolean(userId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkerDayHistoryDto[]>>(
      `${base(projectId)}/${encodeURIComponent(userId)}/day-history`)),
  })
}

function useRefresh(projectId: string) {
  const client = useQueryClient()
  return () => {
    void client.invalidateQueries({ queryKey: key(projectId) })
    void client.invalidateQueries({ queryKey: ['work-order-readiness'] })
    void client.invalidateQueries({ queryKey: ['worker-load', projectId] })
  }
}

export function useSetWorkerShiftsMutation(projectId: string) {
  const onSuccess = useRefresh(projectId)
  return useMutation({
    /** effectiveFrom: the date they apply from; left out, today (ADR-013). reason: why, at most 200 characters (ADR-017). */
    mutationFn: async ({ userId, shifts, rota, effectiveFrom, reason }: {
      userId: string; shifts: WorkerShiftInput[]; rota?: { weeks: number; startsOn: string } | null; effectiveFrom?: string
      reason?: string
    }) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<WorkerCalendarDto>>(
        `${base(projectId)}/${encodeURIComponent(userId)}/shifts`,
        { shifts, ...(rota ? { rota } : {}), ...(effectiveFrom ? { effectiveFrom } : {}), ...(reason ? { reason } : {}) })),
    onSuccess,
  })
}

export function useAddWorkerDayOffMutation(projectId: string) {
  const onSuccess = useRefresh(projectId)
  return useMutation({
    mutationFn: async ({ userId, ...input }: { userId: string; firstDay: string; lastDay: string; reason?: string }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<WorkerCalendarDto>>(
        `${base(projectId)}/${encodeURIComponent(userId)}/days-off`, input)),
    onSuccess,
  })
}

export function useSetWorkerDayMutation(projectId: string) {
  const onSuccess = useRefresh(projectId)
  return useMutation({
    mutationFn: async ({ userId, date, input }: { userId: string; date: string; input: WorkerDayInput }) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<WorkerCalendarDto>>(
        `${base(projectId)}/${encodeURIComponent(userId)}/days/${encodeURIComponent(date)}`, input)),
    onSuccess,
  })
}

export function useRemoveWorkerDayMutation(projectId: string) {
  const onSuccess = useRefresh(projectId)
  return useMutation({
    mutationFn: async ({ userId, date }: { userId: string; date: string }) =>
      unwrapApiResponse(await httpClient.delete<ApiEnvelope<WorkerCalendarDto>>(
        `${base(projectId)}/${encodeURIComponent(userId)}/days/${encodeURIComponent(date)}`)),
    onSuccess,
  })
}

/**
 * Copies a worker's own dates to another worker, replacing those dates there; their other dates stay
 * (docs/domain/worker-calendar.md WC41). The answer is the other worker's calendar.
 */
export function useCopyWorkerDaysMutation(projectId: string) {
  const onSuccess = useRefresh(projectId)
  return useMutation({
    mutationFn: async ({ userId, ...input }: { userId: string; toUserId: string; fromDate?: string; throughDate?: string }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<WorkerCalendarDto>>(
        `${base(projectId)}/${encodeURIComponent(userId)}/days/copy`, input)),
    onSuccess,
  })
}

/**
 * Gives other workers one worker's weekly shifts and rota, each {@code offsetWeeks} further on in it, so teams take turns
 * (docs/domain/worker-calendar.md WC51). Their own weekly shifts are replaced; their day changes and days off stay.
 */
export function useCopyWorkerShiftsMutation(projectId: string) {
  const onSuccess = useRefresh(projectId)
  return useMutation({
    /** effectiveFrom: the date the copies apply from; left out, today (ADR-013). */
    mutationFn: async ({ userId, targets, effectiveFrom }: {
      userId: string; targets: { userId: string; offsetWeeks: number }[]; effectiveFrom?: string
    }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<WorkerCalendarDto[]>>(
        `${base(projectId)}/${encodeURIComponent(userId)}/shifts/copy`, effectiveFrom ? { targets, effectiveFrom } : { targets })),
    onSuccess,
  })
}

export function useRemoveWorkerDayOffMutation(projectId: string) {
  const onSuccess = useRefresh(projectId)
  return useMutation({
    mutationFn: async ({ userId, dayOffId }: { userId: string; dayOffId: string }) =>
      unwrapApiResponse(await httpClient.delete<ApiEnvelope<WorkerCalendarDto>>(
        `${base(projectId)}/${encodeURIComponent(userId)}/days-off/${encodeURIComponent(dayOffId)}`)),
    onSuccess,
  })
}

/** One version of the worker's weekly shifts and rota, to edit one that has not started (ADR-013). */
export async function fetchWorkerSchedule(projectId: string, userId: string, scheduleId: string): Promise<WorkerScheduleDto> {
  return unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkerScheduleDto>>(
    `${base(projectId)}/${encodeURIComponent(userId)}/schedules/${encodeURIComponent(scheduleId)}`))
}

/** Deletes a version that has not started; one that has started stays as history (ADR-013 decision 4). */
export function useRemoveWorkerScheduleMutation(projectId: string) {
  const onSuccess = useRefresh(projectId)
  return useMutation({
    mutationFn: async ({ userId, scheduleId }: { userId: string; scheduleId: string }) =>
      unwrapApiResponse(await httpClient.delete<ApiEnvelope<WorkerCalendarDto>>(
        `${base(projectId)}/${encodeURIComponent(userId)}/schedules/${encodeURIComponent(scheduleId)}`)),
    onSuccess,
  })
}
