// Ships car-side logs to the server (/api/log) so problems seen in the car can be
// debugged afterwards. Batched; never blocks the UI.

const queue = [];
let timer = null;
const session = Math.random().toString(36).slice(2, 10);

function flush() {
  timer = null;
  if (!queue.length) return;
  const batch = queue.splice(0, queue.length);
  const body = JSON.stringify(batch);
  try {
    if (!navigator.sendBeacon || !navigator.sendBeacon('/api/log', new Blob([body], { type: 'application/json' }))) {
      fetch('/api/log', { method: 'POST', body, headers: { 'content-type': 'application/json' }, keepalive: true }).catch(() => {});
    }
  } catch {
    // ignore
  }
}

export function rlog(level, msg, extra) {
  const entry = { s: session, lvl: level, msg: String(msg).slice(0, 2000) };
  if (extra !== undefined) entry.x = extra;
  queue.push(entry);
  if (queue.length > 500) queue.splice(0, queue.length - 500);
  if (!timer) timer = setTimeout(flush, level === 'error' ? 500 : 4000);
  const fn = level === 'error' ? console.error : level === 'warn' ? console.warn : console.log;
  fn.call(console, '[cm]', msg, extra ?? '');
}

export const log = (m, x) => rlog('info', m, x);
export const warn = (m, x) => rlog('warn', m, x);
export const error = (m, x) => rlog('error', m, x);

window.addEventListener('error', (e) => rlog('error', 'window.onerror: ' + e.message, { src: e.filename, line: e.lineno }));
window.addEventListener('unhandledrejection', (e) => rlog('error', 'unhandledrejection: ' + (e.reason?.stack || e.reason)));
window.addEventListener('pagehide', flush);
