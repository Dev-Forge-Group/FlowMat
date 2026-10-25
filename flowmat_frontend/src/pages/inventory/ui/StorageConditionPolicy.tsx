import { useIsProjectOwner } from '../../../entities/project/api/useIsProjectOwner'
import {
  useInventorySettingsQuery,
  useSaveInventorySettingsMutation,
  type StorageConditionPolicy as Policy,
} from '../../../entities/inventory/api/useInventorySettings'
import { errorMessage } from '../../../shared/lib/errorMessage'

/** Whether stock is refused in a place kept otherwise than the item (docs/domain/storage-location.md SL5-SL7); the owner chooses. */
export function StorageConditionPolicy({ projectId }: { projectId: string }) {
  const query = useInventorySettingsQuery(projectId)
  const save = useSaveInventorySettingsMutation(projectId)
  const owner = useIsProjectOwner(projectId)
  const policy = query.data?.storageConditionPolicy ?? 'warn'
  return <div role="group" aria-label="Storage condition policy" style={{ fontSize: 12, margin: '6px 0' }}>
    <label>When a place&apos;s storage condition differs from an item&apos;s{' '}
      <select aria-label="Different storage condition" value={policy} disabled={!owner || save.isPending}
        onChange={(event) => save.mutate({ storageConditionPolicy: event.target.value as Policy, expectedVersion: query.data?.version ?? null })}>
        <option value="warn">warn only</option>
        <option value="refuse">refuse the stock</option>
      </select>
    </label>
    {!owner && <span className="inspector-hint"> (the project owner chooses)</span>}
    {save.isError && <p role="alert">{errorMessage(save.error, 'The setting was not saved.')}</p>}
  </div>
}
