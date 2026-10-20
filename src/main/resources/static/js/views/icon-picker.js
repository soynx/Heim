// Icon source picker: favicon (of a chosen link) / library / URL / upload.
//
// createIconPicker(icon, getLinks) → { el, toRequest(), pendingUpload(), refreshLinks(), setPreview(service) }
// Upload is two-step: the service is saved first, then the file goes to POST /api/services/{id}/icon.

import { api } from '../api.js';
import { h, field, replace } from '../dom.js';
import { serviceIcon } from '../components.js';

const SOURCES = [
  ['favicon', 'Favicon'],
  ['library', 'Library'],
  ['url', 'URL'],
  ['upload', 'Upload'],
];

export function createIconPicker(initialIcon, getLinks) {
  const original = initialIcon || {};
  const icon = {
    source: original.source || 'favicon',
    value: original.value ?? null,
    faviconLinkId: original.faviconLinkId ?? null,
  };
  // Value typed per source, so switching back and forth does not lose input.
  const values = { library: icon.source === 'library' ? icon.value : '', url: icon.source === 'url' ? icon.value : '' };
  let file = null;

  const preview = h('div', { class: 'icon-preview' });
  const panel = h('div', { class: 'icon-panel' });
  const group = `icon-src-${Math.random().toString(36).slice(2)}`;

  const radios = h('div', { class: 'row' }, SOURCES.map(([value, label]) => h('label', { class: 'check' },
    h('input', {
      type: 'radio', name: group, value, checked: icon.source === value,
      onchange: () => { icon.source = value; drawPanel(); },
    }),
    h('span', {}, label))));

  function faviconPanel() {
    const select = h('select', { onchange: (e) => { icon.faviconLinkId = e.target.value || null; } },
      h('option', { value: '' }, 'Primary link'),
      getLinks().map((l) => h('option', { value: l.id, selected: l.id === icon.faviconLinkId },
        l.label || l.url || '(new link)')));
    return field('Fetch favicon from', select);
  }

  function libraryPanel() {
    const results = h('div', { class: 'icon-results' });
    const chosen = h('span', { class: 'icon-chosen' }, values.library ? `Selected: ${values.library}` : 'Nothing selected');
    let timer = null;
    const search = async (q) => {
      if (!q.trim()) return replace(results);
      replace(results, h('span', { class: 'muted' }, 'Searching…'));
      try {
        const { data } = await api.icons.library(q.trim());
        replace(results, (data || []).length ? data.map((item) => h('button', {
          type: 'button', class: 'icon-result' + (item.slug === values.library ? ' selected' : ''), title: item.name,
          onclick: (e) => {
            values.library = item.slug;
            chosen.textContent = `Selected: ${item.name} (${item.slug})`;
            results.querySelectorAll('.icon-result').forEach((b) => b.classList.remove('selected'));
            e.currentTarget.classList.add('selected');
          },
        }, item.previewUrl ? h('img', { src: item.previewUrl, alt: '', loading: 'lazy' }) : null,
        h('span', {}, item.name))) : h('span', { class: 'muted' }, 'No icons found.'));
      } catch (err) {
        replace(results, h('span', { class: 'error-text' },
          err.notFound ? 'Icon library is not available.' : err.message));
      }
    };
    const input = h('input', {
      type: 'search', placeholder: 'Search icons, e.g. proxmox', value: values.library || '',
      oninput: (e) => { clearTimeout(timer); timer = setTimeout(() => search(e.target.value), 300); },
    });
    if (values.library) search(values.library);
    return h('div', {}, field('Search', input), chosen, results);
  }

  function urlPanel() {
    return field('Image URL', h('input', {
      type: 'url', value: values.url || '', placeholder: 'https://example.com/logo.png',
      oninput: (e) => { values.url = e.target.value; },
    }));
  }

  function uploadPanel() {
    return field('Image file', h('input', {
      type: 'file', accept: '.png,.svg,.webp,.jpg,.jpeg,.ico,image/*',
      onchange: (e) => { file = e.target.files[0] || null; },
    }), 'PNG, SVG, WebP, JPG or ICO, max 2 MB. Uploaded when you save.');
  }

  function drawPanel() {
    const panels = { favicon: faviconPanel, library: libraryPanel, url: urlPanel, upload: uploadPanel };
    replace(panel, panels[icon.source]());
  }

  drawPanel();

  return {
    el: h('div', { class: 'icon-picker' }, preview, radios, panel),
    refreshLinks: () => { if (icon.source === 'favicon') drawPanel(); },
    setPreview: (service) => replace(preview, serviceIcon(service, 'icon icon-large')),
    /** File to upload after saving, if the upload source is chosen and a file was picked. */
    pendingUpload: () => (icon.source === 'upload' ? file : null),
    /** Body for ServiceRequest.icon. */
    toRequest: () => {
      const links = getLinks();
      const faviconLinkId = links.some((l) => l.id === icon.faviconLinkId) ? icon.faviconLinkId : null;
      switch (icon.source) {
        case 'library': return { source: 'library', value: values.library || null, faviconLinkId };
        case 'url': return { source: 'url', value: values.url?.trim() || null, faviconLinkId };
        // Keeping the previous value lets the server keep the uploaded file when nothing new is picked.
        case 'upload': return { source: 'upload', value: original.source === 'upload' ? original.value : null, faviconLinkId };
        default: return { source: 'favicon', value: null, faviconLinkId };
      }
    },
  };
}
