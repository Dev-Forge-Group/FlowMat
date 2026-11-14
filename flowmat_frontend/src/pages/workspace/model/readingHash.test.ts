import { describe, expect, it } from 'vitest'
import {
  formatReadingHash, parseChangesParam, parseReadingHash, parseRunParam, withRunParam, withViewParams,
} from './readingHash'

const known = new Set(['mix', 'bake', 'p 1/2'])
const connections = new Set(['c1', 'c 2/3'])

describe('reading hash', () => {
  it('writes and reads back a pinned connection, and only one the canvas has', () => {
    for (const connectionId of ['c1', 'c 2/3']) {
      const state = { kind: 'relation', connectionId } as const
      expect(parseReadingHash(formatReadingHash(state), known, connections)).toEqual(state)
    }
    expect(formatReadingHash({ kind: 'relation', connectionId: 'c 2/3' })).toBe('#relation=c%202%2F3')
    expect(parseReadingHash('#relation=c1&run=r1', known, connections)).toEqual({ kind: 'relation', connectionId: 'c1' })
    expect(parseReadingHash('#relation=gone', known, connections)).toBeNull()
    expect(parseReadingHash('#relation=c1', known)).toBeNull()
  })

  it('writes and reads back focus, reach and route', () => {
    for (const state of [
      { kind: 'focus', processId: 'mix', reach: null },
      { kind: 'focus', processId: 'bake', reach: 'downstream' },
      { kind: 'focus', processId: 'p 1/2', reach: 'upstream' },
      { kind: 'route', from: 'mix', to: 'p 1/2' },
      { kind: 'lens', kinds: ['storage'] },
      { kind: 'lens', kinds: ['equipment', 'storage'] },
    ] as const) {
      expect(parseReadingHash(formatReadingHash(state), known)).toEqual(state)
    }
    expect(formatReadingHash({ kind: 'route', from: 'mix', to: 'bake' })).toBe('#route=mix~bake')
    expect(formatReadingHash({ kind: 'lens', kinds: ['equipment', 'storage'] })).toBe('#lens=equipment~storage')
    expect(formatReadingHash(null)).toBe('')
  })

  it('keeps the run shown over the canvas beside any reading', () => {
    expect(withRunParam('#focus=mix', 'r 1')).toBe('#focus=mix&run=r%201')
    expect(withRunParam('', 'r1')).toBe('#run=r1')
    expect(withRunParam('#lens=storage', null)).toBe('#lens=storage')
    expect(parseRunParam('#focus=mix&run=r%201')).toBe('r 1')
    expect(parseRunParam('#run=')).toBeNull()
    expect(parseReadingHash('#route=mix~bake&run=r1', known)).toEqual({ kind: 'route', from: 'mix', to: 'bake' })
    expect(parseReadingHash('#run=r1', known)).toBeNull()
  })

  it('keeps the compared revision beside the reading and the run', () => {
    expect(withViewParams('#focus=mix', 'r1', 'rev 3')).toBe('#focus=mix&run=r1&changes=rev%203')
    expect(withViewParams('', null, 'rev3')).toBe('#changes=rev3')
    expect(withViewParams('#lens=storage', null, null)).toBe('#lens=storage')
    expect(parseChangesParam('#focus=mix&run=r1&changes=rev%203')).toBe('rev 3')
    expect(parseRunParam('#focus=mix&run=r1&changes=rev3')).toBe('r1')
    expect(parseChangesParam('#changes=')).toBeNull()
    expect(parseReadingHash('#focus=mix&changes=rev3', known)).toEqual({ kind: 'focus', processId: 'mix', reach: null })
  })

  it('reads anything it does not fully understand as no reading', () => {
    for (const hash of ['', '#', '#other=1', '#focus=', '#focus=gone', '#focus=mix&reach=sideways', '#route=mix', '#route=mix~mix',
      '#route=mix~gone', '#route=a~b~c', '#lens=', '#lens=robot', '#lens=storage~storage', '#lens=input~output~storage',
      '#lens=decision']) {
      expect(parseReadingHash(hash, known)).toBeNull()
    }
  })
})
