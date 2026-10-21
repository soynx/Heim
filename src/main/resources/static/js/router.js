// Hash router. A route is [regex, view]; view(root, params) may return a cleanup function.
//
//   #/            dashboard on the default tab
//   #/tab/<id>    dashboard on a tab
//   #/settings    settings
//   #/login       sign in
//
// Every render gets a fresh <div class="app" id="app">: Heim.mount() binds its listeners to the
// root, so reusing one root would stack the listeners of every view shown before.

let routes = [];
let cleanup = null;
let generation = 0;

export function startRouter(routeTable) {
  routes = routeTable;
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

export async function render() {
  const token = ++generation;
  if (typeof cleanup === 'function') {
    try { cleanup(); } catch (e) { console.error(e); }
  }
  cleanup = null;
  const old = document.getElementById('app');
  const root = document.createElement('div');
  root.className = 'app';
  root.id = 'app';
  old.replaceWith(root);

  const path = currentHash().split('?')[0];
  let view = null;
  let params = [];
  for (const [pattern, candidate] of routes) {
    const match = pattern.exec(path);
    if (match) {
      view = candidate;
      params = match.slice(1).map(decodeURIComponent);
      break;
    }
  }
  if (!view) {
    navigate('#/');
    return;
  }
  let result = null;
  try {
    result = await view(root, params);
  } catch (e) {
    console.error(e);
  }
  if (token === generation) cleanup = result;
  else if (typeof result === 'function') result();
}
