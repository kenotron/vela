import { LitElement, html, css } from 'lit';

export class VelaActionList extends LitElement {
  static styles = css`
    :host { display: block; }
    .item { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--border, #e5e7eb); }
    .item:last-child { border-bottom: none; }
    .box { width: 20px; height: 20px; border-radius: 4px; border: 2px solid var(--primary, #7c6ff7); cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; transition: background 0.15s; }
    .box.done { background: var(--primary, #7c6ff7); border-color: var(--primary, #7c6ff7); }
    .box.done::after { content: '✓'; color: white; font-size: 12px; font-weight: 700; }
    .label { flex: 1; font-size: 14px; }
    .label.done { text-decoration: line-through; opacity: 0.5; }
    .meta { font-size: 12px; color: var(--muted, #9ca3af); }
  `;
  static properties = { items: { type: Array } };
  constructor() { super(); this.items = []; }
  _toggle(i) {
    this.items = this.items.map((item, idx) =>
      idx === i ? { ...item, done: !item.done } : item
    );
    this.dispatchEvent(new CustomEvent('change', { detail: this.items }));
  }
  render() {
    return html`${(this.items || []).map((item, i) => {
      const text = typeof item === 'string' ? item : (item.text || item.label || '');
      const done = typeof item === 'object' && !!item.done;
      return html`
        <div class="item">
          <div class="box ${done ? 'done' : ''}" @click=${() => this._toggle(i)}></div>
          <span class="label ${done ? 'done' : ''}">${text}</span>
          ${item.assignee ? html`<span class="meta">${item.assignee}</span>` : ''}
        </div>
      `;
    })}`;
  }
}
customElements.define('vela-action-list', VelaActionList);
