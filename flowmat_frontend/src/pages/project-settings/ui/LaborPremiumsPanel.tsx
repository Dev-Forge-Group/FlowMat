import { useCurrentUserQuery } from '../../../entities/auth/api/useCurrentUserQuery'
import { useProjectMembersQuery } from '../../../entities/project/api/useProjectMembersQuery'
import { useLaborPremiumMutations, useLaborPremiumsQuery } from '../../../entities/workforce/api/useLaborPremiums'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { premiumRuleText } from '../model/premiumModel'
import { PremiumRuleForm } from './PremiumRuleForm'

/**
 * The project's pay premiums for overtime, night and holiday work, for the project owner only
 * (docs/architecture/adr/ADR-028-pay-premiums.md). A save applies from today or a later project date and earlier days keep
 * theirs; one that has not started can be removed. Others are not asked for them. A worker's own premiums are on their card.
 */
export function LaborPremiumsPanel({ projectId }: { projectId: string }) {
  const { data: members = [] } = useProjectMembersQuery(projectId)
  const { data: user } = useCurrentUserQuery()
  const owner = members.some((member) => member.userId === user?.userId && member.projectRole === 'owner')
  const query = useLaborPremiumsQuery(projectId, owner)
  const { set, remove } = useLaborPremiumMutations(projectId)
  const data = !query.isError && query.data && Array.isArray(query.data.lines) ? query.data : undefined

  return (
    <section aria-label="Pay premiums" style={{ border: '1px solid var(--border)', borderRadius: 16, padding: 24, minWidth: 0 }}>
      <h2>Pay premiums</h2>
      <p>Overtime, night and holiday work paid above the hourly rate, worked out from attendance.</p>
      {!owner && <p>Only the project owner sees and sets pay premiums.</p>}
      {owner && query.isError && <p role="alert">{errorMessage(query.error, 'Could not load the pay premiums.')}</p>}
      {data && <p aria-label="Current pay premiums">{data.current ? premiumRuleText(data.current) : 'No premiums: attendance is paid at the hourly rate.'}</p>}
      {data && data.lines.length > 0 && (
        <ul aria-label="Pay premium history" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {data.lines.map((line) => (
            <li key={line.ruleId}>
              {premiumRuleText(line)}
              {line.state === 'upcoming' && <>{' '}<button type="button" aria-label={`Remove premiums from ${line.effectiveFrom}`}
                disabled={remove.isPending} onClick={() => remove.mutate(line.ruleId)}>Remove</button></>}
            </li>
          ))}
        </ul>
      )}
      {data && <PremiumRuleForm label="Set pay premiums" today={data.today} pending={set.isPending}
        onSave={(payload, saved) => set.mutate(payload, { onSuccess: saved })} />}
      {set.isError && <p role="alert">{errorMessage(set.error, 'Failed to save the premiums.')}</p>}
      {remove.isError && <p role="alert">{errorMessage(remove.error, 'Failed to remove the premiums.')}</p>}
      {data && <p className="inspector-hint">Leave a premium empty to not pay it; save with all empty to stop them. Leave Applies from empty for
        today; earlier days keep the premiums they had. Premiums add up where they meet; weekly overtime counts the hours past the daily
        overtime. A worker can have premiums of their own on their Labor rate card.</p>}
    </section>
  )
}
