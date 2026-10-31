import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** One save of the project's pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md); an empty premium is not paid. */
export interface LaborPremiumLineDto {
  ruleId: string
  /** YYYY-MM-DD, a project date. */
  effectiveFrom: string
  overtimeAfterMinutes: number | null
  overtimePercent: number | null
  /** "22:00" in project time; the window runs past midnight when it ends earlier. */
  nightStart: string | null
  nightEnd: string | null
  nightPercent: number | null
  holidayPercent: number | null
  /** Regular minutes of a week past which weeklyOvertimePercent is added; the week starts on weekStartsOn (1 Monday to 7 Sunday). */
  weeklyOvertimeAfterMinutes: number | null
  weeklyOvertimePercent: number | null
  weekStartsOn: number | null
  /** A work day without any of the worker's scheduled time gets holidayPercent too. */
  restDaysAsHolidays: boolean
  /** Minutes of a holiday past which holidayOvertimePercent is added to holidayPercent, in place of the daily overtime. */
  holidayOvertimeAfterMinutes: number | null
  holidayOvertimePercent: number | null
  /** A worker's own save that returns them to the project's premiums from its date; it has none. */
  followProject?: boolean
  reason: string | null
  setBy: string
  setAt: string
  /** past: a later start took over; replaced: saved again for the same start. */
  state: 'current' | 'upcoming' | 'past' | 'replaced' | 'removed'
  removedBy: string | null
  removedAt: string | null
}

export interface LaborPremiumsDto {
  projectId: string
  /** The worker whose own premiums these are; null for the project's. */
  userId?: string | null
  /** The project's date. */
  today: string
  current: LaborPremiumLineDto | null
  /** Newest save first, removed ones included. */
  lines: LaborPremiumLineDto[]
}

/** What a save sends; a premium left out is not paid. */
export interface LaborPremiumInput {
  effectiveFrom?: string
  overtimeAfterMinutes?: number
  overtimePercent?: number
  nightStart?: string
  nightEnd?: string
  nightPercent?: number
  holidayPercent?: number
  weeklyOvertimeAfterMinutes?: number
  weeklyOvertimePercent?: number
  weekStartsOn?: number
  restDaysAsHolidays?: boolean
  holidayOvertimeAfterMinutes?: number
  holidayOvertimePercent?: number
  followProject?: boolean
  reason?: string
}

/** One work day of a worker's attendance as pay. */
export interface AttendancePayDayDto {
  workDate: string
  attendedMinutes: number
  overtimeMinutes: number
  /** Regular minutes of the day past the weekly overtime start of its week. */
  weeklyOvertimeMinutes?: number
  /** On a holiday, minutes past the holiday overtime start (then overtimeMinutes is 0). */
  holidayOvertimeMinutes?: number
  nightMinutes: number
  holiday: boolean
  /** The worker had no scheduled time that day and the day's rule pays that like a holiday. */
  restDayHoliday?: boolean
  premiumMinutes: number
  paidMinutes: number
  ruleId: string | null
  hourlyRate: number | null
  /** Null without a rate. */
  amount: number | null
}

export interface AttendancePayDto {
  projectId: string
  userId: string
  from: string
  to: string
  /** Work days with attendance, oldest first. */
  days: AttendancePayDayDto[]
  attendedMinutes: number
  premiumMinutes: number
  paidMinutes: number
  /** The known amounts added up. */
  amount: number
  costComplete: boolean
}

/** The project's premiums, or with {@code userId} that worker's own (decision 20). */
const path = (projectId: string, userId?: string) => userId
  ? `/projects/${encodeURIComponent(projectId)}/workers/${encodeURIComponent(userId)}/labor-premiums`
  : `/projects/${encodeURIComponent(projectId)}/labor-premiums`

/** The project owner's only (ADR-028); ask for it only for them. With {@code userId}, that worker's own premiums. */
export function useLaborPremiumsQuery(projectId: string, enabled: boolean, userId?: string) {
  return useQuery<LaborPremiumsDto>({
    queryKey: ['labor-premiums', projectId, userId ?? ''],
    enabled: enabled && Boolean(projectId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<LaborPremiumsDto>>(path(projectId, userId))),
  })
}

/** Each change answers with the premiums; pay and labor cost are read again. */
export function useLaborPremiumMutations(projectId: string, userId?: string) {
  const client = useQueryClient()
  const onSuccess = (premiums: LaborPremiumsDto) => {
    client.setQueryData(['labor-premiums', projectId, userId ?? ''], premiums)
    void client.invalidateQueries({ queryKey: ['attendance-pay'] })
    void client.invalidateQueries({ queryKey: ['labor-cost'] })
  }
  return {
    set: useMutation({
      mutationFn: async (input: LaborPremiumInput) =>
        unwrapApiResponse(await httpClient.put<ApiEnvelope<LaborPremiumsDto>>(path(projectId, userId), input)),
      onSuccess,
    }),
    remove: useMutation({
      mutationFn: async (ruleId: string) => unwrapApiResponse(await httpClient.delete<ApiEnvelope<LaborPremiumsDto>>(
        `${path(projectId, userId)}/${encodeURIComponent(ruleId)}`)),
      onSuccess,
    }),
  }
}

/** A worker's attendance as pay, the owner's only; without a range the 7 days to today. */
export function useAttendancePayQuery(projectId: string, userId: string, range: { from: string; to: string } | null) {
  return useQuery<AttendancePayDto>({
    queryKey: ['attendance-pay', projectId, userId, range?.from ?? '', range?.to ?? ''],
    enabled: Boolean(projectId) && Boolean(userId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<AttendancePayDto>>(
      `/projects/${encodeURIComponent(projectId)}/workers/${encodeURIComponent(userId)}/attendance-pay`
      + (range ? `?from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}` : ''))),
  })
}
