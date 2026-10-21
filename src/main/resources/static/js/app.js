// Entry point: session, routes, live status and the global 401 → login hand-off.

import { onUnauthorized } from './api.js';
import { session, refreshSession } from './session.js';
import { startRouter, navigate, currentHash } from './router.js';
import { startStatus } from './status.js';
import { dashboardView } from './views/dashboard.js';
import { settingsView } from './views/settings.js';
import { loginView } from './views/login.js';

/** Settings need edit rights; without them the visitor signs in first. */
function requireEdit(view) {
  return (root, params) => {
    if (session.me.canEdit) return view(root, params);
    session.returnTo = currentHash();
    navigate('#/login');
    return null;
  };
}

onUnauthorized(() => {
  if (currentHash().startsWith('#/login')) return;
  session.returnTo = currentHash();
  navigate('#/login');
});

async function boot() {
  await refreshSession();
  startStatus();
  startRouter([
    [/^#\/?$/, (root) => dashboardView(root, [null])],
    [/^#\/tab\/([^/]+)$/, dashboardView],
    [/^#\/settings$/, requireEdit(settingsView)],
    [/^#\/login$/, loginView],
  ]);
}

boot();
