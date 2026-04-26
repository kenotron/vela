import { LitElement, html, css } from 'lit';

export class VelaTable extends LitElement {
  static styles = css`
    :host { display: block; overflow-x: auto; -webkit-overflow-scrolling: touch; }
    table { width: 100%; border-collapse: collapse; font-size: 14px; }
    thead tr { border-bottom: 2px solid var(--border, #e5e7eb); }
    th {
      text-align: left; padding: 8px 12px; white-space: nowrap;
      font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em;
      color: var(--muted, #9ca3af); cursor: pointer; user-select: none;
    }
    th:hover { color: var(--text, #374151); }
    th.asc::after  { content: ' ↑'; color: var(--primary, #7c6ff7); }
    th.desc::after { content: ' ↓'; color: var(--primary, #7c6ff7); }
    td { padding: 10px 12px; border-bottom: 1px solid var(--border, #e5e7eb); color: var(--text, #374151); vertical-align: top; }
    tr:last-child td { border-bottom: none; }
    tr:hover td { background: var(--surface2, #f9fafb); }
    .empty { font-size: 13px; color: var(--muted, #9ca3af); padding: 12px 0; }
  `;

  static properties = {
    columns: { type: Array },
    rows:    { type: Array },
    _col:    { state: true },
    _dir:    { state: true },
  };

  constructor() {
    super();
    this.columns = [];
    this.rows    = [];
    this._col    = -1;
    this._dir    = 'asc';
  }

  _sort(i) {
    this._dir = this._col === i && this._dir === 'asc' ? 'desc' : 'asc';
    this._col = i;
  }

  render() {
    const cols = this.columns || [];
    let rows = [...(this.rows || [])];
    if (!cols.length && !rows.length) return html``;

    if (this._col >= 0) {
      const m = this._dir === 'asc' ? 1 : -1;
      rows.sort((a, b) => {
        const av = String(a[this._col] ?? '').toLowerCase();
        const bv = String(b[this._col] ?? '').toLowerCase();
        return av < bv ? -m : av > bv ? m : 0;
      });
    }

    return html`
      <table>
        <thead><tr>${cols.map((c, i) => html`
          <th class="${this._col === i ? this._dir : ''}" @click=${() => this._sort(i)}>${c}</th>
        `)}</tr></thead>
        <tbody>${rows.length
          ? rows.map(row => html`<tr>${
              (Array.isArray(row) ? row : cols.map(c => row[c] ?? '')).map(cell => html`<td>${cell}</td>`)
            }</tr>`)
          : html`<tr><td class="empty" colspan="${cols.length}">No data</td></tr>`
        }</tbody>
      </table>
    `;
  }
}

customElements.define('vela-table', VelaTable);
