import { LitElement, html, css } from 'lit';

export class VelaHeatmapCalendar extends LitElement {
  static styles = css`
    :host { display: block; }
    .title { font-size: 13px; font-weight: 600; margin-bottom: 10px; }
    .grid { display: flex; gap: 3px; flex-wrap: wrap; }
    .cell { width: 12px; height: 12px; border-radius: 3px; }
    .l0 { background: var(--surface2, #e5e7eb); }
    .l1 { background: #c4b5fd; }
    .l2 { background: #a78bfa; }
    .l3 { background: var(--primary, #7c6ff7); }
    .l4 { background: #5b21b6; }
    .legend { display: flex; align-items: center; gap: 6px; margin-top: 10px; font-size: 11px; color: var(--muted, #9ca3af); }
    .legend-cells { display: flex; gap: 3px; }
  `;
  static properties = { data: { type: Array }, title: { type: String }, weeks: { type: Number } };
  constructor() { super(); this.data = []; this.title = ''; this.weeks = 12; }
  render() {
    const cells = Array.from({ length: this.weeks * 7 }, (_, i) => {
      const v = (this.data || [])[i];
      if (v === undefined || v === null) return 0;
      if (v === 0) return 0;
      if (v < 3) return 1;
      if (v < 5) return 2;
      if (v < 8) return 3;
      return 4;
    });
    return html`
      ${this.title ? html`<div class="title">${this.title}</div>` : ''}
      <div class="grid">${cells.map(l => html`<div class="cell l${l}"></div>`)}</div>
      <div class="legend">Less <div class="legend-cells">${[0,1,2,3,4].map(l => html`<div class="cell l${l}"></div>`)}</div> More</div>
    `;
  }
}
customElements.define('vela-heatmap-calendar', VelaHeatmapCalendar);
