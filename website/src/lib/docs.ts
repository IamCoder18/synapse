export interface DocsGroup {
  id: string;
  title: string;
  description?: string;
  url: string;
}

export interface DocsHeading {
  depth: number;
  slug: string;
  text: string;
}

export interface DocsPagerLink {
  url: string;
  title: string;
}

export const GROUP_ORDER = [
  'get-started',
  'concepts',
  'annotations',
  'ftc',
  'recipes',
  'guides',
  'api',
] as const;

export const GROUP_TITLES: Record<string, string> = {
  'get-started': 'Get Started',
  concepts: 'Concepts',
  annotations: 'Annotations',
  ftc: 'FTC Integration',
  recipes: 'Recipes',
  guides: 'Guides',
  api: 'API Reference',
};
