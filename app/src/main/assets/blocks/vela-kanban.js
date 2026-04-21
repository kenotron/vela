import { LitElement, html, css } from 'lit';

export class VelaKanban extends LitElement {
  static styles = css`
    :host { display: block; overflow-x: auto; }
    .board { display: flex; gap: 12px; padding: 4px; min-width: min-content; }
    .col { min-width: 160px; max-width: 220px; flex: 1; }
    .col-head { font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: .08em; color: var(--muted, #9ca3af); margin-bottom: 10px; display: flex; justify-content: space-between; }
    .cnt { background: var(--surface2, #e5e7eb); border-radius: 10px; padding: 1px 7px; }
    .card { background: white; border: 1px solid var(--border, #e5e7eb); border-radius: 10px; padding: 12px; margin-bottom: 8px; font-size: 13px; line-height: 1.4; }
  `;
  static properties = { columns: { type: Array } };
  constructor() { super(); this.columns = [{ title: 'To Do', cards: [] }, { title: 'Doing', cards: [] }, { title: 'Done', cards: [] }]; }
  render() {
    return html`
      <div class="board">
        ${(this.columns || []).map(col => html`
          <div class="col">
            <div class="col-head">
              <span>${col.title}</span>
              <span class="cnt">${(col.cards || []).length}</span>
            </div>
            ${(col.cards || []).map(card => html`
              <div class="card">${typeof card === 'string' ? card : (card.text || card.title || card.label || '')}</div>
            `)}
          </div>
        `)}
      </div>
    `;
  }
}
customElements.define('vela-kanban', VelaKanban);
