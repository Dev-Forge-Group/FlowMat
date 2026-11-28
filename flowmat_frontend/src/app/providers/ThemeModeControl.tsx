import { useTheme, type ThemeMode } from './ThemeProvider'
import { chooseLanguage, currentLanguage, type Language } from '../../shared/i18n/language'

const OPTIONS: Array<{ mode: ThemeMode; label: string }> = [
  { mode: 'light', label: 'Light' },
  { mode: 'dark', label: 'Dark' },
  { mode: 'system', label: 'System' },
]

/** Each language is named in itself, so it can be found whichever one is showing. */
const LANGUAGES: Array<{ language: Language; label: string }> = [
  { language: 'en', label: 'EN' },
  { language: 'ko', label: '한국어' },
]

/** The theme and the language, in one pill at the corner of every screen. */
export function ThemeModeControl() {
  const { mode, setMode } = useTheme()
  const language = currentLanguage()

  return (
    <div className="theme-switcher">
      <span role="group" aria-label="Theme mode" className="theme-switcher__group">
        {OPTIONS.map((option) => (
          <button
            key={option.mode}
            type="button"
            className={`theme-switcher__button ${mode === option.mode ? 'theme-switcher__button--active' : ''}`}
            onClick={() => setMode(option.mode)}
          >
            {option.label}
          </button>
        ))}
      </span>
      <span role="group" aria-label="Language" className="theme-switcher__group theme-switcher__group--language">
        {LANGUAGES.map((option) => (
          <button
            key={option.language}
            type="button"
            lang={option.language}
            aria-pressed={language === option.language}
            className={`theme-switcher__button ${language === option.language ? 'theme-switcher__button--active' : ''}`}
            onClick={() => { if (language !== option.language) chooseLanguage(option.language) }}
          >
            {option.label}
          </button>
        ))}
      </span>
    </div>
  )
}
