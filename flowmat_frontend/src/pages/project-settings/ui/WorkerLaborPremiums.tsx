import { useState } from 'react'
import { useLaborPremiumMutations, useLaborPremiumsQuery } from '../../../entities/workforce/api/useLaborPremiums'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { premiumRuleText } from '../model/premiumModel'
import { PremiumRuleForm } from './PremiumRuleForm'

/**
 * A worker's own pay premiums over the project's, for the project owner only (docs/architecture/adr/ADR-028-pay-premiums.md
 * decisions 20-22): their own saves from a date, or following the project's premiums again. Loaded when opened.
 */
export function WorkerLaborPremiums({ projectId, userId }: { projectId: string; userId: string }) {
  const [open, setOpen] = useState(false)
  const query = useLaborPremiumsQuery(projectId, open, userId)
  const { set, remove } = useLaborPremiumMutations(projectId, userId)
  const data = !query.isError && query.data && Array.isArray(query.data.lines) ? query.data : undefined
  const own = data?.current && !data.current.followProject ? data.current : null

  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)} style={{ marginTop: 8 }}>
      <summary>Own pay premiums</summary>
      {query.isError && <p role="alert">{errorMessage(query.error, "Could not load the worker's pay premiums.")}</p>}
      {data && <p aria-label="Worker pay premiums">{own ? premiumRuleText(own) : `${userId} follows the project's pay premiums.`}</p>}
      {data && data.lines.length > 0 && (
        <ul aria-label="Worker pay premium history" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {data.lines.map((line) => (
            <li key={line.ruleId}>
              {premiumRuleText(line)}
              {line.state === 'upcoming' && <>{' '}<button type="button" aria-label={`Remove ${userId}'s premiums from ${line.effectiveFrom}`}
                disabled={remove.isPending} onClick={() => remove.mutate(line.ruleId)}>Remove</button></>}
            </li>
          ))}
        </ul>
      )}
      {data && <PremiumRuleForm label={`Set ${userId}'s pay premiums`} today={data.today} pending={set.isPending}
        onSave={(payload, saved) => set.mutate(payload, { onSuccess: saved })} />}
      {data && own && <button type="button" disabled={set.isPending} onClick={() => set.mutate({ followProject: true })}>
        Follow the project's premiums</button>}
      {set.isError && <p role="alert">{errorMessage(set.error, 'Failed to save the premiums.')}</p>}
      {remove.isError && <p role="alert">{errorMessage(remove.error, 'Failed to remove the premiums.')}</p>}
      {data && <p className="inspector-hint">Own premiums replace the project's for this worker from their date; save with all empty for
        none.</p>}
    </details>
  )
}
