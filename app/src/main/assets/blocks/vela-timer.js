import { LitElement, html, css } from 'lit';

export class VelaTimer extends LitElement {
  static styles = css`
    :host { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 16px; }
    .lbl { font-size: 12px; color: var(--muted, #9ca3af); text-transform: uppercase; letter-spacing: .08em; }
    .disp { font-size: 36px; font-weight: 700; color: var(--primary, #7c6ff7); font-family: monospace; font-variant-numeric: tabular-nums; }
    .btns { display: flex; gap: 8px; margin-top: 4px; }
    .btn { width: 40px; height: 40px; border-radius: 50%; border: none; font-size: 18px; cursor: pointer; }
    .start { background: var(--primary, #7c6ff7); color: white; }
    .reset { background: var(--surface2, #e5e7eb); color: var(--text, #374151); }
  `;
  static properties = { duration: { type: Number }, label: { type: String }, _remaining: { state: true }, _running: { state: true } };
  constructor() { super(); this.duration = 300; this.label = 'Timer'; this._remaining = 300; this._running = false; this._interval = null; }
  connectedCallback() { super.connectedCallback(); this._remaining = this.duration; }
  disconnectedCallback() { super.disconnectedCallback(); clearInterval(this._interval); }
  _toggle() {
    if (this._running) { clearInterval(this._interval); this._running = false; return; }
    if (this._remaining <= 0) this._remaining = this.duration;
    this._interval = setInterval(() => {
      this._remaining--;
      if (this._remaining <= 0) { clearInterval(this._interval); this._running = false; this.dispatchEvent(new CustomEvent('complete')); }
    }, 1000);
    this._running = true;
  }
  _reset() { clearInterval(this._interval); this._running = false; this._remaining = this.duration; }
  _fmt(s) { return `${String(Math.floor(s / 60)).padStart(2,'0')}:${String(s % 60).padStart(2,'0')}`; }
  render() {
    return html`
      <div class="lbl">${this.label}</div>
      <div class="disp">${this._fmt(this._remaining)}</div>
      <div class="btns">
        <button class="btn start" @click=${this._toggle}>${this._running ? '⏸' : '▶'}</button>
        <button class="btn reset" @click=${this._reset}>↺</button>
      </div>
    `;
  }
}
customElements.define('vela-timer', VelaTimer);
