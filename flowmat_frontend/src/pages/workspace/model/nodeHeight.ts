/**
 * How tall a step's box must be to hold its ports, from the canvas node's CSS (index.css, .canvas-node): a 40 px header,
 * each port section 6 px of padding and 24 px a row, and the border. A stored height below that left the ports and their
 * handles hanging under the box, over the next step after a layout. The stored height still applies when it is taller.
 */
const HEADER = 40
const SECTION_PADDING = 6
const ROW = 24
const BORDER = 3

export function portStackHeight(inputs: number, outputs: number): number {
  const section = (rows: number) => (rows > 0 ? SECTION_PADDING + ROW * rows : 0)
  return HEADER + section(inputs) + section(outputs) + BORDER
}

/** The height a step is drawn and laid out with. */
export function nodeDisplayHeight(node: { size: { height: number }; inputs?: readonly unknown[]; outputs?: readonly unknown[] }): number {
  return Math.max(node.size.height, portStackHeight(node.inputs?.length ?? 0, node.outputs?.length ?? 0))
}
