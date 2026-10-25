export interface AttributeRow { name: string; value: string }
export function attributeRows(attributes: Record<string, string>): AttributeRow[] {
  return Object.entries(attributes).map(([name, value]) => ({ name, value }))
}
export function attributesInput(rows: AttributeRow[]): { attributes: Record<string, string>; error: string | null } {
  const attributes: Record<string, string> = {}
  const populated = rows.filter((row) => row.name.trim() || row.value.trim())
  if (populated.length > 20) return { attributes, error: 'Use at most 20 attributes.' }
  for (const row of populated) {
    const name = row.name.trim(), value = row.value.trim()
    // eslint-disable-next-line no-control-regex -- Reject control characters in stored text.
    if (!name || name.length > 50 || ['__proto__', 'prototype', 'constructor'].includes(name) || /[\u0000-\u001f\u007f]|[\ud800-\udbff](?![\udc00-\udfff])|(?:^|[^\ud800-\udbff])[\udc00-\udfff]/.test(row.name))
      return { attributes, error: 'Attribute names must be safe, nonblank and at most 50 characters.' }
    // eslint-disable-next-line no-control-regex -- Reject control characters in stored text.
    if (!value || value.length > 100 || /[\u0000-\u001f\u007f]|[\ud800-\udbff](?![\udc00-\udfff])|(?:^|[^\ud800-\udbff])[\udc00-\udfff]/.test(row.value))
      return { attributes, error: 'Attribute values must be nonblank and at most 100 characters.' }
    if (Object.prototype.hasOwnProperty.call(attributes, name)) return { attributes, error: `Attribute ${name} is repeated.` }
    attributes[name] = value
  }
  return { attributes, error: null }
}
