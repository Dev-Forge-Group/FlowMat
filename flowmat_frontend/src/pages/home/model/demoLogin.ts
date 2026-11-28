/**
 * The login screen names the demo account only where it can exist: the development server, or a build made for a demo
 * server with VITE_SHOW_DEMO_LOGIN=true. The account is seeded only by the backend's dev profile; a production build
 * that advertised it would offer a login that is always refused.
 */
export function showsDemoLogin(env: { DEV?: boolean; VITE_SHOW_DEMO_LOGIN?: string }): boolean {
  return env.DEV === true || env.VITE_SHOW_DEMO_LOGIN === 'true'
}
