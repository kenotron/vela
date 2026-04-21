import { LitElement, html, css } from 'lit';

export class VelaTimeline extends LitElement {
  static styles = css`
    :host { display: block; }
    .tl { position: relative; padding-left: 28px; }
    .tl::before { content: ''; position: absolute; left: 8px; top: 8px; bottom: 8px; width: 2px; background: var(--border, #e5e7eb); }
    .ev { position: relative; margin-bottom: 20px; }
    .dot { position: absolute; left: -24px; top: 4px; width: 12px; height: 12px; border-radius: 50%; background: var(--primary, #7c6ff7); border: 2px solid white; }
    .dot.done { background: #10b981; }
    .text { font-size: 14px; line-height: 1.5; color: var(--text, #374151); }
    .date { font-size: 12px; color: var(--muted, #9ca3af); margin-top: 3px; }
  `;
  static properties = { events: { type: Array } };
  constructor() { super(); this.events = []; }
  render() {
    const items = this.events || [];
    if (!items.length) return html`<div style="font-size:13px;color:var(--muted)">No events</div>`;
    return html`
      <div class="tl">${items.map(e => html`
        <div class="ev">
          <div class="dot ${e.done ? 'done' : ''}"></div>
          <div class="text">${e.text || e.label || String(e)}</div>
          ${e.date ? html`<div class="date">${e.date}</div>` : ''}
        </div>
      `)}</div>
    `;
  }
}
customElements.define('vela-timeline', VelaTimeline);
