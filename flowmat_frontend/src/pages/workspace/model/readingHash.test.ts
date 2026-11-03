import { describe, expect, it } from 'vitest'
import { formatReadingHash, parseReadingHash } from './readingHash'

const known = new Set(['mix', 'bake', 'p 1/2'])

describe('reading hash', () => {
  it('writes and reads back focus, reach and route', () => {
    for (const state of [
      { kind: 'focus', processId: 'mix', reach: null },
      { kind: 'focus', processId: 'bake', reach: 'downstream' },
      { kind: 'focus', processId: 'p 1/2', reach: 'upstream' },
      { kind: 'route', from: 'mix', to: 'p 1/2' },
    ] as const) {
      expect(parseReadingHash(formatReadingHash(state), known)).toEqual(state)
    }
    expect(formatReadingHash({ kind: 'route', from: 'mix', to: 'bake' })).toBe('#route=mix~bake')
    expect(formatReadingHash(null)).toBe('')
  })

  it('reads anything it does not fully understand as no reading', () => {
    for (const hash of ['', '#', '#other=1', '#focus=', '#focus=gone', '#focus=mix&reach=sideways', '#route=mix', '#route=mix~mix',
      '#route=mix~gone', '#route=a~b~c']) {
      expect(parseReadingHash(hash, known)).toBeNull()
    }
  })
})
