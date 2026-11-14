import { describe, expect, it } from 'vitest'
import {
  EMPTY_ORDER_FORM,
  approvalStepsPayload,
  creditPayload,
  orderPayload,
  typedPrice,
  typedQuantity,
} from './purchaseOrderModel'

describe('purchase inputs respect the backend decimal bounds', () => {
  it.each([
    { name: 'eleven whole digits', value: '10000000000' },
    { name: 'eleven whole digits with four decimals', value: '10000000000.0000' },
    { name: 'an oversized value with leading zeros', value: '0000010000000000.0001' },
    { name: 'a value which would overflow Number', value: '9'.repeat(400) },
  ])('rejects $name before JSON serialization', ({ value }) => {
    expect(typedQuantity(value)).toBeNull()
    expect(typedPrice(value)).toBeUndefined()
  })

  it.each([
    { name: 'largest allowed value', value: '9999999999.9999', canonical: '9999999999.9999' },
    { name: 'largest value with leading zeros', value: '0000009999999999.9999', canonical: '9999999999.9999' },
    { name: 'smallest positive quantity', value: '0.0001', canonical: '0.0001' },
    { name: 'ordinary padded value', value: ' 0000012.3400 ', canonical: '12.34' },
  ])('keeps $name usable without changing its decimal JSON value', ({ value, canonical }) => {
    expect(JSON.stringify(typedQuantity(value))).toBe(canonical)
    expect(JSON.stringify(typedPrice(value))).toBe(canonical)
  })

  it('keeps a zero price valid while rejecting a zero quantity', () => {
    expect(typedPrice('000000000000000.0000')).toBe(0)
    expect(typedQuantity('000000000000000.0000')).toBeNull()
  })

  it('preserves an intentional empty approval limit as null', () => {
    expect(typedPrice('  ')).toBeNull()
    expect(JSON.stringify({ approvalLimit: typedPrice('') })).toBe('{"approvalLimit":null}')
    expect(typedPrice(undefined)).toBeNull()
  })

  it('does not turn an overflowing order price into an accepted unpriced line', () => {
    const result = orderPayload('project', {
      ...EMPTY_ORDER_FORM,
      supplierName: 'Supplier',
      lines: [{ itemId: 'item', quantity: '1', unitPrice: '9'.repeat(400) }],
    })
    expect(result.input).toBeNull()
    expect(result.error).not.toBeNull()
  })

  it('does not send an out-of-range order quantity to the server', () => {
    const result = orderPayload('project', {
      ...EMPTY_ORDER_FORM,
      supplierName: 'Supplier',
      lines: [{ itemId: 'item', quantity: '10000000000', unitPrice: '0' }],
    })
    expect(result.input).toBeNull()
    expect(result.error).not.toBeNull()
  })

  it('does not serialize an overflowing approval-step amount as null', () => {
    const result = approvalStepsPayload([{ fromValue: '9'.repeat(400), approverId: 'editor' }])
    expect(result.steps).toBeNull()
    expect(result.error).not.toBeNull()
  })

  it('retains the separate twenty-digit exact-string contract for supplier credits', () => {
    const amount = '99999999999999999999.9999'
    expect(creditPayload({ returnId: 'return', amount, creditNote: '', reason: '', change: false }).input)
      .toEqual({ amount })
  })
})
