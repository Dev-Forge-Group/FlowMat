import { describe, expect, it } from 'vitest'
import { nextZoomStep } from './cameraSteps'

describe('nextZoomStep', () => {
  it('steps a quarter from a step', () => {
    expect(nextZoomStep(1, 1)).toBe(1.25)
    expect(nextZoomStep(1, -1)).toBe(0.75)
  })

  it('lands on the next step from between two', () => {
    expect(nextZoomStep(1.1, 1)).toBe(1.25)
    expect(nextZoomStep(1.1, -1)).toBe(1)
    // A zoom a hair off a step, as a fit leaves it, counts as on it.
    expect(nextZoomStep(1.2499999999, 1)).toBe(1.5)
  })

  it('stays within 25% and 300%', () => {
    expect(nextZoomStep(3, 1)).toBe(3)
    expect(nextZoomStep(0.25, -1)).toBe(0.25)
    expect(nextZoomStep(0.1, -1)).toBe(0.25)
  })
})
