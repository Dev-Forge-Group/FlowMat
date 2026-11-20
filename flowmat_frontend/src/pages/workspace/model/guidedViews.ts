/**
 * archify's guided views (adapted from archify viewer/guided-views.js, Apache-2.0): a workflow's chapters, each showing
 * some of its processes, read one at a time or played. Playing walks each chapter's processes in order, one beat each,
 * then goes on to the next chapter, and stops after the last. The chapters are stored with the workflow (ADR-037).
 */

/** A chapter as the reading uses it; `focus` is the processes in reading order. */
export interface GuidedChapter {
  id: string
  label: string
  note: string | null
  focus: readonly string[]
  version: number
}

/** How long a playing chapter stays on one process. archify gives a chapter 3.2 s; FlowMat's are longer, so per process. */
export const BEAT_MS = 1200

/** A chapter's processes that are still on the canvas, in reading order. */
export function liveFocus(chapter: GuidedChapter, known: (processId: string) => boolean): string[] {
  return chapter.focus.filter(known)
}

/**
 * Where playing goes from here: the chapter's next process, else the first process of the next chapter that still shows
 * one, else null at the end. With no beat yet, the chapter's first process.
 */
export function nextBeat(
  chapters: readonly GuidedChapter[],
  at: { viewId: string; beat: string | null },
  known: (processId: string) => boolean,
): { viewId: string; beat: string } | null {
  const index = chapters.findIndex((chapter) => chapter.id === at.viewId)
  if (index < 0) return null
  const focus = liveFocus(chapters[index], known)
  const position = at.beat == null ? -1 : focus.indexOf(at.beat)
  if (position + 1 < focus.length) return { viewId: at.viewId, beat: focus[position + 1] }
  for (const chapter of chapters.slice(index + 1)) {
    const first = liveFocus(chapter, known)[0]
    if (first) return { viewId: chapter.id, beat: first }
  }
  return null
}

/** The chapter before or after this one, by its place in the list; null past either end. */
export function neighbourChapter(chapters: readonly GuidedChapter[], viewId: string, step: 1 | -1): GuidedChapter | null {
  const index = chapters.findIndex((chapter) => chapter.id === viewId)
  return index < 0 ? null : chapters[index + step] ?? null
}
