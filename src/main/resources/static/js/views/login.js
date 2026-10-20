// Login form. After success, returns to the page that asked for login.

import { api } from '../api.js';
import { h, field } from '../dom.js';
import { session, sessionChanged, refreshSession } from '../session.js';
import { navigate } from '../router.js';
import { clearNotices } from '../ui.js';

export async function loginView(root) {
  await refreshSession(); // the cached session may be stale (e.g. expired after a 401)
  if (session.me.authAvailable === false) {
    root.append(h('p', {}, 'Login is not enabled. ', h('a', { href: '#/' }, 'Back to the dashboard')));
    return null;
  }
  if (session.me.authenticated) {
    root.append(h('p', {}, `Logged in as ${session.me.username || ''}. `, h('a', { href: '#/' }, 'Back to the dashboard')));
    return null;
  }

  const username = h('input', { type: 'text', autocomplete: 'username', required: true });
  const password = h('input', { type: 'password', autocomplete: 'current-password', required: true });
  const error = h('p', { class: 'error-text', role: 'alert', hidden: true });

  async function submit(e) {
    e.preventDefault();
    error.hidden = true;
    submitButton.disabled = true;
    try {
      await api.auth.login(username.value.trim(), password.value);
    } catch (err) {
      error.textContent = err.message || 'Login failed';
      error.hidden = false;
      password.select();
      return;
    } finally {
      submitButton.disabled = false;
    }
    await sessionChanged();
    clearNotices();
    const target = session.returnTo && !session.returnTo.startsWith('#/login') ? session.returnTo : '#/';
    session.returnTo = null;
    navigate(target);
  }

  const submitButton = h('button', { type: 'submit', class: 'primary' }, 'Log in');
  root.append(h('form', { class: 'editor login', onsubmit: submit },
    h('h1', {}, 'Log in'),
    field('Username', username),
    field('Password', password),
    error,
    h('div', { class: 'actions' }, submitButton)));
  username.focus();
  return null;
}
