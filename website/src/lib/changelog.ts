import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const REPO_ROOT = new URL('../../..', import.meta.url).pathname;

export interface ChangelogSection {
  type: 'added' | 'changed' | 'fixed' | 'removed' | 'deprecated' | 'security' | 'other';
  bullets: string[];
}

export interface ChangelogRelease {
  version: string | null;
  date: string | null;
  raw: string;
  sections: ChangelogSection[];
  isUnreleased: boolean;
  isLatest: boolean;
}

const HEADER_RE = /^## \[(.+?)\](?:\s*-\s*(.+?))?\s*$/;
const SECTION_RE = /^### (.+)$/;

function normaliseSection(name: string): ChangelogSection['type'] {
  const n = name.trim().toLowerCase();
  if (n.startsWith('add')) return 'added';
  if (n.startsWith('chang')) return 'changed';
  if (n.startsWith('fix') || n.startsWith('remove bug')) return 'fixed';
  if (n.startsWith('remove')) return 'removed';
  if (n.startsWith('deprecate')) return 'deprecated';
  if (n.startsWith('secur')) return 'security';
  return 'other';
}

export function parseChangelog(markdown: string): ChangelogRelease[] {
  const lines = markdown.split(/\r?\n/);
  const releases: ChangelogRelease[] = [];
  let current: ChangelogRelease | null = null;
  let currentSection: ChangelogSection | null = null;

  for (const line of lines) {
    const header = line.match(HEADER_RE);
    if (header) {
      if (current) {
        if (currentSection) current.sections.push(currentSection);
        releases.push(current);
      }
      const version = header[1].trim();
      const date = (header[2] ?? '').trim() || null;
      const isUnreleased = version.toLowerCase() === 'unreleased';
      current = {
        version: isUnreleased ? null : version,
        date,
        raw: '',
        sections: [],
        isUnreleased,
        isLatest: false,
      };
      currentSection = null;
      continue;
    }
    if (!current) continue;
    current.raw += line + '\n';
    const section = line.match(SECTION_RE);
    if (section) {
      if (currentSection) current.sections.push(currentSection);
      currentSection = { type: normaliseSection(section[1]), bullets: [] };
      continue;
    }
    if (currentSection && /^\s*-\s+/.test(line)) {
      currentSection.bullets.push(line.replace(/^\s*-\s+/, '').trim());
    }
  }
  if (current) {
    if (currentSection) current.sections.push(currentSection);
    releases.push(current);
  }

  // mark latest stable release
  const firstStable = releases.find((r) => !r.isUnreleased);
  if (firstStable) firstStable.isLatest = true;

  return releases;
}

export function loadChangelog(): ChangelogRelease[] {
  const file = join(REPO_ROOT, 'CHANGELOG.md');
  const md = readFileSync(file, 'utf8');
  return parseChangelog(md);
}

let cachedVersion: string | null = null;

export function latestVersion(): string {
  if (cachedVersion) return cachedVersion;
  const latest = loadChangelog().find((r) => r.isLatest);
  if (!latest?.version) throw new Error('CHANGELOG.md has no stable release entry');
  cachedVersion = latest.version;
  return cachedVersion;
}
