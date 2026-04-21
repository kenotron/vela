/**
 * @vela/runtime — the Vela Mini App JavaScript SDK.
 * Served at /lib/vela-runtime.js by the local Ktor server.
 * Import via import map: import { vela } from '@vela/runtime'
 */

const _h = { 'Content-Type': 'application/json' };
const _post = (url, body) =>
  fetch(url, { method: 'POST', headers: _h, body: JSON.stringify(body) }).then(r => r.json());

export const vela = {
  vault: {
    read:  (path, fmt) =>
      fetch(`/api/vault/read?path=${encodeURIComponent(path)}${fmt ? '&format=' + fmt : ''}`)
        .then(r => fmt === 'json' ? r.json() : r.text()),
    list:  (path = '') =>
      fetch(`/api/vault/list?path=${encodeURIComponent(path)}`).then(r => r.json()),
    write: (path, content) => _post('/api/vault/write', { path, content }),
  },
  db: {
    query:  (sql, params = []) => _post('/api/db/query',  { sql, params }),
    mutate: (sql, params = []) => _post('/api/db/mutate', { sql, params }),
  },
  ai: {
    complete: (prompt, systemPrompt) =>
      _post('/api/ai/complete', { prompt, systemPrompt }).then(r => r.text),
  },
  events: {
    emit: (name, data) => _post('/api/events/emit', { name, data }),
    on:   (name, fn) => {
      let t = Date.now();
      setInterval(async () => {
        const evs = await fetch(`/api/events/poll?since=${t}`).then(r => r.json());
        t = Date.now();
        evs.filter(e => e.name === name).forEach(e => fn(e.data));
      }, 3000);
    },
  },
  app: {
    get context() { return window.__VELA_CONTEXT__ || {}; },
    navigate: (relPath)               => _post('/api/app/navigate', { relPath }),
    notify:   (message, type = 'info') => _post('/api/app/notify',  { message, type }),
    refresh:  ()                      => _post('/api/app/refresh',  {}),
    remix:    ()                      => _post('/api/app/remix',    {}),
    record:   (options = {})          => _post('/api/app/record',   options),
  },
};
