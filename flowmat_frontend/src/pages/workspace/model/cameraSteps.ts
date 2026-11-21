/** archify's camera stepper: 25% steps between 25% and 300%. */
export const ZOOM_STEP = 0.25
export const MIN_ZOOM_STEP = 0.25
export const MAX_ZOOM_STEP = 3

/**
 * The next zoom from the stepper: the next quarter up or down from where the camera is, so a zoom between steps lands
 * on the next one, never past the limits.
 */
export function nextZoomStep(zoom: number, direction: 1 | -1): number {
  const steps = 1 / ZOOM_STEP
  return direction > 0
    ? Math.min(MAX_ZOOM_STEP, (Math.floor(zoom * steps + 1e-6) + 1) / steps)
    : Math.max(MIN_ZOOM_STEP, (Math.ceil(zoom * steps - 1e-6) - 1) / steps)
}
