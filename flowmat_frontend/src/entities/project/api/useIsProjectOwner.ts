import { useCurrentUserQuery } from '../../auth/api/useCurrentUserQuery'
import { useProjectMembersQuery } from './useProjectMembersQuery'

/** Whether the signed-in user is the project's active owner; false until both are known. */
export function useIsProjectOwner(projectId: string): boolean {
  const me = useCurrentUserQuery().data?.userId
  const members = useProjectMembersQuery(projectId)
  return Boolean(me) && Array.isArray(members.data) && members.data.some((member) => member.userId === me
    && member.projectRole === 'owner' && member.memberStatus === 'active')
}
