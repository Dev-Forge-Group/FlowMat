import { useCallback, useEffect, useState } from 'react'

/**
 * archify's motion control (adapted from archify's Motion Governor in template.source.html, Apache-2.0): the reader can
 * keep the canvas Live, where the camera glides and highlights fade, or Still, where everything moves at once. The system's
 * reduced-motion preference always wins. The choice is this browser's and is never shared.
 */
export type MotionChoice = 'live' | 'still'

const MOTION_KEY = 'flowmat.motion'

function readMotion(): MotionChoice {
  try {
    return localStorage.getItem(MOTION_KEY) === 'still' ? 'still' : 'live'
  } catch {
    return 'live'
  }
}

function saveMotion(choice: MotionChoice) {
  try {
    localStorage.setItem(MOTION_KEY, choice)
  } catch {
    // Only a preference: the canvas works the same without it.
  }
}

function reducedMotionQuery() {
  return typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-reduced-motion: reduce)') : null
}

/** Whether the canvas should move without animating: the reader chose Still, or the system asks for reduced motion. */
export function motionStill(): boolean {
  return Boolean(reducedMotionQuery()?.matches) || document.documentElement.getAttribute('data-motion') === 'still'
}

/**
 * The reader's motion choice, shown on the document as `data-motion` (live or still) while the workspace is open, so the
 * canvas styles and `motionStill` follow it.
 */
export function useMotion() {
  const [choice, setChoice] = useState<MotionChoice>(readMotion)
  const [reduced, setReduced] = useState(() => Boolean(reducedMotionQuery()?.matches))

  useEffect(() => {
    const query = reducedMotionQuery()
    if (!query) return
    const update = () => setReduced(query.matches)
    query.addEventListener('change', update)
    return () => query.removeEventListener('change', update)
  }, [])

  const still = reduced || choice === 'still'
  useEffect(() => {
    const root = document.documentElement
    root.setAttribute('data-motion', still ? 'still' : 'live')
    return () => root.removeAttribute('data-motion')
  }, [still])

  const toggle = useCallback(() => {
    setChoice((current) => {
      const next = current === 'still' ? 'live' : 'still'
      saveMotion(next)
      return next
    })
  }, [])

  return { still, reduced, toggle }
}
