// Service editor drawer: Heim.ServiceEditor, filled from GET /api/services/{id} and saved as a
// ServiceRequest. Icon uploads go to POST /api/services/{id}/icon once the service exists.

import { api } from '../api.js';
import { getStatus } from '../status.js';
import { serviceToEditor, serviceRequest, newEditorLink, defaultsToUi, safeImageSrc } from '../adapter.js';
import { openDialog, showError, debounce, el } from '../ui.js';

const { Heim } = window;

const MAX_LINKS = 8;
const MAX_ICON_BYTES = 2 * 1024 * 1024;

/** Opens the drawer for a new service (no serviceId) or an existing one. onSaved runs after any change. */
export async function openServiceEditor({ serviceId, tabs, tabId, onSaved }) {
  let dto = null;
  let etag = null;
  let defaults;
  try {
    const [settings, service] = await Promise.all([
      api.settings.get(),
      serviceId ? api.services.get(serviceId) : null,
    ]);
    defaults = defaultsToUi(settings.data.probe);
    if (service) ({ data: dto, etag } = service);
  } catch (err) {
    if (!err.handled) showError(document.querySelector('#main'), err);
    return;
  }

  const isNew = !dto;
  const service = dto
    ? serviceToEditor(dto, getStatus())
    : { name: '', description: '', icon: null, tabs: tabId ? [tabId] : [], links: [newEditorLink(true)] };
  const dlg = Heim.ServiceEditor({
    service, tabs, isNew, defaults, iconSource: dto?.icon?.source || 'favicon',
  });
  if (isNew) dlg.querySelector('[data-action="confirm-delete"]').remove();

  const body = dlg.querySelector('.dialog__body');
  const [nameInput, descriptionInput] = dlg.querySelectorAll('.field-row .input');
  nameInput.required = true;
  nameInput.maxLength = 200;
  descriptionInput.maxLength = 1000;
  const list = dlg.querySelector('.link-list');
  const addLinkButton = dlg.querySelector('[data-action="add-link"]');
  const linkCount = addLinkButton.closest('.dialog__section').querySelector('.section-label > span:last-child');
  list.querySelectorAll('.link-row').forEach(syncHealthFields);

  const icon = iconPicker(dlg.querySelector('.icon-picker'), dto, list);
  nameInput.addEventListener('input', () => icon.renamed());

  function linksChanged() {
    const rows = list.querySelectorAll('.link-row');
    linkCount.textContent = `${rows.length} of ${MAX_LINKS}`;
    addLinkButton.disabled = rows.length >= MAX_LINKS;
    if (rows.length && !list.querySelector('.radio[name="primary-link"]:checked')) {
      rows[0].querySelector('.radio[name="primary-link"]').checked = true;
      rows[0].classList.add('link-row--primary');
    }
  }
  linksChanged();

  let busy = false;
  async function run(task) {
    if (busy) return;
    busy = true;
    showError(body, null);
    try {
      await task();
    } catch (err) {
      showError(body, err);
    } finally {
      busy = false;
    }
  }

  async function save() {
    const form = readForm();
    if (!form) return;
    const request = serviceRequest(form, dto);
    const res = dto ? await api.services.update(dto.id, request, etag) : await api.services.create(request);
    // From here on a retry (say, after a failed upload) updates the service instead of adding another.
    ({ data: dto, etag } = res);
    if (icon.pendingFile()) ({ data: dto } = await api.services.uploadIcon(dto.id, icon.pendingFile()));
    close();
    await onSaved();
    if (!dto.icon?.cachedFile) awaitIcon(dto.id, onSaved);
  }

  async function remove() {
    await api.services.remove(dto.id, etag);
    close();
    await onSaved();
  }

  async function refreshIcon() {
    const res = await api.services.refreshIcon(dto.id);
    icon.showSaved(res.data);
    // The cached icon is server-managed and not part of the service's ETag, so `etag` stays valid.
    await onSaved();
  }

  /** Form values in design shapes, or null after showing what is missing. */
  function readForm() {
    const fail = (message, focus) => {
      showError(body, new Error(message));
      if (focus) focus.focus();
      return null;
    };
    const name = nameInput.value.trim();
    if (!name) return fail('Give the service a name.', nameInput);
    const links = [];
    for (const row of list.querySelectorAll('.link-row')) {
      const link = readLink(row);
      if (!link.url && !link.label) continue;
      if (!link.url) return fail(`Add a URL for ${link.label}.`, row.querySelector('.link-row__url'));
      if (Number.isNaN(link.health.interval)) return fail('Interval is a whole number of seconds.', row.querySelector('[id$="-iv"]'));
      if (Number.isNaN(link.health.timeout)) return fail('Timeout is a whole number of milliseconds.', row.querySelector('[id$="-to"]'));
      links.push(link);
    }
    const iconRequest = icon.request(links);
    if (iconRequest.error) return fail(iconRequest.error);
    return {
      name,
      description: descriptionInput.value.trim(),
      tabIds: [...dlg.querySelectorAll('.choice-chip[aria-pressed="true"]')].map((c) => c.dataset.value),
      icon: iconRequest.icon,
      links,
    };
  }

  const { close } = openDialog(dlg, {
    onAction: (name, detail, event) => {
      switch (name) {
        case 'save-service': run(save); break;
        case 'delete-service': run(remove); break;
        case 'refresh-icon':
        case 'fetch-favicon':
          if (!isNew) run(refreshIcon);
          break;
        case 'add-link': addLink(); break;
        case 'remove-link': linksChanged(); break;
        default: icon.onAction(name, detail, event);
      }
    },
  });
  // Enter in a text field saves, like a form would.
  dlg.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target.matches('input.input:not([type="search"])')) { e.preventDefault(); run(save); }
  });
  dlg.addEventListener('change', (e) => {
    if (e.target.matches('[data-health-toggle]')) syncHealthFields(e.target.closest('.link-row'));
  });

  function addLink() {
    const rows = list.querySelectorAll('.link-row');
    if (rows.length >= MAX_LINKS) return;
    const row = Heim.LinkRow({ link: newEditorLink(rows.length === 0), index: rows.length, defaults });
    list.appendChild(row);
    syncHealthFields(row);
    linksChanged();
    row.querySelector('.link-row__label').focus();
  }
}

/** A changed icon is fetched in the background; show it once it has arrived (or give up quietly). */
async function awaitIcon(serviceId, onSaved) {
  for (let i = 0; i < 5; i += 1) {
    await new Promise((resolve) => { setTimeout(resolve, 1500); });
    try {
      const { data } = await api.services.get(serviceId);
      if (data.icon?.cachedFile) { await onSaved(); return; }
    } catch {
      return;
    }
  }
}

/** Reads one Heim.LinkRow. New rows carry heim's generated id, which the API accepts as a temporary id. */
function readLink(row) {
  const val = (sel) => row.querySelector(sel).value.trim();
  const int = (sel) => { const v = val(sel); return v === '' ? null : (/^\d+$/.test(v) ? Number(v) : Number.NaN); };
  const checks = row.querySelectorAll('.health-panel__body .checkbox');
  return {
    id: row.dataset.linkId,
    label: val('.link-row__label'),
    kind: val('.link-row__kind'),
    url: val('.link-row__url'),
    primary: row.querySelector('.radio[name="primary-link"]').checked,
    newTab: row.querySelector('.link-row__options .switch').checked,
    health: {
      enabled: row.querySelector('[data-health-toggle]').checked,
      probeUrl: val('[id$="-pu"]'),
      codes: val('[id$="-sc"]'),
      ignoreTls: checks[0].checked,
      followRedirects: checks[1].checked,
      interval: int('[id$="-iv"]'),
      timeout: int('[id$="-to"]'),
    },
  };
}

/** heim.js disables a turned-off check's fields only on change; do it for the initial state too. */
function syncHealthFields(row) {
  const on = row.querySelector('[data-health-toggle]').checked;
  row.querySelectorAll('[data-health-field]').forEach((f) => { f.disabled = !on; });
}

/**
 * The four icon sources on top of Heim.IconPicker. The library pane lists dashboard-icons search
 * results (GET /api/icons/library) in place of the built-in glyphs.
 */
function iconPicker(pick, dto, list) {
  const saved = dto?.icon || { source: 'favicon' };
  let source = saved.source || 'favicon';
  let librarySlug = saved.source === 'library' ? saved.value : null;
  let file = null;
  const name = () => pick.closest('.drawer').querySelector('.field-row .input').value || dto?.name || '';

  const setPreview = (iconUi) => {
    pick.querySelector('.icon-picker__preview .service-icon')
      .replaceWith(Heim.ServiceIcon({ icon: iconUi, name: name(), size: 'lg' }));
  };

  // Refresh: re-download the saved icon (existing services only).
  if (dto) {
    pick.querySelector('.icon-picker__preview').appendChild(
      Heim.Button({ label: 'Refresh', size: 'sm', variant: 'ghost', action: 'refresh-icon' }));
  }

  // Favicon: choose the link it is fetched from.
  const faviconPanel = pick.querySelector('[data-panel="favicon"]');
  const linkField = Heim.Field({ label: 'Fetch from', type: 'select', options: [] });
  const linkSelect = linkField.querySelector('select');
  faviconPanel.prepend(linkField);
  const faviconUrl = faviconPanel.querySelector('.favicon-row__url');
  let faviconLinkId = saved.faviconLinkId || null;
  const fetchButton = faviconPanel.querySelector('[data-action="fetch-favicon"]');
  fetchButton.disabled = !dto;
  faviconPanel.querySelector('.field__hint').textContent = 'Fetched from this link when you save. Falls back to initials if none is found.';
  function fillLinks() {
    const rows = [...list.querySelectorAll('.link-row')].map(readLink).filter((l) => l.url);
    const primary = rows.find((l) => l.primary) || rows[0];
    linkSelect.replaceChildren(...[{ id: '', label: 'Primary link', url: primary?.url || '' }, ...rows].map((l) => {
      const o = el('option', null, l.id ? (l.label || l.url) : l.label);
      o.value = l.id;
      o.selected = l.id === (faviconLinkId || '');
      return o;
    }));
    if (!rows.some((l) => l.id === faviconLinkId)) faviconLinkId = null;
    const chosen = rows.find((l) => l.id === faviconLinkId) || primary;
    faviconUrl.textContent = chosen ? chosen.url : 'Add a link first';
  }
  linkSelect.addEventListener('focus', fillLinks);
  linkSelect.addEventListener('change', () => { faviconLinkId = linkSelect.value || null; fillLinks(); });
  fillLinks();

  // Library: search dashboard-icons.
  const libraryPanel = pick.querySelector('[data-panel="library"]');
  const search = libraryPanel.querySelector('input[type="search"]');
  const grid = libraryPanel.querySelector('.icon-grid');
  const note = el('p', 'field__hint');
  libraryPanel.appendChild(note);
  grid.replaceChildren();
  search.placeholder = 'Search icons, e.g. jellyfin';
  let lastQuery = null;
  const runSearch = debounce(async () => {
    const q = search.value.trim();
    if (q === lastQuery) return;
    lastQuery = q;
    if (!q) { grid.replaceChildren(); note.textContent = ''; return; }
    note.textContent = 'Searching…';
    try {
      const { data } = await api.icons.library(q);
      if (search.value.trim() !== q) return;
      grid.replaceChildren(...(data || []).map(libraryItem));
      note.textContent = data && data.length ? '' : 'No icons found.';
    } catch (err) {
      note.textContent = err.message;
    }
  }, 250);
  search.addEventListener('input', runSearch);
  function libraryItem(item) {
    const b = el('button', 'icon-grid__item');
    b.type = 'button';
    b.dataset.slug = item.slug;
    b.title = item.name;
    b.setAttribute('aria-pressed', String(item.slug === librarySlug));
    const src = safeImageSrc(item.previewUrl);
    if (src) {
      const img = el('img');
      img.src = src;
      img.alt = '';
      img.loading = 'lazy';
      img.width = 20;
      img.height = 20;
      b.appendChild(img);
    }
    const label = el('span', null, item.name);
    label.style.cssText = 'max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap';
    b.appendChild(label);
    return b;
  }
  if (librarySlug) { search.value = librarySlug; runSearch(); }

  // URL.
  const urlInput = pick.querySelector('[data-panel="url"] input');
  if (saved.source === 'url') urlInput.value = saved.value || '';

  // Upload: kept until the service is saved.
  const uploadPanel = pick.querySelector('[data-panel="upload"]');
  const fileInput = uploadPanel.querySelector('input[type="file"]');
  const dropTitle = uploadPanel.querySelector('.drop-zone__title');
  fileInput.accept = '.png,.svg,.webp,.jpg,.jpeg,.gif,.ico,image/*';
  const zone = uploadPanel.querySelector('.drop-zone');
  zone.lastElementChild.previousElementSibling.textContent = 'PNG, SVG, ICO · up to 2 MB';
  zone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); }
  });
  function useFile(f) {
    if (!f) return;
    if (f.size > MAX_ICON_BYTES) { showError(pick.closest('.dialog__body'), new Error('Icon must be at most 2 MB.')); return; }
    file = f;
    dropTitle.textContent = f.name;
    setPreview({ src: URL.createObjectURL(f) });
  }
  fileInput.addEventListener('change', () => useFile(fileInput.files[0]));

  return {
    /** Initials follow the name while no image is shown. */
    renamed: () => {
      if (pick.querySelector('.icon-picker__preview .service-icon--initials')) setPreview(null);
    },
    pendingFile: () => (source === 'upload' ? file : null),
    showSaved: (serviceDto) => setPreview(serviceToEditor(serviceDto).icon),
    onAction(action, detail, event) {
      if (action === 'segment' && detail.group === 'Icon source') {
        source = detail.value;
        if (source === 'favicon') fillLinks();
        if (source === 'library') search.focus();
      } else if (action === 'pick-icon') {
        // heim.js previews a built-in glyph; show the chosen library logo instead.
        const item = event.target.closest('.icon-grid__item');
        librarySlug = item.dataset.slug;
        const img = item.querySelector('img');
        setPreview(img ? { src: img.src } : null);
      } else if (action === 'drop-file') {
        useFile(detail.files && detail.files[0]);
      }
    },
    /** IconRef for the request, or { error }. */
    request(links) {
      const linkIds = links.map((l) => l.id);
      const fav = linkIds.includes(faviconLinkId) ? faviconLinkId : null;
      switch (source) {
        case 'library':
          return librarySlug ? { icon: { source, value: librarySlug, faviconLinkId: fav } } : { error: 'Pick an icon from the library.' };
        case 'url': {
          const value = urlInput.value.trim();
          return value ? { icon: { source, value, faviconLinkId: fav } } : { error: 'Add an image URL for the icon.' };
        }
        case 'upload':
          if (!file && saved.source !== 'upload') return { error: 'Choose an image to upload.' };
          return { icon: { source, value: saved.source === 'upload' ? saved.value : null, faviconLinkId: fav } };
        default:
          return { icon: { source: 'favicon', value: null, faviconLinkId: fav } };
      }
    },
  };
}
