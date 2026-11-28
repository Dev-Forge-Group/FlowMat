import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { detectLanguage, setLanguageForTest } from './language'
import { ko } from './ko'
import { issueTitle, severityWord, t } from './t'

afterEach(() => setLanguageForTest('en'))

describe('detectLanguage', () => {
  it('takes a stored choice first, then the first browser language FlowMat speaks', () => {
    expect(detectLanguage('ko', ['en-US'])).toBe('ko')
    expect(detectLanguage(null, ['ko-KR', 'en-US'])).toBe('ko')
    expect(detectLanguage(null, ['fr-FR', 'ko'])).toBe('ko')
  })

  it('falls back to English: an English browser, an unknown choice or language, or none', () => {
    expect(detectLanguage(null, ['en-US', 'ko-KR'])).toBe('en')
    expect(detectLanguage('de', ['fr-FR'])).toBe('en')
    expect(detectLanguage(undefined, [])).toBe('en')
  })
})

describe('t', () => {
  it('keeps the English text in English and fills placeholders', () => {
    expect(t('Check workflow')).toBe('Check workflow')
    expect(t('{errors} errors, {warnings} warnings', { errors: 1, warnings: 0 })).toBe('1 errors, 0 warnings')
    expect(issueTitle('REQUIRED_INPUT_UNCONNECTED')).toBeNull()
    expect(severityWord('error')).toBe('error')
  })

  it('speaks Korean when chosen, and shows the English for text it has no Korean for', () => {
    setLanguageForTest('ko')
    expect(t('Check workflow')).toBe('검사')
    expect(t('{errors} errors, {warnings} warnings', { errors: 1, warnings: 0 })).toBe('오류 1개, 경고 0개')
    expect(t('No such text')).toBe('No such text')
    expect(issueTitle('REQUIRED_INPUT_UNCONNECTED')).toBe('필수 입력에 연결이 없음')
    expect(issueTitle('NO_SUCH_CODE')).toBeNull()
    expect(severityWord('warning')).toBe('경고')
  })
})

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'flowmat-editor' ? [] : sourceFiles(path)
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

describe('Korean entries', () => {
  it('cover every text the code passes to t()', () => {
    const missing = new Set<string>()
    for (const file of sourceFiles(join(__dirname, '..', '..'))) {
      for (const match of readFileSync(file, 'utf8').matchAll(/\bt\(\s*'((?:[^'\\]|\\.)*)'/g)) {
        const text = match[1].replace(/\\'/g, "'")
        if (!(text in ko)) missing.add(`${file}: ${text}`)
      }
    }
    expect([...missing]).toEqual([])
  })
})
