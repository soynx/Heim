// Notices (errors / info) shown above the current view.

import { h, button } from './dom.js';

const area = () => document.getElementById('notices');

function notice(kind, message, actions = [], timeoutMs = 0) {
  const el = h('div', { class: `notice notice-${kind}`, role: kind === 'error' ? 'alert' : 'status' },
    h('span', { class: 'notice-text' }, message),
    actions,
    h('button', { type: 'button', class: 'notice-close', 'aria-label': 'Dismiss', onclick: () => el.remove() }, '×'));
  area().append(el);
  if (timeoutMs) setTimeout(() => el.remove(), timeoutMs);
  return el;
}

export function showInfo(message) {
  return notice('info', message, [], 4000);
}

/** Shows an ApiError (or any Error). Conflicts get a reload button. */
export function showError(err, context) {
  const text = (context ? context + ': ' : '') + (err?.message || String(err));
  const actions = err?.conflict ? [button('Reload', () => window.location.reload())] : [];
  console.error(err);
  return notice('error', text, actions);
}

export function clearNotices() {
  area().replaceChildren();
}

/** Runs an async action and reports failures; resolves to the action's result or undefined. */
export async function attempt(action, context) {
  try {
    return await action();
  } catch (err) {
    if (!err?.handled) showError(err, context);
    return undefined;
  }
}
