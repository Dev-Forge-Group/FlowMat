import { describe, expect, it } from 'vitest'
import { showsDemoLogin } from './demoLogin'

describe('showsDemoLogin', () => {
  it('names the demo account on the development server and on a build made for a demo server', () => {
    expect(showsDemoLogin({ DEV: true })).toBe(true)
    expect(showsDemoLogin({ DEV: false, VITE_SHOW_DEMO_LOGIN: 'true' })).toBe(true)
  })

  it('does not name it in a production build, where the account does not exist', () => {
    expect(showsDemoLogin({ DEV: false })).toBe(false)
    expect(showsDemoLogin({ DEV: false, VITE_SHOW_DEMO_LOGIN: 'yes' })).toBe(false)
  })
})
