// Entry point: session bootstrap, top bar, routes, global 401 handling.

import { api, onUnauthorized } from './api.js';
import { h, replace, button } from './dom.js';
import { session, refreshSession, sessionChanged } from './session.js';
import { startRouter, navigate, currentHash } from './router.js';
import { startStatus, restartStatus } from './status.js';
import { getPageTitle } from './chrome.js';
import { attempt, clearNotices } from './ui.js';
import { dashboardView } from './views/dashboard.js';
import { serviceEditorView } from './views/service-editor.js';
import { tabsView } from './views/tabs.js';
import { settingsView } from './views/settings.js';
import { loginView } from './views/login.js';

/** Views that need edit rights; without them the user is sent to login (or the dashboard). */
const requireEdit = (view) => (root, params, query) => {
  if (!session.canEdit) {
    if (session.me.authAvailable && !session.me.authenticated) {
      session.returnTo = currentHash();
      navigate('#/login');
    } else {
      navigate('#/');
    }
    return null;
  }
  return view(root, params, query);
};

const routes = [
  [/^#\/?$/, (root) => dashboardView(root, [null])],
  [/^#\/tab\/([^/]+)$/, dashboardView],
  [/^#\/edit\/service\/([^/]+)$/, requireEdit(serviceEditorView)],
  [/^#\/tabs$/, requireEdit(tabsView)],
  [/^#\/settings$/, requireEdit(settingsView)],
  [/^#\/login$/, loginView],
];

function renderTopbar() {
  const bar = document.getElementById('topbar');
  const me = session.me;
  const nav = h('nav', { class: 'topbar-nav' },
    h('a', { href: '#/' }, 'Dashboard'),
    session.canEdit ? h('a', { href: '#/tabs' }, 'Tabs') : null,
    session.canEdit ? h('a', { href: '#/settings' }, 'Settings') : null,
    me.authAvailable && me.authenticated
      ? [h('span', { class: 'topbar-user' }, me.username || ''), button('Log out', logout)]
      : null,
    me.authAvailable && !me.authenticated && me.passwordSet ? h('a', { href: '#/login' }, 'Log in') : null);
  replace(bar, h('a', { class: 'topbar-title', href: '#/' }, getPageTitle()), nav);
}

async function logout() {
  const ok = await attempt(() => api.auth.logout(), 'Logout failed');
  if (ok === undefined) return;
  await sessionChanged();
  clearNotices();
  navigate('#/');
}

onUnauthorized(() => {
  if (currentHash().startsWith('#/login')) return;
  session.returnTo = currentHash();
  navigate('#/login');
});

document.addEventListener('heim:title', renderTopbar);
document.addEventListener('heim:session', () => {
  renderTopbar();
  restartStatus();
});

async function boot() {
  await refreshSession();
  renderTopbar();
  startStatus();
  startRouter(document.getElementById('view'), routes, (root) => {
    root.append(h('p', {}, 'Page not found. ', h('a', { href: '#/' }, 'Back to the dashboard')));
  });
}

boot();
