import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import { draftToSpec, fetchDraftAvailability, requestDraft, type ProcessDraft } from '../model/draft'
import { buildSample } from '../model/samples'

/**
 * A first process from what the person makes, in their own words (docs/architecture/adr/ADR-047-process-draft-from-a-description.md,
 * ROADMAP §5 7 ①): the server drafts and checks it, the person reads it, and only then is it built in a new project the
 * same way as a sample. Shown only where the server has drafting turned on; the samples stay the way in without it, so a
 * first-time user is never offered a button that can only fail.
 */
export function StartFromDescription({ ownerId }: { ownerId: string | undefined }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [description, setDescription] = useState('')
  const availability = useQuery({ queryKey: ['process-draft-availability'], queryFn: fetchDraftAvailability, staleTime: 5 * 60 * 1000 })
  const draft = useMutation({ mutationFn: requestDraft })
  const build = useMutation({
    mutationFn: ({ value, owner }: { value: ProcessDraft; owner: string }) => buildSample(draftToSpec(value), owner),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['projects'] }),
    onSuccess: ({ projectId, workflowId }) => navigate(`/projects/${projectId}/workflows/${workflowId}`),
  })
  const name = (code: string) => draft.data?.items.find((item) => item.code === code)
  const flows = (list: { item: string; quantity: number }[]) =>
    list.map((flow) => `${name(flow.item)?.name ?? flow.item} ${flow.quantity} ${name(flow.item)?.unit ?? ''}`.trim()).join(' + ')

  if (availability.data !== true) return null

  return (
    <section aria-label="설명으로 시작" className="flex h-full min-w-0 flex-col rounded-2xl bg-[#1a1b24] p-5 sm:col-span-2">
      <p className="mb-1 text-[13px] font-semibold">설명으로 시작</p>
      <p className="mb-3 text-[12.5px] text-[#9a9bb0]">
        무엇을 어떻게 만드는지 적으면 공정과 재료의 초안을 만들어 보여 줍니다. 읽어 보고 만들기를 누르면 새 프로젝트에 생깁니다.
        적은 설명은 초안을 만드는 AI 서비스로 보내집니다.
      </p>
      <textarea aria-label="만드는 것" value={description} maxLength={2000} rows={3}
        onChange={(event) => setDescription(event.target.value)} placeholder="예: 원유에 메론 시럽을 섞어 200ml 병에 담고 24병씩 상자에 넣습니다."
        className="w-full rounded-lg bg-[#24253a] px-3 py-2 text-[13px] text-white ring-1 ring-[#33344a]" />
      <div className="mt-2 flex gap-2">
        <button type="button" disabled={!description.trim() || draft.isPending} onClick={() => draft.mutate(description.trim())}
          className="rounded-lg bg-[#7c5cff] px-3.5 py-2 text-[13px] font-semibold text-white disabled:opacity-50">
          {draft.isPending ? '초안을 만드는 중…' : '초안 만들기'}
        </button>
      </div>
      {draft.isError && (
        <p role="alert" className="mt-2 text-[12.5px] text-[#f09595]">
          {errorStatus(draft.error) === 503 ? '이 서버에는 설명으로 시작이 켜져 있지 않습니다. 견본으로 시작해 보세요.' : errorMessage(draft.error)}
        </p>
      )}
      {draft.data && (
        <div aria-label="초안" role="group" className="mt-3 text-[12.5px] text-[#c9c8d6]">
          <p className="font-semibold text-white">{draft.data.title}</p>
          <p className="mb-1 text-[#9a9bb0]">{draft.data.summary}</p>
          <ol className="list-decimal pl-5">
            {draft.data.steps.map((step) => (
              <li key={step.name}>{step.name}: {flows(step.inputs)} → {flows(step.outputs)}{step.minutes > 0 ? ` (${step.minutes}분)` : ''}</li>
            ))}
          </ol>
          <button type="button" disabled={!ownerId || build.isPending} onClick={() => ownerId && build.mutate({ value: draft.data!, owner: ownerId })}
            className="mt-2 rounded-lg bg-[#24253a] px-3.5 py-2 text-[13px] text-white ring-1 ring-[#33344a] hover:ring-[#7c5cff] disabled:opacity-50">
            {build.isPending ? '만드는 중…' : '이 초안으로 만들기'}
          </button>
          {build.isError && <p role="alert" className="mt-2 text-[#f09595]">초안을 만들다 멈췄습니다: {errorMessage(build.error)}</p>}
        </div>
      )}
    </section>
  )
}
