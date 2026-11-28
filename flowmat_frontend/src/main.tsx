import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { QueryProvider } from './app/providers/QueryProvider'
import { ThemeModeControl } from './app/providers/ThemeModeControl'
import { ThemeProvider } from './app/providers/ThemeProvider'
import { router } from './app/router/index'
import { currentLanguage } from './shared/i18n/language'
import './index.css'
import './tailwind.css'

document.documentElement.lang = currentLanguage()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider>
      <QueryProvider>
        <RouterProvider router={router} />
        <ThemeModeControl />
      </QueryProvider>
    </ThemeProvider>
  </StrictMode>
)
