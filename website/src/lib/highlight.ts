import { createHighlighter, type ThemeRegistration } from 'shiki'

/**
 * Brand-safe dark theme for code rendering. Cyan/teal/emerald/sky hues only —
 * no pink, purple, or orange anywhere.
 */
export const synapseTheme: ThemeRegistration = {
  name: 'synapse-dark',
  type: 'dark',
  colors: {
    'editor.background': '#0d151f',
    'editor.foreground': '#d7e3f0',
  },
  settings: [
    { scope: ['comment'], settings: { foreground: '#5f7489', fontStyle: 'italic' } },
    { scope: ['string'], settings: { foreground: '#34d399' } },
    { scope: ['constant.numeric'], settings: { foreground: '#5eead4' } },
    { scope: ['keyword', 'storage'], settings: { foreground: '#22d3ee' } },
    { scope: ['entity.name.type', 'entity.other.inherited-class', 'support.type'], settings: { foreground: '#67e8f9' } },
    { scope: ['entity.name.function'], settings: { foreground: '#38bdf8' } },
    { scope: ['meta.annotation', 'storage.type.annotation', 'entity.name.tag'], settings: { foreground: '#2dd4bf' } },
    { scope: ['variable', 'meta.variable'], settings: { foreground: '#d7e3f0' } },
  ],
}

const highlighter = await createHighlighter({
  themes: [synapseTheme],
  langs: ['java', 'groovy', 'kotlin', 'properties'],
})

export function highlightJava(code: string): string {
  return highlighter.codeToHtml(code, { lang: 'java', theme: synapseTheme })
}

export type SnippetLang = 'java' | 'groovy' | 'kotlin' | 'properties';

export function highlightCode(code: string, lang: SnippetLang): string {
  return highlighter.codeToHtml(code, { lang, theme: synapseTheme })
}
