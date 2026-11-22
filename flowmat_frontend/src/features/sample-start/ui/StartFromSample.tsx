import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { buildMelonMilkSample, SAMPLE_STEPS, type SampleStep } from '../model/melonMilkSample'

const STEP_LABEL: Record<SampleStep, string> = {
  Project: '프로젝트', Items: '품목', Stock: '재고', Equipment: '설비', Process: '공정', Publish: '게시',
}

/**
 * Starts a first-time user on a ready process (docs/status/ROADMAP-2026-11-23.md §6 first run): builds the melon milk sample
 * in a new project and opens it, so the Plan panel answers at once.
 */
export function StartFromSample({ ownerId }: { ownerId: string | undefined }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [step, setStep] = useState<SampleStep | null>(null)
  const build = useMutation({
    mutationFn: (owner: string) => buildMelonMilkSample(owner, setStep),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['projects'] }),
    onSuccess: ({ projectId, workflowId }) => navigate(`/projects/${projectId}/workflows/${workflowId}`),
  })

  return (
    <section aria-label="견본으로 시작"
      className="flex h-full min-w-0 flex-col rounded-2xl bg-[#1a1b24] p-5 sm:col-span-2">
      <p className="mb-1 text-[13px] font-semibold">견본으로 시작</p>
      <p className="mb-3 text-[12.5px] text-[#9a9bb0]">
        메론우유: 원유 입고 → 배합 → 충전 → 포장. 품목·재고·설비·시간이 채워져 있고 1,000병에는 원유가 모자라서, 열자마자
        Plan 패널이 부족분과 지금 만들 수 있는 양을 보여 줍니다.
      </p>
      <div className="flex flex-wrap items-center gap-3">
        <button type="button" disabled={!ownerId || build.isPending} onClick={() => ownerId && build.mutate(ownerId)}
          className="rounded-lg bg-[#7c5cff] px-3.5 py-2 text-[13px] font-semibold text-white disabled:opacity-50">
          {build.isPending ? '견본을 만드는 중...' : '메론우유 견본 만들기'}
        </button>
        {build.isPending && step && (
          <span role="status" className="text-[12.5px] text-[#9a9bb0]">
            {STEP_LABEL[step]} ({SAMPLE_STEPS.indexOf(step) + 1}/{SAMPLE_STEPS.length})
          </span>
        )}
      </div>
      {build.isError && (
        <p role="alert" className="mt-2 text-[12.5px] text-[#f09595]">
          견본이 {step ? STEP_LABEL[step] : '시작'} 단계에서 멈췄습니다: {errorMessage(build.error)}
        </p>
      )}
    </section>
  )
}
