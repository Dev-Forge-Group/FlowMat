import { currentLanguage } from './language'
import { ko } from './ko'

/**
 * The text in the current language. The English text is the key: a missing translation shows the English, never a key.
 * Placeholders are written {name} and filled from values.
 */
export function t(text: string, values?: Record<string, string | number>): string {
  const template = currentLanguage() === 'ko' ? ko[text] ?? text : text
  if (!values) return template
  return template.replace(/\{(\w+)\}/g, (match, name: string) => (name in values ? String(values[name]) : match))
}

/** A short Korean name for a check's code, shown before the server's English message; null in English. */
export function issueTitle(code: string): string | null {
  return currentLanguage() === 'ko' ? ko[`issue:${code}`] ?? null : null
}

/** "error" and "warning" as words in the current language. */
export function severityWord(severity: string): string {
  return t(severity === 'error' ? 'error' : severity === 'warning' ? 'warning' : severity)
}
