import { LitElement, html, css } from 'lit';
import { markdownToHtml } from '@vela/remark';

// Strip YAML front matter before handing text to remark so --- fences
// don't render as thematic breaks or stray paragraphs.
function stripFrontmatter(text) {
  const m = text.match(/^---\r?\n[\s\S]*?\r?\n---\r?\n?/);
  return m ? text.slice(m[0].length) : text;
}

// Strip embedded line numbers from files exported via `nl` or `cat -n`.
// Pattern: optional spaces + digits + TAB at the start of each line.
// e.g. "       1\t# Backlog\n       2\t\n" → "# Backlog\n\n"
function stripLineNumbers(text) {
  return text.replace(/^[ \t]*\d+\t/gm, '');
}

export class VelaContentRenderer extends LitElement {
  static styles = css`
    :host { display: block; font-family: -apple-system, sans-serif; }

    /* ── Loading ─────────────────────────────────────────────────────── */
    .loading {
      font-size: 13px; color: var(--muted, #9ca3af);
      text-align: center; padding: 40px 0;
    }

    /* ── Markdown body resets ────────────────────────────────────────── */
    .md * { box-sizing: border-box; }

    /* ── Block spacing ───────────────────────────────────────────────── */
    .md > * + * { margin-top: 16px; }

    /* ── Headings ────────────────────────────────────────────────────── */
    .md h1 {
      font-size: 22px; font-weight: 700;
      color: var(--text, #111); margin: 0 0 4px;
      border-bottom: 2px solid var(--border, #e5e7eb); padding-bottom: 8px;
    }
    .md h2 {
      font-size: 13px; font-weight: 700; letter-spacing: .06em;
      text-transform: uppercase; color: var(--muted, #9ca3af);
      margin: 24px 0 8px;
    }
    .md h3 { font-size: 17px; font-weight: 600; color: var(--text, #111); margin: 0; }
    .md h4, .md h5, .md h6 { font-size: 14px; font-weight: 600; color: var(--text, #374151); margin: 0; }

    /* ── Paragraphs ──────────────────────────────────────────────────── */
    .md p { font-size: 15px; line-height: 1.7; color: var(--text, #374151); margin: 0; }

    /* ── Inline formatting ───────────────────────────────────────────── */
    .md strong { font-weight: 700; color: var(--text, #111); }
    .md em     { font-style: italic; }
    .md del    { text-decoration: line-through; opacity: .55; }
    .md a      { color: var(--primary, #7c6ff7); text-decoration: none; }
    .md a:hover{ text-decoration: underline; }

    /* ── Inline code ─────────────────────────────────────────────────── */
    .md code {
      font-family: ui-monospace, 'SF Mono', monospace;
      font-size: 12.5px; line-height: 1.4;
      background: var(--surface2, #f3f4f6); color: var(--text, #374151);
      border-radius: 4px; padding: 1px 5px;
    }

    /* ── Code blocks ─────────────────────────────────────────────────── */
    .md pre {
      background: var(--surface2, #f3f4f6); border-radius: 10px;
      padding: 14px; overflow-x: auto; margin: 0;
    }
    .md pre code {
      background: none; padding: 0; border-radius: 0;
      font-size: 12.5px; line-height: 1.55;
    }

    /* ── Blockquote ──────────────────────────────────────────────────── */
    .md blockquote {
      margin: 0; padding: 10px 14px;
      border-left: 3px solid var(--primary, #7c6ff7);
      background: var(--surface2, #f9fafb); border-radius: 0 8px 8px 0;
      font-style: italic; color: var(--muted, #6b7280);
    }
    .md blockquote > * + * { margin-top: 8px; }

    /* ── Lists ───────────────────────────────────────────────────────── */
    .md ul, .md ol { padding-left: 24px; margin: 0; }
    .md li {
      font-size: 14px; line-height: 1.7; color: var(--text, #374151);
      margin-top: 4px;
    }
    .md li > p { margin: 0; }
    .md li > * + * { margin-top: 4px; }

    /* ── GFM task lists ──────────────────────────────────────────────── */
    .md ul.contains-task-list { list-style: none; padding-left: 4px; }
    .md li.task-list-item {
      display: flex; align-items: flex-start; gap: 10px; padding: 6px 0;
      border-bottom: 1px solid var(--border, #f3f4f6);
    }
    .md li.task-list-item:last-child { border-bottom: none; }
    .md li.task-list-item input[type="checkbox"] {
      appearance: none; -webkit-appearance: none;
      width: 18px; height: 18px; flex-shrink: 0; margin-top: 1px;
      border: 2px solid var(--border, #d1d5db); border-radius: 4px;
      background: transparent; transition: all .15s; cursor: default;
    }
    .md li.task-list-item input[type="checkbox"]:checked {
      background: var(--primary, #7c6ff7);
      border-color: var(--primary, #7c6ff7);
    }
    .md li.task-list-item input[type="checkbox"]:checked::after {
      content: '✓'; display: block;
      text-align: center; line-height: 14px;
      font-size: 11px; font-weight: 700; color: white;
    }

    /* ── Tables ──────────────────────────────────────────────────────── */
    .md table {
      width: 100%; border-collapse: collapse;
      font-size: 14px; overflow-x: auto; display: block;
    }
    .md th {
      text-align: left; padding: 8px 12px;
      font-size: 11px; font-weight: 700; text-transform: uppercase;
      letter-spacing: .06em; color: var(--muted, #9ca3af);
      border-bottom: 2px solid var(--border, #e5e7eb);
    }
    .md td {
      padding: 10px 12px; border-bottom: 1px solid var(--border, #e5e7eb);
      color: var(--text, #374151); vertical-align: top;
    }
    .md tr:last-child td { border-bottom: none; }
    .md tr:hover td { background: var(--surface2, #f9fafb); }

    /* ── Horizontal rule ─────────────────────────────────────────────── */
    .md hr { border: none; border-top: 1px solid var(--border, #e5e7eb); margin: 0; }
  `;

  static properties = {
    _loading: { state: true },
    _html:    { state: true },
  };

  constructor() {
    super();
    this._loading = true;
    this._html    = '';
    this._started = false;
  }

  connectedCallback() {
    super.connectedCallback();
    this._pollForContext(0);
  }

  /** Called from onVelaReady in the template for a faster first paint. */
  load() {
    const p = window.__VELA_CONTEXT__?.itemPath;
    if (p && !this._started) this._fetch(p);
  }

  _pollForContext(n) {
    const p = window.__VELA_CONTEXT__?.itemPath;
    if (p) {
      if (!this._started) this._fetch(p);
    } else if (n < 100) {
      setTimeout(() => this._pollForContext(n + 1), 100);
    } else {
      this._loading = false;
    }
  }

  async _fetch(itemPath) {
    this._started = true;
    try {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), 8000);
      const r = await fetch(`/api/vault/read?path=${encodeURIComponent(itemPath)}`, { signal: ctrl.signal });
      clearTimeout(t);
      if (r.ok) {
        const raw  = await r.text();
        const text = stripLineNumbers(stripFrontmatter(raw));
        this._html = text.trim() ? await markdownToHtml(text) : '';
      }
    } catch (_) { /* timeout or network */ }
    this._loading = false;
  }

  /** Called when itemPath changes without a full page reload (SPA navigation). */
  reload() {
    const p = window.__VELA_CONTEXT__?.itemPath;
    if (!p || p === this._currentPath) return;
    this._currentPath = p;
    this._started = false;
    this._loading = true;
    this._html    = '';
    this._fetch(p);
  }

  updated(changed) {
    if (changed.has('_html')) {
      const el = this.renderRoot?.querySelector('.md');
      if (el) el.innerHTML = this._html;
    }
  }

  render() {
    if (this._loading) return html`<div class="loading">Loading…</div>`;
    return html`<div class="md"></div>`;
  }
}

customElements.define('vela-content-renderer', VelaContentRenderer);
