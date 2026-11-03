import { describe, expect, it } from 'vitest'
import { workspaceRouteView } from './workspaceRouteModel'

describe('workspaceRouteView', () => {
  it('keeps the editor once a canvas has loaded, whatever a later refresh does', () => {
    expect(workspaceRouteView({ isLoading: false, hasCanvas: true })).toBe('editor')
  })

  it('waits for the first canvas, and shows the error page only when none loaded', () => {
    expect(workspaceRouteView({ isLoading: true, hasCanvas: false })).toBe('loading')
    expect(workspaceRouteView({ isLoading: false, hasCanvas: false })).toBe('error')
  })
})
