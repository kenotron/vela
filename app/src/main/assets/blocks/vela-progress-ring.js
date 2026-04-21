import { LitElement, html, css } from 'lit';

export class VelaProgressRing extends LitElement {
  static styles = css`
    :host { display: inline-flex; flex-direction: column; align-items: center; gap: 8px; }
    .wrap { position: relative; display: inline-block; }
    svg { transform: rotate(-90deg); display: block; }
    .pct { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-size: 18px; font-weight: 700; color: var(--primary, #7c6ff7); }
    .label { font-size: 13px; color: var(--muted, #9ca3af); text-align: center; }
  `;
  static properties = { value: { type: Number }, max: { type: Number }, title: { type: String }, size: { type: Number } };
  constructor() { super(); this.value = 0; this.max = 100; this.title = ''; this.size = 80; }
  render() {
    const r = (this.size / 2) - 6;
    const circ = 2 * Math.PI * r;
    const pct = Math.min(1, (this.value || 0) / (this.max || 100));
    const offset = circ * (1 - pct);
    return html`
      <div class="wrap" style="width:${this.size}px;height:${this.size}px">
        <svg width=${this.size} height=${this.size} viewBox="0 0 ${this.size} ${this.size}">
          <circle cx=${this.size/2} cy=${this.size/2} r=${r} fill="none" stroke="var(--surface2,#f3f4f6)" stroke-width="6"/>
          <circle cx=${this.size/2} cy=${this.size/2} r=${r} fill="none" stroke="var(--primary,#7c6ff7)" stroke-width="6"
            stroke-linecap="round" stroke-dasharray=${circ} stroke-dashoffset=${offset}
            style="transition:stroke-dashoffset 0.5s ease"/>
        </svg>
        <div class="pct">${Math.round(pct * 100)}%</div>
      </div>
      ${this.title ? html`<div class="label">${this.title}</div>` : ''}
    `;
  }
}
customElements.define('vela-progress-ring', VelaProgressRing);
