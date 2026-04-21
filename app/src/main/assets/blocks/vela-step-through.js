import { LitElement, html, css } from 'lit';

export class VelaStepThrough extends LitElement {
  static styles = css`
    :host { display: block; }
    .wrap { padding: 20px; }
    .header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
    .num { width: 36px; height: 36px; border-radius: 50%; background: var(--primary, #7c6ff7); color: white; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 16px; flex-shrink: 0; }
    .step-title { font-size: 18px; font-weight: 600; flex: 1; }
    .body { font-size: 15px; line-height: 1.6; color: var(--text, #374151); margin-bottom: 24px; }
    .nav { display: flex; justify-content: space-between; align-items: center; }
    .btn { padding: 10px 20px; border-radius: 10px; border: none; font-size: 14px; font-weight: 600; cursor: pointer; }
    .prev { background: var(--surface2, #e5e7eb); color: var(--text, #374151); }
    .next { background: var(--primary, #7c6ff7); color: white; }
    .btn:disabled { opacity: 0.3; cursor: not-allowed; }
    .dots { display: flex; gap: 6px; }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--border, #d1d5db); transition: background 0.2s; }
    .dot.active { background: var(--primary, #7c6ff7); }
  `;
  static properties = { sections: { type: Array }, _cur: { state: true } };
  constructor() { super(); this.sections = []; this._cur = 0; }
  render() {
    const steps = (this.sections || []).filter(s => s.type === 'paragraph' || s.type === 'heading');
    if (!steps.length) return html`<div class="wrap"><p>No steps found.</p></div>`;
    const step = steps[this._cur];
    return html`
      <div class="wrap">
        <div class="header">
          <div class="num">${this._cur + 1}</div>
          <div class="step-title">${step.type === 'heading' ? step.text : `Step ${this._cur + 1}`}</div>
        </div>
        <div class="body">${step.text}</div>
        <div class="nav">
          <button class="btn prev" ?disabled=${this._cur === 0} @click=${() => this._cur--}>← Prev</button>
          <div class="dots">${steps.map((_, i) => html`<div class="dot ${i === this._cur ? 'active' : ''}"></div>`)}</div>
          <button class="btn next" ?disabled=${this._cur === steps.length - 1} @click=${() => this._cur++}>Next →</button>
        </div>
      </div>
    `;
  }
}
customElements.define('vela-step-through', VelaStepThrough);
