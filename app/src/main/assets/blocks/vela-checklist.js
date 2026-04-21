import { LitElement, html, css } from 'lit';

export class VelaChecklist extends LitElement {
  static styles = css`
    :host { display: block; }
    .title { font-size: 13px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--muted, #9ca3af); margin: 0 0 8px; }
    .progress { height: 4px; background: var(--surface2, #f3f4f6); border-radius: 2px; margin-bottom: 6px; }
    .fill { height: 100%; background: var(--primary, #7c6ff7); border-radius: 2px; transition: width 0.3s; }
    .count { font-size: 12px; color: var(--muted, #9ca3af); margin-bottom: 10px; }
    .item { display: flex; align-items: center; gap: 10px; padding: 8px 0; cursor: pointer; }
    .box { width: 18px; height: 18px; border-radius: 4px; border: 2px solid var(--border, #d1d5db); flex-shrink: 0; transition: all 0.15s; display: flex; align-items: center; justify-content: center; }
    .box.checked { background: var(--primary, #7c6ff7); border-color: var(--primary, #7c6ff7); color: white; font-size: 11px; font-weight: 700; }
    .text { font-size: 14px; flex: 1; }
    .text.done { text-decoration: line-through; opacity: 0.45; }
  `;
  static properties = { items: { type: Array }, title: { type: String } };
  constructor() { super(); this.items = []; this.title = ''; }
  _toggle(i) {
    const item = this.items[i];
    const updated = typeof item === 'string'
      ? { text: item, done: true }
      : { ...item, done: !item.done };
    this.items = [...this.items.slice(0, i), updated, ...this.items.slice(i + 1)];
  }
  render() {
    const items = this.items || [];
    const done = items.filter(i => (typeof i === 'object' ? i.done : false)).length;
    const pct = items.length > 0 ? (done / items.length) * 100 : 0;
    return html`
      ${this.title ? html`<div class="title">${this.title}</div>` : ''}
      <div class="progress"><div class="fill" style="width:${pct}%"></div></div>
      <div class="count">${done} / ${items.length} completed</div>
      ${items.map((item, i) => {
        const text = typeof item === 'string' ? item : (item.text || item.label || '');
        const checked = typeof item === 'object' && !!item.done;
        return html`
          <div class="item" @click=${() => this._toggle(i)}>
            <div class="box ${checked ? 'checked' : ''}">${checked ? '✓' : ''}</div>
            <span class="text ${checked ? 'done' : ''}">${text}</span>
          </div>
        `;
      })}
    `;
  }
}
customElements.define('vela-checklist', VelaChecklist);
