import { describe, expect, it } from 'vitest'
import { inReadingOrder, liveFocus, neighbourChapter, nextBeat, type GuidedChapter } from './guidedViews'

const chapter = (id: string, focus: string[]): GuidedChapter => ({ id, label: id, note: null, focus, version: 1 })
const chapters = [chapter('a', ['mix', 'bake']), chapter('gone', ['deleted']), chapter('b', ['pack'])]
const known = (id: string) => id !== 'deleted'

describe('guided views', () => {
  it('walks a chapter process by process, skips a chapter with nothing left, and stops after the last', () => {
    expect(nextBeat(chapters, { viewId: 'a', beat: null }, known)).toEqual({ viewId: 'a', beat: 'mix' })
    expect(nextBeat(chapters, { viewId: 'a', beat: 'mix' }, known)).toEqual({ viewId: 'a', beat: 'bake' })
    expect(nextBeat(chapters, { viewId: 'a', beat: 'bake' }, known)).toEqual({ viewId: 'b', beat: 'pack' })
    expect(nextBeat(chapters, { viewId: 'b', beat: 'pack' }, known)).toBeNull()
    expect(nextBeat(chapters, { viewId: 'missing', beat: null }, known)).toBeNull()
  })

  it('leaves out processes that are no longer on the canvas', () => {
    expect(liveFocus(chapter('x', ['mix', 'deleted', 'pack']), known)).toEqual(['mix', 'pack'])
  })

  it('finds the chapter before and after', () => {
    expect(neighbourChapter(chapters, 'gone', 1)?.id).toBe('b')
    expect(neighbourChapter(chapters, 'gone', -1)?.id).toBe('a')
    expect(neighbourChapter(chapters, 'a', -1)).toBeNull()
    expect(neighbourChapter(chapters, 'b', 1)).toBeNull()
  })
})

describe('a new chapter\'s processes', () => {
  const node = (processId: string, x: number, y: number) => ({ processId, position: { x, y } })

  it('are in reading order, top to bottom then left to right, without those not on the canvas', () => {
    const nodes = [node('pack', 400, 200), node('mix', 0, 0), node('bake', 200, 0), node('cool', 0, 200)]
    expect(inReadingOrder(nodes, ['pack', 'gone', 'cool', 'bake', 'mix'])).toEqual(['mix', 'bake', 'cool', 'pack'])
  })
})
