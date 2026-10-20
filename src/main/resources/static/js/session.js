// Who is looking at the page and what they may do (from GET /api/auth/me).

import { api } from './api.js';

/** Used while the auth API is missing (404): no login, everything editable. */
const NO_AUTH = Object.freeze({
  authenticated: false, username: null, passwordSet: false,
  requireLoginToView: false, requireLoginToEdit: false,
  canView: true, canEdit: true, authAvailable: false,
});

export const session = {
  me: NO_AUTH,
  /** Hash to return to after login. */
  returnTo: null,
  /** Tab last shown on the dashboard, used as default for new services. */
  currentTabId: null,

  get canEdit() { return !!this.me.canEdit; },
  get canView() { return this.me.canView !== false; },
};

export async function refreshSession() {
  try {
    const { data } = await api.auth.me();
    session.me = { ...data, authAvailable: true };
  } catch (e) {
    // 404 → the backend has no auth module; anything else → assume the safest useful default.
    session.me = e.notFound ? NO_AUTH : { ...NO_AUTH, canEdit: false, authAvailable: true };
  }
  return session.me;
}

/** Call after login/logout/auth changes: refreshes the session and tells the app shell to redraw. */
export async function sessionChanged() {
  await refreshSession();
  document.dispatchEvent(new Event('heim:session'));
  return session.me;
}
