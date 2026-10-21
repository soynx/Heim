// Who is looking at the page and what they may do (GET /api/auth/me).

import { api } from './api.js';

/** Until /api/auth/me answers: assume nothing may be edited. */
const UNKNOWN = Object.freeze({
  authenticated: false, username: null, passwordSet: false,
  requireLoginToView: false, requireLoginToEdit: false, canView: true, canEdit: false,
});

export const session = {
  me: UNKNOWN,
  /** Hash to return to after login. */
  returnTo: null,
};

export async function refreshSession() {
  try {
    const { data } = await api.auth.me();
    session.me = data;
  } catch {
    session.me = UNKNOWN;
  }
  return session.me;
}
