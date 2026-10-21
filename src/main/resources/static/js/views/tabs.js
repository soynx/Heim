// Tab management: create, rename, delete, default, showAll, tab order, service order per tab.
// Every change re-fetches, so each If-Match ETag is the one the user looked at: per tab for tab
// edits, the tab list ETag (tab order) for create and reorder.

import { api } from '../api.js';
import { h, field, checkbox, button, replace } from '../dom.js';
import { attempt, showError } from '../ui.js';

export async function tabsView(root) {
  const content = h('div');
  const openOrders = new Set(); // keeps "Services" sections open across reloads
  root.append(h('h1', {}, 'Tabs'), content);

  async function load() {
    let tabs, orderEtag, services;
    try {
      ({ data: tabs, etag: orderEtag } = await api.tabs.list());
      // Each tab's own GET carries the ETag its edits need; render from the same response.
      tabs = await Promise.all(tabs.map(async (t) => {
        const res = await api.tabs.get(t.id);
        return { ...res.data, etag: res.etag };
      }));
      ({ data: services } = await api.services.list());
    } catch (err) {
      if (!err.handled) showError(err, 'Could not load tabs');
      return;
    }
    const names = new Map((services || []).map((s) => [s.id, s.name]));
    replace(content,
      h('ol', { class: 'tab-list' }, tabs.map((tab, i) => tabItem(tab, i, tabs, orderEtag, names))),
      createForm(orderEtag));
  }

  /** Runs a mutation, then reloads; errors are shown and also reload (fresh ETags). */
  async function mutate(action, context) {
    await attempt(action, context);
    await load();
  }

  function tabItem(tab, index, tabs, orderEtag, names) {
    const nameInput = h('input', { type: 'text', value: tab.name, maxlength: 100, required: true });
    const colorInput = h('input', { type: 'text', value: tab.color || '', placeholder: 'color (optional)', maxlength: 50 });
    // PUT replaces icon and color too, so always send the current values.
    const tabBody = (extra) => ({ name: tab.name, icon: tab.icon, color: tab.color, ...extra });
    const moveTab = (delta) => {
      const ids = tabs.map((t) => t.id);
      [ids[index], ids[index + delta]] = [ids[index + delta], ids[index]];
      return mutate(() => api.tabs.reorder(ids, orderEtag), 'Could not reorder tabs');
    };

    return h('li', { class: 'tab-item' },
      h('div', { class: 'row' },
        field('Name', nameInput),
        field('Color', colorInput),
        button('Save', () => mutate(() => api.tabs.update(tab.id,
          tabBody({ name: nameInput.value.trim(), color: colorInput.value.trim() || null }), tab.etag), 'Could not save tab')),
        tab.isDefault
          ? h('strong', { class: 'tag' }, 'Default')
          : button('Make default', () => mutate(
            () => api.tabs.update(tab.id, tabBody({ isDefault: true }), tab.etag), 'Could not set default')),
        checkbox('Show all services', tab.showAll, (v) => mutate(
          () => api.tabs.update(tab.id, tabBody({ showAll: v }), tab.etag), 'Could not change tab')),
        button('↑', () => moveTab(-1), { title: 'Move tab up', disabled: index === 0 }),
        button('↓', () => moveTab(1), { title: 'Move tab down', disabled: index === tabs.length - 1 }),
        button('Delete', () => {
          if (!window.confirm(`Delete tab “${tab.name}”? Services stay available on other tabs.`)) return null;
          return mutate(() => api.tabs.remove(tab.id, tab.etag), 'Could not delete tab');
        }, { class: 'danger', disabled: tabs.length <= 1 })),
      serviceOrder(tab, names));
  }

  /** Up/down ordering of the services each group references (v1: one group per tab). */
  function serviceOrder(tab, names) {
    const groups = tab.groups || [];
    const total = groups.reduce((n, g) => n + (g.serviceIds?.length || 0), 0);
    const summary = `Services (${total})` + (tab.showAll ? ' — plus all other services, alphabetically' : '');
    const moveService = (groupIndex, index, delta) => {
      const body = groups.map((g) => ({ id: g.id, name: g.name, serviceIds: [...(g.serviceIds || [])] }));
      const ids = body[groupIndex].serviceIds;
      [ids[index], ids[index + delta]] = [ids[index + delta], ids[index]];
      return mutate(() => api.tabs.setGroups(tab.id, body, tab.etag), 'Could not reorder services');
    };
    return h('details', {
      class: 'service-order',
      open: openOrders.has(tab.id),
      ontoggle: (e) => (e.target.open ? openOrders.add(tab.id) : openOrders.delete(tab.id)),
    },
      h('summary', {}, summary),
      groups.map((group, gi) => h('ol', {},
        group.name ? h('li', { class: 'group-name' }, group.name) : null,
        (group.serviceIds || []).map((sid, i, all) => h('li', { class: 'row' },
          h('span', { class: 'grow' }, names.get(sid) || sid),
          button('↑', () => moveService(gi, i, -1), { title: 'Move up', disabled: i === 0 }),
          button('↓', () => moveService(gi, i, 1), { title: 'Move down', disabled: i === all.length - 1 }))))),
      total === 0 ? h('p', { class: 'empty' }, 'No services assigned. Assign tabs in the service editor.') : null);
  }

  function createForm(orderEtag) {
    const name = h('input', { type: 'text', placeholder: 'New tab name', maxlength: 100, required: true });
    const showAll = h('input', { type: 'checkbox' });
    return h('form', {
      class: 'row create-tab',
      onsubmit: (e) => {
        e.preventDefault();
        if (!name.value.trim()) return;
        mutate(() => api.tabs.create({ name: name.value.trim(), showAll: showAll.checked }, orderEtag), 'Could not create tab');
      },
    },
    field('Name', name),
    h('label', { class: 'check' }, showAll, h('span', {}, 'Show all services')),
    h('button', { type: 'submit', class: 'primary' }, 'Create tab'));
  }

  await load();
  return null;
}
