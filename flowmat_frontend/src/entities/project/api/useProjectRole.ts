import { useCurrentUserQuery } from '../../auth/api/useCurrentUserQuery'
import { useProjectMembersQuery } from './useProjectMembersQuery'

/** The signed-in user's active role in the project (owner, editor or viewer); null until known or without one. */
export function useProjectRole(projectId: string): string | null {
  const me = useCurrentUserQuery().data?.userId
  const members = useProjectMembersQuery(projectId)
  if (!me || !Array.isArray(members.data)) return null
  return members.data.find((member) => member.userId === me && member.memberStatus === 'active')?.projectRole ?? null
}
