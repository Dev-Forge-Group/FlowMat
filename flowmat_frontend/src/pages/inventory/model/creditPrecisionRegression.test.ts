import { describe, expect, it } from 'vitest'
import { creditPayload, type CreditForm } from './purchaseOrderModel'

describe.each([
  { action: 'creating', change: false, reason: '' },
  { action: 'changing', change: true, reason: 'Correct the supplier credit amount' },
])('credit amount precision when $action a credit', ({ change, reason }) => {
  const form = (amount: string): CreditForm => ({
    returnId: 'return-precision',
    amount,
    creditNote: 'CN-PRECISION',
    reason,
    change,
  })

  it.each([
    '9007199254740993',
    '99999999999999999999.9999',
  ])('preserves the valid decimal amount %s in the outgoing input', (amount) => {
    const result = creditPayload(form(amount))

    expect(result.error).toBeNull()
    expect(result.input).not.toBeNull()
    expect(String(result.input?.amount)).toBe(amount)
  })

  it('keeps 1.2500 numerically equal to 1.25', () => {
    const result = creditPayload(form('1.2500'))

    expect(result.error).toBeNull()
    expect(result.input).not.toBeNull()
    expect(Number(result.input?.amount)).toBe(1.25)
  })
})
