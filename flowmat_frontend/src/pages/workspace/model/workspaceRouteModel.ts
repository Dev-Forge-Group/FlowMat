/** What the workflow route shows. */
export type WorkspaceRouteView = 'loading' | 'editor' | 'error'

/**
 * The editor once a canvas has loaded, even when a later refresh fails: a passing network failure must not throw away the
 * open editor with its selection, inspector and unsaved edits. The error page only when nothing has loaded.
 */
export function workspaceRouteView({ isLoading, hasCanvas }: { isLoading: boolean; hasCanvas: boolean }): WorkspaceRouteView {
  if (hasCanvas) return 'editor'
  return isLoading ? 'loading' : 'error'
}
