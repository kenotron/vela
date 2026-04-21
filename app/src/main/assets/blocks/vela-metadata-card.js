import { LitElement, html, css } from 'lit';

export class VelaMetadataCard extends LitElement {
  static styles = css`
    :host { display: block; border-radius: 12px; background: var(--surface2, #f9fafb); padding: 16px; }
    .row { display: flex; justify-content: space-between; align-items: flex-start; padding: 8px 0; border-bottom: 1px solid var(--border, #e5e7eb); gap: 16px; }
    .row:last-child { border-bottom: none; }
    .key { font-size: 12px; color: var(--muted, #9ca3af); font-weight: 600; text-transform: uppercase; letter-spacing: .05em; flex-shrink: 0; }
    .val { font-size: 14px; color: var(--text, #111); text-align: right; word-break: break-word; }
  `;
  static properties = { frontmatter: { type: Object } };
  constructor() { super(); this.frontmatter = {}; }
  render() {
    const entries = Object.entries(this.frontmatter || {}).filter(([k]) => !k.startsWith('_'));
    if (!entries.length) return html`<div style="font-size:13px;color:var(--muted)">No metadata</div>`;
    return html`${entries.map(([k, v]) => html`
      <div class="row">
        <span class="key">${k.replace(/_/g, ' ')}</span>
        <span class="val">${String(v)}</span>
      </div>
    `)}`;
  }
}
customElements.define('vela-metadata-card', VelaMetadataCard);
