import { describe, expect, it } from 'vitest'
import { nodeDisplayHeight, portStackHeight } from './nodeHeight'

describe('node height', () => {
  it('grows a step to hold its ports (the sizes measured on the canvas)', () => {
    // Three inputs and an output measured 148 px of content in a box of 80.
    expect(portStackHeight(3, 1)).toBeGreaterThanOrEqual(150)
    expect(nodeDisplayHeight({ size: { height: 80 }, inputs: [1, 2, 3], outputs: [1] })).toBe(portStackHeight(3, 1))
  })

  it('keeps the stored height when it is already tall enough', () => {
    expect(nodeDisplayHeight({ size: { height: 80 }, inputs: [1], outputs: [] })).toBe(80)
    expect(nodeDisplayHeight({ size: { height: 240 }, inputs: [1, 2, 3], outputs: [1] })).toBe(240)
  })
})
