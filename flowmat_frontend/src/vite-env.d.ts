/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_WORKFLOW_SYNC_HEARTBEAT_MS?: string
  readonly VITE_WORKFLOW_SYNC_RECONNECT_DELAY_MS?: string
  readonly VITE_SHOW_DEMO_LOGIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.css' {
  const content: string
  export default content
}
