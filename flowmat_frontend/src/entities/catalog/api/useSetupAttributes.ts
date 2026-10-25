import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export interface ItemSetupAttributesDto { itemId: string; attributes: Record<string, string>; version: number }
export interface SetupChangeoverDto {
  changeoverId: string; equipmentId: string; fromAttributes: Record<string, string>; toAttributes: Record<string, string>
  priority: number; minutes: number; note: string | null; version: number
}
export interface SetupChangeoverInput {
  fromAttributes: Record<string, string>; toAttributes: Record<string, string>; priority: number; minutes: number
  note: string | null; expectedVersion: number
}
const attrsKey = (id: string) => ['item-setup-attributes', id]
const ruleKey = (id: string) => ['equipment-setup-changeovers', id]
const attrsPath = (id: string) => `/items/${encodeURIComponent(id)}/setup-attributes`
const rulePath = (id: string) => `/equipments/${encodeURIComponent(id)}/setup-changeovers`
function map(value: unknown): value is Record<string, string> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value)
    && Object.entries(value).length <= 20 && Object.entries(value).every(([name, one]) =>
      name.length > 0 && name.length <= 50 && name.trim() === name && !['__proto__', 'prototype', 'constructor'].includes(name)
      // eslint-disable-next-line no-control-regex -- Reject control characters in stored text.
      && !/[\u0000-\u001f\u007f]|[\ud800-\udbff](?![\udc00-\udfff])|(?:^|[^\ud800-\udbff])[\udc00-\udfff]/.test(name) && typeof one === 'string' && one.trim() === one && one.length > 0
      // eslint-disable-next-line no-control-regex -- Reject control characters in stored text.
      && one.length <= 100 && !/[\u0000-\u001f\u007f]|[\ud800-\udbff](?![\udc00-\udfff])|(?:^|[^\ud800-\udbff])[\udc00-\udfff]/.test(one)))
}
function attrs(envelope: ApiEnvelope<ItemSetupAttributesDto>, id: string) {
  const value = unwrapApiResponse(envelope)
  if (!value || value.itemId !== id || !Number.isSafeInteger(value.version) || value.version < 0 || !map(value.attributes))
    throw new Error('Invalid setup attributes response. Reload current attributes.')
  return value
}
function rule(value: SetupChangeoverDto, id: string): SetupChangeoverDto {
  if (!value || value.equipmentId !== id || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.changeoverId)
    || !Number.isSafeInteger(value.version) || value.version < 1 || !map(value.fromAttributes) || !map(value.toAttributes)
    || Object.keys(value.fromAttributes).length + Object.keys(value.toAttributes).length === 0
    || !Number.isInteger(value.priority) || value.priority < 1 || value.priority > 100000
    || !Number.isInteger(value.minutes) || value.minutes < 1 || value.minutes > 10080
    // eslint-disable-next-line no-control-regex -- Reject control characters in stored text.
    || (value.note !== null && (typeof value.note !== 'string' || value.note.length > 500 || /\u0000|[\ud800-\udbff](?![\udc00-\udfff])|(?:^|[^\ud800-\udbff])[\udc00-\udfff]/.test(value.note))))
    throw new Error('Invalid setup changeover response. Reload current rules.')
  return value
}
function rules(envelope: ApiEnvelope<SetupChangeoverDto[]>, id: string) {
  const values = unwrapApiResponse(envelope)
  if (!Array.isArray(values)) throw new Error('Invalid setup changeover response. Reload current rules.')
  const confirmed = values.map((value) => rule(value, id))
  if (new Set(confirmed.map((one) => one.priority)).size !== confirmed.length
    || new Set(confirmed.map((one) => one.changeoverId)).size !== confirmed.length)
    throw new Error('Invalid setup changeover response. Reload current rules.')
  return confirmed.sort((a, b) => a.priority - b.priority)
}
function invalidate(client: ReturnType<typeof useQueryClient>, projectId: string) {
  for (const key of [['equipment-load', projectId], ['work-order-readiness'], ['work-order-plan'], ['equipment-setup-preview']])
    void client.invalidateQueries({ queryKey: key })
}
export function useItemSetupAttributes(itemId: string, projectId: string) {
  const client = useQueryClient()
  const query = useQuery({ queryKey: attrsKey(itemId), retry: false,
    queryFn: async () => attrs(await httpClient.get<ApiEnvelope<ItemSetupAttributesDto>>(attrsPath(itemId)), itemId) })
  const save = useMutation({ retry: false,
    mutationFn: async (input: { attributes: Record<string, string>; expectedVersion: number }) =>
      attrs(await httpClient.put<ApiEnvelope<ItemSetupAttributesDto>>(attrsPath(itemId), input), itemId),
    onMutate: () => client.cancelQueries({ queryKey: attrsKey(itemId) }),
    onSuccess: (value) => { client.setQueryData(attrsKey(itemId), value); invalidate(client, projectId) },
  })
  return { query, save }
}
export function useSetupChangeovers(equipmentId: string, projectId: string) {
  const client = useQueryClient()
  const query = useQuery({ queryKey: ruleKey(equipmentId), retry: false,
    queryFn: async () => rules(await httpClient.get<ApiEnvelope<SetupChangeoverDto[]>>(rulePath(equipmentId)), equipmentId) })
  const save = useMutation({ retry: false,
    mutationFn: async ({ changeoverId, ...input }: SetupChangeoverInput & { changeoverId: string }) => {
      const value = rule(unwrapApiResponse(await httpClient.put<ApiEnvelope<SetupChangeoverDto>>(
        `${rulePath(equipmentId)}/${encodeURIComponent(changeoverId)}`, input)), equipmentId)
      if (value.changeoverId !== changeoverId) throw new Error('Invalid setup changeover identity. Reload current rules.')
      return value
    },
    onMutate: () => client.cancelQueries({ queryKey: ruleKey(equipmentId) }),
    onSuccess: (value) => {
      client.setQueryData<SetupChangeoverDto[]>(ruleKey(equipmentId), (old) => [...(old ?? []).filter((one) => one.changeoverId !== value.changeoverId), value].sort((a, b) => a.priority - b.priority))
      invalidate(client, projectId)
    },
  })
  const remove = useMutation({ retry: false,
    mutationFn: async (value: SetupChangeoverDto) => rules(await httpClient.delete<ApiEnvelope<SetupChangeoverDto[]>>(
      `${rulePath(equipmentId)}/${encodeURIComponent(value.changeoverId)}?expectedVersion=${value.version}`), equipmentId),
    onMutate: () => client.cancelQueries({ queryKey: ruleKey(equipmentId) }),
    onSuccess: (values) => { client.setQueryData(ruleKey(equipmentId), values); invalidate(client, projectId) },
  })
  return { query, save, remove }
}
