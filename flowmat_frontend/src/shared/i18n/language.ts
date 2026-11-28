/**
 * Which language the screens speak. English is the source text and the default; Korean is shown when the person chose it
 * or the browser asks for it first. Tests run in an English browser, so their selectors keep working.
 */
export type Language = 'en' | 'ko'

export const LANGUAGE_STORAGE_KEY = 'flowmat.language'

/** A stored choice wins; otherwise the first browser language FlowMat speaks; otherwise English. */
export function detectLanguage(stored: string | null | undefined, browser: readonly string[]): Language {
  if (stored === 'en' || stored === 'ko') return stored
  for (const tag of browser) {
    const primary = tag.toLowerCase().split('-')[0]
    if (primary === 'ko' || primary === 'en') return primary
  }
  return 'en'
}

function storedChoice(): string | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem(LANGUAGE_STORAGE_KEY)
  } catch {
    return null
  }
}

function browserLanguages(): readonly string[] {
  if (typeof navigator === 'undefined') return []
  return navigator.languages?.length ? navigator.languages : [navigator.language]
}

let current: Language = detectLanguage(storedChoice(), browserLanguages())

export function currentLanguage(): Language {
  return current
}

/** Keeps the choice and reloads, so every screen reads it from the start. */
export function chooseLanguage(language: Language) {
  try {
    localStorage.setItem(LANGUAGE_STORAGE_KEY, language)
  } catch {
    // Storage refused (private window): the choice lasts for this page only.
  }
  current = language
  if (typeof location !== 'undefined') location.reload()
}

/** For tests only. */
export function setLanguageForTest(language: Language) {
  current = language
}
