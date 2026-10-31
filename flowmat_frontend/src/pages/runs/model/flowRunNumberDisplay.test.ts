import { afterEach, describe, expect, it, vi } from 'vitest'
import { flowRunNumberDisplay } from './flowRunJson'

afterEach(() => vi.restoreAllMocks())

describe('exact numeric fields in Flow Run JSON', () => {
  it('retains an integer beyond the JavaScript safe range', () => {
    expect(flowRunNumberDisplay('{"quantity":9007199254740993}', 'quantity', 9007199254740992))
      .toEqual({ text: '9007199254740993', legacy: false })
  })

  it('retains all decimal digits and trailing zeroes', () => {
    expect(flowRunNumberDisplay('{"quantity":9007199254740993.123456789000}', 'quantity', 9007199254740994))
      .toEqual({ text: '9007199254740993.123456789000', legacy: false })
  })

  it('retains scientific notation even when the parsed number overflows or underflows', () => {
    const json = '{"scientific":9.007199254740993123e+15,"tiny":1e-1000,"huge":1E+1000}'
    expect(flowRunNumberDisplay(json, 'scientific', 9007199254740994))
      .toEqual({ text: '9.007199254740993123e+15', legacy: false })
    expect(flowRunNumberDisplay(json, 'tiny', 0)).toEqual({ text: '1e-1000', legacy: false })
    expect(flowRunNumberDisplay(json, 'huge', Infinity)).toEqual({ text: '1E+1000', legacy: false })
  })

  it('retains signed zero rather than stringifying its parsed value', () => {
    expect(flowRunNumberDisplay('{"quantity":-0.0000}', 'quantity', -0))
      .toEqual({ text: '-0.0000', legacy: false })
  })

  it('matches a decoded escaped key and ignores a nested field with the same name', () => {
    const json = '{"quanti\\u0074y":9007199254740993,"nested":{"quantity":9007199254740995}}'
    expect(flowRunNumberDisplay(json, 'quantity', 7)).toEqual({ text: '9007199254740993', legacy: false })
  })

  it('does not promote a nested or inherited-looking value into a missing top-level field', () => {
    for (const json of [
      '{"nested":{"quantity":7}}',
      '{"__proto__":{"quantity":7},"constructor":{"quantity":8}}',
    ]) {
      expect(flowRunNumberDisplay(json, 'quantity', 12)).toEqual({ text: null, legacy: false })
    }
  })

  it('does not accept source metadata in the JSON document as parser context', () => {
    const json = '{"quantity":{"source":"9007199254740993"},"source":"1",'
      + '"context":{"source":"2"},"__proto__":{"source":"3"}}'
    expect(flowRunNumberDisplay(json, 'quantity', 12)).toEqual({ text: null, legacy: false })
  })

  it('reads a numeric field despite unrelated source and prototype-shaped metadata', () => {
    const json = '{"quantity":9007199254740993,"source":"7","context":{"source":"8"},'
      + '"__proto__":{"source":"9","quantity":10}}'
    expect(flowRunNumberDisplay(json, 'quantity', 12)).toEqual({ text: '9007199254740993', legacy: false })
  })

  it('rejects strings, booleans, null and containers that only look like numeric fields', () => {
    for (const value of ['"9007199254740993"', 'true', 'false', 'null', '[7]', '{"source":"7"}']) {
      expect(flowRunNumberDisplay(`{"quantity":${value}}`, 'quantity', 12))
        .toEqual({ text: null, legacy: false })
    }
  })

  it('requires an object with the requested own top-level numeric field', () => {
    for (const json of ['null', '7', '"quantity"', '[7]', '{}', '{"other":7}']) {
      expect(flowRunNumberDisplay(json, 'quantity', 12)).toEqual({ text: null, legacy: false })
    }
  })

  it('uses native JSON duplicate-key semantics without reviving an earlier numeric value', () => {
    expect(flowRunNumberDisplay('{"quantity":1,"quantity":9007199254740993}', 'quantity', 12))
      .toEqual({ text: '9007199254740993', legacy: false })
    expect(flowRunNumberDisplay('{"quantity":9007199254740993,"quantity":"later"}', 'quantity', 12))
      .toEqual({ text: null, legacy: false })
  })

  it('treats a null exact payload as authoritative absence rather than using stale legacy data', () => {
    expect(flowRunNumberDisplay(null, 'quantity', 12)).toEqual({ text: null, legacy: false })
  })

  it('never falls back to legacy data when the exact JSON is malformed', () => {
    for (const json of ['', ' ', '{', '{"quantity":1,}', '{"quantity":NaN}', '{"quantity":1} {}']) {
      expect(flowRunNumberDisplay(json, 'quantity', 12)).toEqual({ text: null, legacy: false })
    }
  })

  it('marks finite numeric fallback as legacy only when the exact field was omitted', () => {
    expect(flowRunNumberDisplay(undefined, 'quantity', 12.5)).toEqual({ text: '12.5', legacy: true })
    expect(flowRunNumberDisplay(undefined, 'quantity', -0)).toEqual({ text: '0', legacy: true })
  })

  it('does not invent legacy numeric values from non-finite numbers or non-numbers', () => {
    for (const value of [null, undefined, NaN, Infinity, -Infinity, '12', true, {}, []]) {
      expect(flowRunNumberDisplay(undefined, 'quantity', value)).toEqual({ text: null, legacy: true })
    }
  })

  it('withholds exact evidence when the native parser does not provide primitive source context', () => {
    const nativeParse = JSON.parse
    vi.spyOn(JSON, 'parse').mockImplementation((text, reviver) => nativeParse(text, reviver
      ? function (this: unknown, key: string, value: unknown) { return reviver.call(this, key, value) }
      : undefined))

    expect(flowRunNumberDisplay('{"quantity":9007199254740993}', 'quantity', 9007199254740992))
      .toEqual({ text: null, legacy: false })
  })
})
