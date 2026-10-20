// Hash router. A route is [regex, viewFn]; viewFn(container, params, query) may return a cleanup function.
//
// Routes used by the app:
//   #/                     dashboard (default tab)
//   #/tab/<id>             dashboard on a tab
//   #/edit/service/new     new service
//   #/edit/service/<id>    edit service
//   #/tabs                 tab management
//   #/settings             settings
//   #/login                login

let routes = [];
let container = null;
let cleanup = null;
let fallback = null;

export function startRouter(target, routeTable, notFound) {
  container = target;
  routes = routeTable;
  fallback = notFound;
  window.addEventListener('hashchange', render);
  render();
}

export function navigate(hash) {
  if (window.location.hash === hash) render();
  else window.location.hash = hash;
}

export function currentHash() {
  return window.location.hash || '#/';
}

let generation = 0;

/** Each render gets a fresh root element, so a slow (stale) view only ever draws into a detached node. */
export async function render() {
  const token = ++generation;
  const [path, queryString] = currentHash().split('?');
  const query = new URLSearchParams(queryString || '');
  if (typeof cleanup === 'function') {
    try { cleanup(); } catch (e) { console.error(e); }
  }
  cleanup = null;
  const root = document.createElement('div');
  root.className = 'view-root';
  container.replaceChildren(root);

  let view = fallback;
  let params = [];
  for (const [pattern, candidate] of routes) {
    const match = pattern.exec(path);
    if (match) {
      view = candidate;
      params = match.slice(1).map(decodeURIComponent);
      break;
    }
  }
  if (!view) return;
  let result = null;
  try {
    result = await view(root, params, query);
  } catch (e) {
    console.error(e);
  }
  if (token === generation) cleanup = result;
  else if (typeof result === 'function') result();
}
