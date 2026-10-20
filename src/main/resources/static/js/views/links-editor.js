// Links editor: an ordered list of link rows bound to plain objects.
//
// createLinksEditor(links, primaryLinkId, onChange) → { el, getLinks(), getPrimaryLinkId() }
// Links keep their server id; new rows get a temporary "new-N" id, which the API accepts as a
// reference in primaryLinkId / icon.faviconLinkId of the same request.

import { h, field, checkbox, button, intOrNull, replace } from '../dom.js';
import { LINK_KINDS } from '../components.js';

let tempCounter = 0;

function newLink() {
  return {
    id: `new-${++tempCounter}`,
    label: '', kind: 'public', url: '', openInNewTab: true,
    probe: { enabled: false, url: null, method: 'GET', expectedStatus: '200-399', ignoreTls: false,
      followRedirects: false, intervalSeconds: null, timeoutSeconds: null },
  };
}

function cloneLink(link) {
  const base = newLink();
  return { ...base, ...link, probe: { ...base.probe, ...(link.probe || {}) } };
}

export function createLinksEditor(initialLinks, initialPrimary, onChange = () => {}) {
  const links = (initialLinks || []).map(cloneLink);
  let primaryLinkId = initialPrimary || links[0]?.id || null;
  const list = h('div', { class: 'links-list' });
  const radioGroup = `primary-${Math.random().toString(36).slice(2)}`;

  function changed() { onChange(); }

  function move(index, delta) {
    const target = index + delta;
    if (target < 0 || target >= links.length) return;
    [links[index], links[target]] = [links[target], links[index]];
    draw();
    changed();
  }

  function remove(index) {
    const [removed] = links.splice(index, 1);
    if (removed.id === primaryLinkId) primaryLinkId = links[0]?.id || null;
    draw();
    changed();
  }

  function row(link, index) {
    const text = (key, props = {}) => h('input', {
      type: 'text', value: link[key] ?? '', ...props,
      oninput: (e) => { link[key] = e.target.value; changed(); },
    });
    const kind = h('select', { onchange: (e) => { link.kind = e.target.value; } },
      LINK_KINDS.map((k) => h('option', { value: k, selected: link.kind === k }, k)));

    return h('fieldset', { class: 'link-row' },
      h('legend', {}, `Link ${index + 1}`),
      h('div', { class: 'row' },
        field('Label', text('label', { placeholder: 'e.g. LAN' })),
        field('Kind', kind),
        field('URL', text('url', { type: 'url', placeholder: 'https://…', required: true }))),
      h('div', { class: 'row' },
        checkbox('Open in new tab', link.openInNewTab, (v) => { link.openInNewTab = v; }),
        h('label', { class: 'check' },
          h('input', {
            type: 'radio', name: radioGroup, checked: link.id === primaryLinkId,
            onchange: () => { primaryLinkId = link.id; changed(); },
          }),
          h('span', {}, 'Primary link')),
        button('↑', () => move(index, -1), { title: 'Move up', disabled: index === 0 }),
        button('↓', () => move(index, 1), { title: 'Move down', disabled: index === links.length - 1 }),
        button('Remove', () => remove(index), { class: 'danger' })),
      probeOptions(link.probe));
  }

  function draw() {
    replace(list, links.length ? links.map(row) : h('p', { class: 'empty' }, 'No links yet.'));
  }

  draw();
  const el = h('div', { class: 'links-editor' },
    list,
    button('+ Add link', () => {
      const link = newLink();
      links.push(link);
      if (!primaryLinkId) primaryLinkId = link.id;
      draw();
      changed();
    }));

  return {
    el,
    getLinks: () => links,
    getPrimaryLinkId: () => primaryLinkId,
    /** Body for ServiceRequest.links. */
    toRequest: () => links.map((l) => ({
      id: l.id, label: l.label?.trim() || null, kind: l.kind, url: l.url.trim(), openInNewTab: l.openInNewTab,
      probe: { ...l.probe, url: l.probe.url?.trim() || null, expectedStatus: l.probe.expectedStatus?.trim() || null },
    })),
  };
}

function probeOptions(probe) {
  const num = (key, placeholder) => h('input', {
    type: 'number', min: 1, value: probe[key] ?? '', placeholder,
    oninput: (e) => { probe[key] = intOrNull(e.target.value); },
  });
  return h('details', { class: 'probe', open: probe.enabled },
    h('summary', {}, 'Health check'),
    h('div', { class: 'row' },
      checkbox('Enabled', probe.enabled, (v) => { probe.enabled = v; }),
      checkbox('Ignore TLS errors', probe.ignoreTls, (v) => { probe.ignoreTls = v; }),
      checkbox('Follow redirects', probe.followRedirects, (v) => { probe.followRedirects = v; })),
    h('div', { class: 'row' },
      field('Probe URL', h('input', {
        type: 'url', value: probe.url ?? '', placeholder: 'defaults to the link URL',
        oninput: (e) => { probe.url = e.target.value; },
      })),
      field('Method', h('select', { onchange: (e) => { probe.method = e.target.value; } },
        ['GET', 'HEAD'].map((m) => h('option', { value: m, selected: probe.method === m }, m)))),
      field('Expected status', h('input', {
        type: 'text', value: probe.expectedStatus ?? '', placeholder: '200-399',
        oninput: (e) => { probe.expectedStatus = e.target.value; },
      }))),
    h('div', { class: 'row' },
      field('Interval (s)', num('intervalSeconds', 'default')),
      field('Timeout (s)', num('timeoutSeconds', 'default'))));
}
