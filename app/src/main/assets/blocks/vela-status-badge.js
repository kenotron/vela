import { LitElement, html, css } from 'lit';

export class VelaStatusBadge extends LitElement {
  static styles = css`
    :host { display: inline-block; }
    .badge { display: inline-flex; align-items: center; padding: 6px 14px; border-radius: 20px; font-size: 13px; font-weight: 700; cursor: pointer; user-select: none; transition: opacity 0.15s; }
    .badge:hover { opacity: 0.85; }
    .open     { background: #fef3c7; color: #92400e; border: 1px solid #fcd34d; }
    .done, .resolved, .closed { background: #d1fae5; color: #065f46; border: 1px solid #6ee7b7; }
    .blocked, .critical { background: #fee2e2; color: #991b1b; border: 1px solid #fca5a5; }
    .review, .pending { background: #ede9fe; color: #5b21b6; border: 1px solid #c4b5fd; }
    .default  { background: #f3f4f6; color: #374151; border: 1px solid #d1d5db; }
  `;
  static properties = { status: { type: String }, statuses: { type: Array } };
  constructor() { super(); this.status = 'open'; this.statuses = ['open', 'review', 'done', 'blocked']; }
  _cycle() {
    const idx = (this.statuses || ['open']).indexOf(this.status);
    this.status = (this.statuses || ['open'])[(idx + 1) % (this.statuses || ['open']).length];
    this.dispatchEvent(new CustomEvent('change', { detail: this.status }));
  }
  render() {
    const known = ['open','done','resolved','closed','blocked','critical','review','pending'];
    const cls = known.includes(this.status) ? this.status : 'default';
    return html`<div class="badge ${cls}" @click=${this._cycle}>${this.status}</div>`;
  }
}
customElements.define('vela-status-badge', VelaStatusBadge);
