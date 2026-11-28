# FlowMat Frontend

React 19 + TypeScript + Vite client for FlowMat. Product vision, API contracts, and module rules live in
[`CLAUDE.md`](./CLAUDE.md); the editor engine's current state is in [`../docs/editor/`](../docs/editor/).

## Requirements

- Node.js `^20.19.0` or `>=22.12.0` (Vite 8)
- The backend on `http://localhost:8080` — see the root [`README.md`](../README.md) for Postgres/Redis and
  `./gradlew bootRun --args='--spring.profiles.active=dev'`

## Run

```bash
npm ci
npm run dev        # http://localhost:5173, proxies /api to http://localhost:8080
```

The dev profile seeds a demo account: **demo-owner / demo1234** (project `prj_demo_main`).
The login screen names it on the dev server only; a build for a demo server whose backend runs the dev profile shows it with `VITE_SHOW_DEMO_LOGIN=true npm run build`. Production builds leave it out, because the prod profile has no such account.

## Checks (same as CI)

```bash
npm run lint
npx tsc --noEmit -p tsconfig.json
npm test           # vitest, node environment, src/**/*.test.{ts,tsx}
npm run build
```

## Layout

Feature-Sliced layers, imported only downward (`pages → widgets → features → entities → shared`):

| Folder | Holds |
|---|---|
| `src/app` | router, guards, providers |
| `src/pages` | one folder per route; `ui/` components, `model/` pure logic with tests |
| `src/entities/<domain>/api` | one React Query hook per endpoint (`useXxxQuery`, `useXxxMutation`) |
| `src/lib/flowmat-editor` | framework-free drawing engine (no React/Zustand imports — enforced by a test) |
| `src/shared` | `httpClient`, DTO types in `types/api.ts`, small helpers in `lib/` |

## Screens

| Route | What it does |
|---|---|
| `/projects/:id/workflows/:workflowId` | workflow canvas and editor |
| `/projects/:id/inventory` | Items, Stock (quantities, thresholds, movement history), Units (global unit master) |
| `/projects/:id/runs`, `/runs/:runId` | start production runs, record material movements, finish runs |
| `/projects/:id/rules` | flow rules that block run/inventory operations when their condition matches |
| `/projects/:id/templates` | apply process templates; create/edit them with the `template:manage` permission |
| `/invite/accept?token=…` | invite preview and acceptance |
| `/admin` | user roles (requires `user:manage`) |

## Conventions

- Hooks own the request payload, cache invalidation, and query keys; components never call `httpClient`.
- Failed requests throw a `UiError` object (not an `Error`) — show it with `errorMessage(error, fallback)`
  from `shared/lib/errorMessage.ts`.
- Mutations submitted from forms catch the rejection and let the mutation's `error` state render the message.
- Permission flags for hiding admin UI come from `useMyPermissionsQuery()`; the backend enforces them regardless.
