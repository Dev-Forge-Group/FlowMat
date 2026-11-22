import { create } from 'zustand'

/** The nodes the Plan panel lights on the canvas: the path of the requirement chosen ("is-traced"). */
export const usePlanTraceStore = create<{ traced: ReadonlySet<string>; setTraced(ids: Iterable<string>): void }>((set) => ({
  traced: new Set(),
  setTraced: (ids) => set({ traced: new Set(ids) }),
}))
