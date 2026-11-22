import type { PointerEvent as ReactPointerEvent } from 'react'
import { toElementId, type ElementId, type LineEndpoint, type ResizeHandle } from '../../../lib/flowmat-editor'

/** What a press or a key on the drawing layer is about, read from the element it landed on or the key. */

export function getElementIdFromTarget(target: EventTarget | null): ElementId | null {
  if (!(target instanceof Element)) return null
  const element = target.closest('[data-element-id]')
  const id = element?.getAttribute('data-element-id')
  return id ? toElementId(id) : null
}

export function getEditorAction(
  event: ReactPointerEvent<SVGSVGElement>,
): { kind: 'none' } | { kind: 'rotate' } | { kind: 'resize'; handle: ResizeHandle } | { kind: 'line-endpoint'; endpoint: LineEndpoint } {
  const target = event.target
  if (!(target instanceof Element)) return { kind: 'none' }
  const action = target.getAttribute('data-editor-action')
  if (action === 'line-endpoint') {
    const endpoint = target.getAttribute('data-editor-endpoint')
    if (isLineEndpoint(endpoint)) return { kind: 'line-endpoint', endpoint }
  }
  if (action === 'rotate') return { kind: 'rotate' }
  if (action === 'resize') {
    const handle = target.getAttribute('data-editor-handle')
    if (isResizeHandle(handle)) return { kind: 'resize', handle }
  }
  return { kind: 'none' }
}

function isResizeHandle(value: string | null): value is ResizeHandle {
  return value === 'nw' || value === 'n' || value === 'ne' || value === 'e' || value === 'se' || value === 's' || value === 'sw' || value === 'w'
}

function isLineEndpoint(value: string | null): value is LineEndpoint {
  return value === 'start' || value === 'end'
}

export function getKeyboardAction(event: KeyboardEvent): 'none' | 'delete' | 'duplicate' | 'front' | 'back' | 'group' | 'ungroup' {
  if (event.key === 'Delete' || event.key === 'Backspace') return 'delete'
  if (event.ctrlKey || event.metaKey) {
    const key = event.key.toLowerCase()
    if (key === 'g') return event.shiftKey ? 'ungroup' : 'group'
    if (key === 'd') return 'duplicate'
    if (event.key === ']') return 'front'
    if (event.key === '[') return 'back'
  }
  return 'none'
}

export function isTextEditingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return Boolean(target.closest('input, textarea, select, [contenteditable="true"]'))
}
