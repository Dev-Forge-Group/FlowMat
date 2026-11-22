import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { buildSample, SAMPLE_STEPS, SAMPLES, type SampleSpec, type SampleStep } from '../model/samples'

const STEP_LABEL: Record<SampleStep, string> = {
  Project: '프로젝트', Items: '품목', Stock: '재고', Equipment: '설비', Process: '공정', Publish: '게시',
}

/**
 * Starts a first-time user on a ready process (docs/status/ROADMAP-2026-11-23.md §6 first run): builds an industry sample
 * in a new project and opens it, so the Plan panel answers at once.
 */
export function StartFromSample({ ownerId }: { ownerId: string | undefined }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [step, setStep] = useState<SampleStep | null>(null)
  const build = useMutation({
    mutationFn: ({ spec, owner }: { spec: SampleSpec; owner: string }) => buildSample(spec, owner, setStep),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['projects'] }),
    onSuccess: ({ projectId, workflowId }) => navigate(`/projects/${projectId}/workflows/${workflowId}`),
  })
  const building = build.isPending ? build.variables?.spec : undefined

  return (
    <section aria-label="견본으로 시작"
      className="flex h-full min-w-0 flex-col rounded-2xl bg-[#1a1b24] p-5 sm:col-span-2">
      <p className="mb-1 text-[13px] font-semibold">견본으로 시작</p>
      <p className="mb-3 text-[12.5px] text-[#9a9bb0]">
        품목·재고·설비·시간이 채워진 공정을 새 프로젝트에 만들고 엽니다. 재료 하나가 모자라게 해 두어서, 열자마자 Plan 패널이
        부족분과 지금 만들 수 있는 양을 보여 줍니다.
      </p>
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {SAMPLES.map((spec) => (
          <button key={spec.key} type="button" disabled={!ownerId || build.isPending}
            onClick={() => ownerId && build.mutate({ spec, owner: ownerId })}
            className="rounded-lg bg-[#24253a] px-3.5 py-2.5 text-left text-[13px] text-white ring-1 ring-[#33344a] transition hover:ring-[#7c5cff] disabled:opacity-50">
            <span className="block font-semibold">{spec.title} 견본 만들기</span>
            <span className="block text-[12px] text-[#9a9bb0]">{spec.summary}</span>
          </button>
        ))}
      </div>
      {building && step && (
        <p role="status" className="mt-2 text-[12.5px] text-[#9a9bb0]">
          {building.title} 견본을 만드는 중: {STEP_LABEL[step]} ({SAMPLE_STEPS.indexOf(step) + 1}/{SAMPLE_STEPS.length})
        </p>
      )}
      {build.isError && (
        <p role="alert" className="mt-2 text-[12.5px] text-[#f09595]">
          견본이 {step ? STEP_LABEL[step] : '시작'} 단계에서 멈췄습니다: {errorMessage(build.error)}
        </p>
      )}
    </section>
  )
}
