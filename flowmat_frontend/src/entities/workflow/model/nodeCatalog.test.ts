import { describe, expect, it } from 'vitest'
import { getDefaultConnectionType, getWorkflowNodeDefinition, getWorkflowPaletteDefinitions } from './nodeCatalog'

describe('node catalog', () => {
  it('offers the Exclusive gateway (ADR-006)', () => {
    expect(getWorkflowPaletteDefinitions().map((definition) => definition.tool)).toContain('exclusive')
    const gateway = getWorkflowNodeDefinition('exclusive')
    expect(gateway.label).toBe('Exclusive')
    expect(gateway.isTerminal).toBe(false)
    expect(getDefaultConnectionType('exclusive')).toBe('control_flow')
  })

  it('offers the Split gateway (ADR-008)', () => {
    expect(getWorkflowPaletteDefinitions().map((definition) => definition.tool)).toContain('split')
    expect(getWorkflowNodeDefinition('split').label).toBe('Split')
    expect(getWorkflowNodeDefinition('split').isTerminal).toBe(false)
  })

  it('offers the Join gateway (ADR-007)', () => {
    expect(getWorkflowPaletteDefinitions().map((definition) => definition.tool)).toContain('join')
    expect(getWorkflowNodeDefinition('join').label).toBe('Join')
    expect(getWorkflowNodeDefinition('join').isTerminal).toBe(false)
  })
})
