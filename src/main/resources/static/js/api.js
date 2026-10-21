// Fetch wrapper and endpoint catalogue. Every call resolves to { data, etag } or throws ApiError.
//
// - JSON in/out, same-origin credentials (session cookie).
// - CSRF: the XSRF-TOKEN cookie is echoed as X-XSRF-TOKEN on every non-GET request.
// - Optimistic locking: pass { etag } to send If-Match; a 409 throws ApiError with `conflict = true`.
//   ETags are per resource: a service/tab/settings write takes the ETag of that resource's GET;
//   tab create/reorder take the ETag of the tab list (it covers the tab order only).
// - 401 notifies onUnauthorized listeners (the app shows the login view) unless { quiet401: true }.

export class ApiError extends Error {
  constructor(status, error, message) {
    super(message || error || `HTTP ${status}`);
    this.status = status;
    this.error = error;
    this.conflict = status === 409;
    this.notFound = status === 404;
    this.unauthorized = status === 401;
  }
}

const unauthorizedListeners = new Set();

/** Registers a callback for 401 responses. Returns an unsubscribe function. */
export function onUnauthorized(fn) {
  unauthorizedListeners.add(fn);
  return () => unauthorizedListeners.delete(fn);
}

function cookie(name) {
  const prefix = name + '=';
  const hit = document.cookie.split(';').map((c) => c.trim()).find((c) => c.startsWith(prefix));
  return hit ? decodeURIComponent(hit.slice(prefix.length)) : null;
}

/** Spring issues the CSRF cookie lazily; a cheap GET makes sure it exists before the first write. */
async function ensureCsrfCookie() {
  if (cookie('XSRF-TOKEN')) return;
  try { await fetch('/api/auth/me', { credentials: 'same-origin' }); } catch { /* ignore */ }
}

async function parseBody(res) {
  if (res.status === 204) return null;
  const type = res.headers.get('Content-Type') || '';
  if (type.includes('json')) return res.json();
  return res.text();
}

export async function request(method, path, { body, form, etag, quiet401 = false } = {}) {
  const headers = { Accept: 'application/json' };
  const init = { method, headers, credentials: 'same-origin' };
  if (form) {
    init.body = form; // FormData: browser sets the multipart boundary
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  if (method !== 'GET' && method !== 'HEAD') {
    await ensureCsrfCookie();
    const token = cookie('XSRF-TOKEN');
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  if (etag) headers['If-Match'] = etag;

  let res;
  try {
    res = await fetch(path, init);
  } catch (e) {
    throw new ApiError(0, 'Network error', 'Cannot reach the server: ' + e.message);
  }

  if (!res.ok) {
    let payload = null;
    try { payload = await parseBody(res); } catch { /* not JSON */ }
    const error = payload && typeof payload === 'object' ? payload.error : null;
    let message = payload && typeof payload === 'object' ? payload.message : null;
    if (res.status === 409) {
      message = (message ? message + ' — ' : '') + 'This was changed elsewhere. Reload to get the latest version.';
    }
    if (res.status === 403 && !message) message = 'Forbidden (missing permission or CSRF token). Try reloading.';
    const err = new ApiError(res.status, error || res.statusText, message);
    if (res.status === 401 && !quiet401 && unauthorizedListeners.size) {
      err.handled = true; // the login view takes over; callers need not show this error
      unauthorizedListeners.forEach((fn) => fn(err));
    }
    throw err;
  }
  return { data: await parseBody(res), etag: res.headers.get('ETag') };
}

const get = (path, opts) => request('GET', path, opts);
const enc = encodeURIComponent;

function fileForm(file) {
  const form = new FormData();
  form.append('file', file);
  return form;
}

export const api = {
  dashboard: () => get('/api/dashboard'),

  services: {
    list: () => get('/api/services'),
    get: (id) => get(`/api/services/${enc(id)}`),
    create: (body) => request('POST', '/api/services', { body }),
    update: (id, body, etag) => request('PUT', `/api/services/${enc(id)}`, { body, etag }),
    remove: (id, etag) => request('DELETE', `/api/services/${enc(id)}`, { etag }),
    uploadIcon: (id, file, etag) => request('POST', `/api/services/${enc(id)}/icon`, { form: fileForm(file), etag }),
    refreshIcon: (id) => request('POST', `/api/services/${enc(id)}/icon/refresh`),
  },

  icons: {
    library: (q) => get(`/api/icons/library?q=${enc(q)}`),
  },

  tabs: {
    list: () => get('/api/tabs'),
    get: (id) => get(`/api/tabs/${enc(id)}`),
    create: (body, etag) => request('POST', '/api/tabs', { body, etag }),
    update: (id, body, etag) => request('PUT', `/api/tabs/${enc(id)}`, { body, etag }),
    remove: (id, etag) => request('DELETE', `/api/tabs/${enc(id)}`, { etag }),
    reorder: (tabIds, etag) => request('PUT', '/api/tabs/order', { body: { tabIds }, etag }),
    setGroups: (id, groups, etag) => request('PUT', `/api/tabs/${enc(id)}/groups`, { body: groups, etag }),
  },

  settings: {
    get: () => get('/api/settings'),
    update: (body, etag) => request('PUT', '/api/settings', { body, etag }),
    uploadBackground: (file, etag) => request('POST', '/api/settings/background', { form: fileForm(file), etag }),
    updateAuth: (body) => request('PUT', '/api/settings/auth', { body }),
  },

  auth: {
    me: () => get('/api/auth/me', { quiet401: true }),
    login: (username, password) => request('POST', '/api/auth/login', { body: { username, password }, quiet401: true }),
    logout: () => request('POST', '/api/auth/logout'),
  },

  backup: {
    exportUrl: '/api/backup/export',
    importZip: (file) => request('POST', '/api/backup/import', { form: fileForm(file) }),
  },

  status: {
    get: () => get('/api/status', { quiet401: true }),
    streamUrl: '/api/status/stream',
  },
};
