import { describe, expect, it } from 'vitest'
import { connectionMarkClass, processMarkClass, type ChangeMarks } from './canvasMarks'
import type { CanvasReadingHighlight } from './canvasReadingModel'
import type { RunMark } from './runOverlay'

const reading: CanvasReadingHighlight = {
  nodeIds: new Set(['mix', 'bake']),
  edgeIds: new Set(['c1']),
  tone: 'downstream',
  origin: 'mix',
  nearIds: new Set(['cool']),
}
const runMarks = new Map<string, RunMark>([['bake', 'failed']])
const changeMarks: ChangeMarks = {
  processes: new Map([['pack', 'added']]),
  connections: new Map([['c2', 'changed']]),
}

describe('processMarkClass', () => {
  it('is empty with nothing read, run or compared', () => {
    expect(processMarkClass('mix', null, null, null)).toBe('')
  })

  it('marks what is read with its tone and its origin, what is near, and lets the rest recede', () => {
    expect(processMarkClass('mix', reading, null, null)).toBe('reading-on reading-downstream reading-origin')
    expect(processMarkClass('bake', reading, null, null)).toBe('reading-on reading-downstream')
    expect(processMarkClass('cool', reading, null, null)).toBe('reading-near')
    expect(processMarkClass('pack', reading, null, null)).toBe('reading-dim')
  })

  it('adds a shown run\'s mark and a compared revision\'s change beside the reading', () => {
    expect(processMarkClass('bake', reading, runMarks, changeMarks)).toBe('reading-on reading-downstream run-mark run-failed')
    expect(processMarkClass('pack', null, runMarks, changeMarks)).toBe('change-mark change-added')
  })
})

describe('connectionMarkClass', () => {
  it('marks a connection read or receding, and what changed on it', () => {
    expect(connectionMarkClass('c1', reading, null)).toBe('reading-on reading-downstream')
    expect(connectionMarkClass('c2', reading, changeMarks)).toBe('reading-dim change-changed')
    expect(connectionMarkClass('c2', null, changeMarks)).toBe('change-changed')
    expect(connectionMarkClass('c3', null, null)).toBe('')
  })
})
