// Create / edit / delete a service. Route: #/edit/service/<id|new>.

import { api } from '../api.js';
import { h, field, button } from '../dom.js';
import { session } from '../session.js';
import { navigate } from '../router.js';
import { showError, showInfo, attempt } from '../ui.js';
import { createLinksEditor } from './links-editor.js';
import { createIconPicker } from './icon-picker.js';

export async function serviceEditorView(root, [id]) {
  const isNew = id === 'new';
  let service = null;
  let etag = null;
  let tabs = [];
  try {
    const tabsRes = await api.tabs.list();
    tabs = tabsRes.data || [];
    if (isNew) {
      service = {
        name: '', description: '', color: '', icon: { source: 'favicon' }, links: [],
        primaryLinkId: null, tabIds: session.currentTabId ? [session.currentTabId] : [],
      };
    } else {
      ({ data: service, etag } = await api.services.get(id));
    }
  } catch (err) {
    if (!err.handled) showError(err, 'Could not load the service');
    return null;
  }

  const backHash = session.currentTabId ? `#/tab/${encodeURIComponent(session.currentTabId)}` : '#/';
  const name = h('input', { type: 'text', value: service.name, required: true, maxlength: 200 });
  const description = h('textarea', { rows: 2, maxlength: 1000 }, service.description || '');
  const color = h('input', { type: 'text', value: service.color || '', placeholder: '#3b82f6', maxlength: 50 });
  const colorPicker = h('input', {
    type: 'color', value: /^#[0-9a-f]{6}$/i.test(service.color || '') ? service.color : '#888888',
    oninput: (e) => { color.value = e.target.value; },
  });

  let iconPicker = null;
  const linksEditor = createLinksEditor(service.links, service.primaryLinkId, () => iconPicker?.refreshLinks());
  iconPicker = createIconPicker(service.icon, linksEditor.getLinks);
  iconPicker.setPreview(service);

  const selectedTabs = new Set(service.tabIds || []);
  const tabPicker = h('div', { class: 'row' }, tabs.map((tab) => h('label', { class: 'check' },
    h('input', {
      type: 'checkbox', checked: selectedTabs.has(tab.id),
      onchange: (e) => (e.target.checked ? selectedTabs.add(tab.id) : selectedTabs.delete(tab.id)),
    }),
    h('span', {}, tab.name))));

  function requestBody() {
    return {
      name: name.value.trim(),
      description: description.value.trim() || null,
      color: color.value.trim() || null,
      icon: iconPicker.toRequest(),
      primaryLinkId: linksEditor.getPrimaryLinkId(),
      links: linksEditor.toRequest(),
      tabIds: [...selectedTabs],
    };
  }

  async function save(e) {
    e.preventDefault();
    if (!form.reportValidity()) return;
    let saved;
    try {
      const res = isNew
        ? await api.services.create(requestBody())
        : await api.services.update(id, requestBody(), etag);
      saved = res.data;
      etag = res.etag;
    } catch (err) {
      if (!err.handled) showError(err, 'Could not save');
      return;
    }
    const file = iconPicker.pendingUpload();
    if (file) {
      const uploaded = await attempt(() => api.services.uploadIcon(saved.id, file), 'Service saved, but the icon upload failed');
      if (!uploaded) return navigate(`#/edit/service/${encodeURIComponent(saved.id)}`);
    }
    showInfo(`Saved “${saved.name}”.`);
    navigate(backHash);
  }

  async function refreshIcon() {
    const res = await attempt(() => api.services.refreshIcon(id), 'Icon refresh failed');
    if (!res) return;
    iconPicker.setPreview(res.data); // the cached icon is not part of the ETag, so ours stays valid
    showInfo(res.data.icon?.cachedFile ? 'Icon refreshed.' : 'No icon could be fetched.');
  }

  async function remove() {
    if (!window.confirm(`Delete “${service.name}”? This cannot be undone.`)) return;
    const res = await attempt(() => api.services.remove(id, etag), 'Could not delete');
    if (res === undefined) return;
    showInfo('Service deleted.');
    navigate(backHash);
  }

  const form = h('form', { class: 'editor', onsubmit: save, novalidate: true },
    h('h1', {}, isNew ? 'New service' : `Edit ${service.name}`),
    h('section', {},
      field('Name', name),
      field('Description', description),
      field('Color', h('div', { class: 'row' }, color, colorPicker), 'Any CSS color; optional.')),
    h('section', {}, h('h2', {}, 'Links'), linksEditor.el),
    h('section', {}, h('h2', {}, 'Tabs'),
      tabs.length ? tabPicker : h('p', { class: 'empty' }, 'No tabs.')),
    h('section', {}, h('h2', {}, 'Icon'), iconPicker.el,
      isNew ? null : button('Refresh icon', refreshIcon)),
    h('div', { class: 'actions' },
      h('button', { type: 'submit', class: 'primary' }, 'Save'),
      h('a', { class: 'button', href: backHash }, 'Cancel'),
      isNew ? null : button('Delete service', remove, { class: 'danger' })));

  root.append(form);
  name.focus();
  return null;
}
