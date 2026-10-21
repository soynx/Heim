// Dashboard: Heim.Dashboard over GET /api/dashboard, live status, and every edit-mode action.

import { api } from '../api.js';
import { session, refreshSession } from '../session.js';
import { navigate, render as rerender } from '../router.js';
import { onStatus, getStatus, restartStatus } from '../status.js';
import { dashboardToUi, applySnapshot, probeToUi, probeEvent } from '../adapter.js';
import { applyAppearance, confirmDialog, pickFile, showError, el } from '../ui.js';
import { openServiceEditor } from './service-editor.js';
import { openTabEditor } from './tab-editor.js';

const { Heim } = window;

export async function dashboardView(root, [tabId]) {
  let ui;
  try {
    const { data } = await api.dashboard();
    ui = dashboardToUi(data, getStatus());
  } catch (err) {
    if (!err.handled) loadFailed(root, err);
    return null;
  }
  applyAppearance(ui);

  const me = session.me;
  const cfg = {
    canEdit: me.canEdit,
    user: me.authenticated ? me.username : null,
    settingsHref: '#/settings',
    loginHref: me.passwordSet ? '#/login' : false,
    servicesFor: (id) => ui.servicesByTab[id] || [],
    onAction,
  };
  function fill() {
    Object.assign(cfg, {
      title: ui.title,
      tabs: ui.tabs,
      services: ui.services,
      background: ui.background,
      firstRun: ui.services.length === 0 && me.canEdit,
    });
  }
  fill();
  cfg.tab = (ui.tabs.find((t) => t.id === tabId) || ui.tabs.find((t) => t.isDefault) || ui.tabs[0] || {}).id;
  const dash = Heim.Dashboard(root, cfg);

  async function reload() {
    try {
      const { data } = await api.dashboard();
      ui = dashboardToUi(data, getStatus());
    } catch (err) {
      pageError(err);
      return;
    }
    applyAppearance(ui);
    fill();
    if (!ui.tabs.some((t) => t.id === dash.state.tab)) dash.state.tab = (ui.tabs.find((t) => t.isDefault) || ui.tabs[0]).id;
    dash.render();
  }

  function pageError(err) {
    showError(root.querySelector('#main'), err);
  }

  /** Writes run one after another, so quick keyboard moves arrive in order. */
  let queue = Promise.resolve();
  function enqueue(write) {
    queue = queue.then(write).catch((err) => { pageError(err); return reload(); });
    return queue;
  }

  function requireEdit() {
    if (me.canEdit) return true;
    session.returnTo = window.location.hash || '#/';
    navigate('#/login');
    return false;
  }

  function serviceName(id) {
    return ui.services.find((s) => s.id === id)?.name || 'this service';
  }

  function onAction(name, d) {
    switch (name) {
      case 'select-tab':
        history.replaceState(null, '', `#/tab/${encodeURIComponent(d.id)}`);
        break;
      case 'add-service':
        if (requireEdit()) openServiceEditor({ tabs: ui.tabs, tabId: dash.state.tab, onSaved: reload });
        break;
      case 'edit-service':
        if (requireEdit()) openServiceEditor({ serviceId: d.serviceId, tabs: ui.tabs, onSaved: reload });
        break;
      case 'delete-service':
        deleteService(d.serviceId);
        break;
      case 'reorder-services':
        enqueue(saveServiceOrder);
        break;
      case 'add-tab':
        openTabEditor({ tabs: ui.tabs, onSaved: (id) => { if (id) dash.state.tab = id; return reload(); } });
        break;
      case 'edit-tab':
        openTabEditor({ tabId: dash.state.tab, tabs: ui.tabs, onSaved: reload });
        break;
      case 'logout':
        logout();
        break;
      case 'import':
        if (requireEdit()) importBackup();
        break;
      default:
    }
  }

  async function deleteService(id) {
    const ok = await confirmDialog({
      title: 'Delete service',
      body: `${serviceName(id)} is removed from every tab. This can’t be undone.`,
      confirmLabel: 'Delete service',
    });
    if (!ok) return;
    enqueue(async () => {
      await api.services.remove(id);
      await reload();
    });
  }

  /** The grid's DOM order is the new order of the active tab (its first, unnamed group in v1). */
  async function saveServiceOrder() {
    const id = dash.state.tab;
    const order = [...root.querySelectorAll('.service-grid .service-card[data-service-id]')].map((c) => c.dataset.serviceId);
    const byId = new Map(ui.services.map((s) => [s.id, s]));
    ui.servicesByTab[id] = order.map((sid) => byId.get(sid)).filter(Boolean);
    const { data: tab, etag } = await api.tabs.get(id);
    const groups = tab.groups.length ? tab.groups : [{ id: null, name: null }];
    const body = groups.map((g, i) => ({ id: g.id, name: g.name, serviceIds: i === 0 ? order : [] }));
    await api.tabs.setGroups(id, body, etag);
  }

  async function saveTabOrder(bar) {
    const ids = [...bar.querySelectorAll('.tab[data-tab-id]')].map((t) => t.dataset.tabId);
    if (ids.join() === ui.tabs.map((t) => t.id).join()) return;
    const focusId = document.activeElement?.dataset?.tabId;
    await api.tabs.reorder(ids);
    await reload();
    if (focusId) root.querySelector(`.top-bar [data-tab-id="${CSS.escape(focusId)}"]`)?.focus();
  }

  async function logout() {
    try {
      await api.auth.logout();
    } catch (err) {
      pageError(err);
      return;
    }
    await refreshSession();
    restartStatus();
    rerender();
  }

  async function importBackup() {
    const file = await pickFile('.zip,application/zip');
    if (!file) return;
    const ok = await confirmDialog({
      title: 'Import configuration',
      body: `All current services, tabs and settings are replaced by the contents of ${file.name}.`,
      confirmLabel: 'Import',
    });
    if (!ok) return;
    enqueue(async () => {
      await api.backup.importZip(file);
      await reload();
    });
  }

  wireTabReorder(root, saveTabOrder, enqueue);

  /** Redraws for a status change and puts keyboard focus back where it was inside a card. */
  function redraw() {
    const active = document.activeElement;
    const card = active && active.closest && active.closest('.service-card');
    dash.render();
    if (!card) return;
    const again = root.querySelector(`.service-card[data-service-id="${CSS.escape(card.dataset.serviceId)}"]`);
    const sel = active.dataset.linkId ? `[data-link-id="${CSS.escape(active.dataset.linkId)}"]`
      : active.dataset.action ? `[data-action="${CSS.escape(active.dataset.action)}"]` : '.service-card__primary';
    again?.querySelector(sel)?.focus();
  }

  // A snapshot replaces everything, so the view is drawn again (it also carries the toolbar's
  // "n degraded · n down" counts). Single updates patch the card in place and only redraw when the
  // service's overall status changes, because the counts depend on it.
  const unsubscribe = onStatus((state, change) => {
    if (!change) {
      applySnapshot(ui.services, state);
      redraw();
      return;
    }
    const svc = ui.services.find((s) => s.id === change.serviceId);
    const link = svc && svc.links.find((l) => l.id === change.linkId);
    if (!link) return;
    const before = Heim.computeStatus(svc.links).status;
    link.probe = probeToUi(state.links[link.id], link.probeEnabled);
    if (Heim.computeStatus(svc.links).status !== before && !root.querySelector('.service-card--dragging')) redraw();
    else Heim.applyProbe(root, probeEvent(svc.id, link));
  });

  return () => {
    unsubscribe();
    document.querySelectorAll('body > .dialog-root').forEach((d) => d.remove());
  };
}

/**
 * Tabs reorder in edit mode by dragging, or with Alt+Left/Right on a focused tab (plain arrows move
 * between tabs). heim.js only provides this for cards.
 */
function wireTabReorder(root, save, enqueue) {
  let dragged = null;
  const editingTab = (node) => node && node.closest && node.closest('.tab-bar--editing .tab');

  root.addEventListener('pointerdown', (e) => {
    const tab = editingTab(e.target);
    if (tab) tab.draggable = true;
  });
  root.addEventListener('dragstart', (e) => {
    const tab = editingTab(e.target);
    if (!tab) return;
    dragged = tab;
    e.dataTransfer.effectAllowed = 'move';
    try { e.dataTransfer.setData('text/plain', tab.dataset.tabId); } catch { /* not needed */ }
  });
  root.addEventListener('dragover', (e) => {
    if (!dragged) return;
    const over = editingTab(e.target);
    if (!over || over === dragged || over.parentElement !== dragged.parentElement) return;
    e.preventDefault();
    const r = over.getBoundingClientRect();
    over.parentElement.insertBefore(dragged, e.clientX - r.left > r.width / 2 ? over.nextElementSibling : over);
  });
  root.addEventListener('dragend', () => {
    if (!dragged) return;
    const bar = dragged.parentElement;
    dragged.removeAttribute('draggable');
    dragged = null;
    enqueue(() => save(bar));
  });
  root.addEventListener('keydown', (e) => {
    const tab = editingTab(e.target);
    if (!tab || !e.altKey || (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight')) return;
    e.preventDefault();
    e.stopPropagation(); // keep heim.js from treating it as tab navigation
    const sibling = e.key === 'ArrowLeft' ? tab.previousElementSibling : tab.nextElementSibling;
    if (!sibling || !sibling.matches('.tab')) return;
    tab.parentElement.insertBefore(tab, e.key === 'ArrowLeft' ? sibling : sibling.nextElementSibling);
    tab.focus();
    enqueue(() => save(tab.parentElement));
  }, true);
}

function loadFailed(root, err) {
  const main = el('main', 'page');
  main.id = 'main';
  root.appendChild(main);
  showError(main, err);
}
