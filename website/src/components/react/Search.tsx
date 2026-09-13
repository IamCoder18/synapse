import { useEffect, useState } from 'react';
import { create, insertMultiple, search, type AnyOrama } from '@orama/orama';

interface Hit {
  url: string;
  title: string;
  description?: string;
}

export default function Search() {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<Hit[]>([]);
  const [db, setDb] = useState<AnyOrama | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setOpen((o) => !o);
      }
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  useEffect(() => {
    if (!open || db) return;
    fetch('/search.json')
      .then((r) => (r.ok ? r.json() : null))
      .then(async (json) => {
        if (!json) return;
        const inst = create({
          schema: { url: 'string', title: 'string', description: 'string', content: 'string' } as const,
        });
        await insertMultiple(inst, json);
        setDb(inst);
      })
      .catch(() => {});
  }, [open, db]);

  useEffect(() => {
    if (!db || !query.trim()) {
      setHits([]);
      return;
    }
    let cancelled = false;
    const apply = (res: any) =>
      setHits(
        (res?.hits ?? []).slice(0, 8).map((h: any) => ({
          url: h.document.url,
          title: h.document.title,
          description: h.document.description,
        })),
      );
    try {
      Promise.resolve(
        search(db, {
          term: query,
          properties: ['title', 'description', 'content'],
        }),
      )
        .then((res) => {
          if (!cancelled) apply(res);
        })
        .catch(() => {
          if (!cancelled) setHits([]);
        });
    } catch {
      setHits([]);
    }
    return () => {
      cancelled = true;
    };
  }, [query, db]);

  return (
    <>
      <button
        type="button"
        className="syn-search-trigger"
        onClick={() => setOpen(true)}
        aria-label="Open search"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <circle cx="11" cy="11" r="7" />
          <path d="M21 21l-4.3-4.3" />
        </svg>
        <span>Search docs</span>
        <kbd>⌘K</kbd>
      </button>

      {open && (
        <div className="syn-search-backdrop" onClick={() => setOpen(false)}>
          <div className="syn-search" role="dialog" aria-modal="true" aria-label="Search docs" onClick={(e) => e.stopPropagation()}>
            <input
              autoFocus
              type="search"
              placeholder="Search docs (try: SafeOpMode, hardware thread, topic)…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <ul role="list">
              {hits.length === 0 && query && <li className="syn-search__empty">No results.</li>}
              {hits.map((h) => (
                <li key={h.url}>
                  <a href={h.url} onClick={() => setOpen(false)}>
                    <strong>{h.title}</strong>
                    {h.description && <span>{h.description}</span>}
                  </a>
                </li>
              ))}
            </ul>
            <p className="syn-search__hint">
              <kbd>Esc</kbd> to close · <kbd>⌘</kbd>/<kbd>Ctrl</kbd>+<kbd>K</kbd> to open
            </p>
          </div>
          <style>{`
            .syn-search-backdrop {
              position: fixed; inset: 0; z-index: 100;
              background: rgba(0,0,0,0.55);
              display: grid; place-items: start center;
              padding-top: 8vh;
            }
            .syn-search {
              width: min(560px, 92vw);
              background: var(--card);
              border: 1px solid var(--border);
              border-radius: 12px;
              padding: 1rem;
              color: var(--foreground);
            }
            .syn-search input {
              width: 100%; padding: 0.7rem 0.9rem; border-radius: 8px;
              background: var(--muted); border: 1px solid var(--border);
              color: var(--foreground); font-size: 1rem;
            }
            .syn-search ul { list-style: none; padding: 0; margin: 0.6rem 0; max-height: 50vh; overflow: auto; }
            .syn-search li a {
              display: block; padding: 0.55rem 0.7rem; border-radius: 8px;
              color: var(--foreground); text-decoration: none;
            }
            .syn-search li a:hover { background: var(--muted); }
            .syn-search li a span { display: block; color: var(--muted-foreground); font-size: 0.85rem; }
            .syn-search__empty { color: var(--muted-foreground); padding: 0.7rem; }
            .syn-search__hint { color: var(--muted-foreground); font-size: 0.78rem; margin: 0; }
            .syn-search__hint kbd {
              background: var(--muted); border: 1px solid var(--border);
              border-radius: 4px; padding: 1px 5px; font-family: var(--font-mono);
            }
          `}</style>
        </div>
      )}

      <style>{`
        .syn-search-trigger {
          display: inline-flex;
          align-items: center;
          gap: 0.45rem;
          padding: 0.4rem 0.7rem;
          background: var(--card);
          border: 1px solid var(--border);
          border-radius: 999px;
          color: var(--muted-foreground);
          font-size: 0.85rem;
          cursor: pointer;
        }
        .syn-search-trigger:hover { border-color: var(--primary); color: var(--foreground); }
        .syn-search-trigger kbd {
          font-family: var(--font-mono);
          font-size: 0.7rem;
          padding: 1px 4px;
          border-radius: 4px;
          background: var(--muted);
          border: 1px solid var(--border);
          color: var(--muted-foreground);
        }
      `}</style>
    </>
  );
}
