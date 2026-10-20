// Settings: general (title, theme, background, custom CSS, probe defaults), auth, backup.

import { api } from '../api.js';
import { h, field, checkbox, button, intOrNull } from '../dom.js';
import { session, sessionChanged } from '../session.js';
import { applySettings } from '../chrome.js';
import { showError, showInfo, attempt } from '../ui.js';
import { render } from '../router.js';

export async function settingsView(root) {
  let settings, etag;
  try {
    ({ data: settings, etag } = await api.settings.get());
  } catch (err) {
    if (!err.handled) showError(err, 'Could not load settings');
    return null;
  }
  root.append(
    h('h1', {}, 'Settings'),
    generalForm(settings, etag),
    authForm(settings.auth),
    backupSection());
  return null;
}

function generalForm(settings, initialEtag) {
  let etag = initialEtag;
  const title = h('input', { type: 'text', value: settings.title || '', maxlength: 200, required: true });
  const theme = h('select', {}, ['auto', 'light', 'dark'].map((t) =>
    h('option', { value: t, selected: (settings.theme || 'auto') === t }, t)));

  const bg = settings.background || { source: 'none' };
  let bgSource = bg.source || 'none';
  const bgUrl = h('input', { type: 'url', value: bg.source === 'url' ? bg.value || '' : '', placeholder: 'https://…' });
  const bgFile = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/gif,image/webp' });
  const bgPanels = {
    url: field('Image URL', bgUrl),
    upload: field('Image file', bgFile,
      (bg.source === 'upload' ? `Current: ${bg.value || bg.cachedFile || 'uploaded image'}. ` : '') + 'Max 10 MB.'),
  };
  const showPanels = () => Object.entries(bgPanels).forEach(([k, el]) => { el.hidden = k !== bgSource; });
  const bgRadios = h('div', { class: 'row' }, [['none', 'None'], ['url', 'URL'], ['upload', 'Upload']].map(([v, label]) =>
    h('label', { class: 'check' },
      h('input', { type: 'radio', name: 'bg-source', value: v, checked: bgSource === v,
        onchange: () => { bgSource = v; showPanels(); } }),
      h('span', {}, label))));
  showPanels();

  const css = h('textarea', { rows: 10, class: 'code', spellcheck: 'false' }, settings.customCss || '');
  const interval = h('input', { type: 'number', min: 5, max: 86400, required: true, value: settings.probe?.intervalSeconds ?? 60 });
  const timeout = h('input', { type: 'number', min: 1, max: 300, required: true, value: settings.probe?.timeoutSeconds ?? 5 });

  async function save(e) {
    e.preventDefault();
    if (!form.reportValidity()) return;
    const file = bgSource === 'upload' ? bgFile.files[0] : null;
    if (bgSource === 'upload' && !file && bg.source !== 'upload') {
      showError(new Error('Choose an image file to upload.'));
      return;
    }
    const background = bgSource === 'url' ? { source: 'url', value: bgUrl.value.trim() }
      : bgSource === 'none' ? { source: 'none' }
        : file ? undefined : { source: 'upload' }; // new uploads go through their own endpoint
    try {
      let res = await api.settings.update({
        title: title.value.trim(),
        theme: theme.value,
        background,
        customCss: css.value,
        probe: { intervalSeconds: intOrNull(interval.value), timeoutSeconds: intOrNull(timeout.value) },
      }, etag);
      etag = res.etag;
      if (file) {
        res = await api.settings.uploadBackground(file, etag);
        etag = res.etag;
      }
    } catch (err) {
      if (!err.handled) showError(err, 'Could not save settings');
      return;
    }
    await refreshChrome();
    showInfo('Settings saved.');
  }

  const form = h('form', { class: 'editor', onsubmit: save, novalidate: true },
    h('h2', {}, 'General'),
    field('Title', title),
    field('Theme', theme),
    h('fieldset', {}, h('legend', {}, 'Background'), bgRadios, bgPanels.url, bgPanels.upload),
    field('Custom CSS', css, 'Served as /custom.css and loaded after the default stylesheet.'),
    h('fieldset', {}, h('legend', {}, 'Health check defaults'),
      h('div', { class: 'row' }, field('Interval (s)', interval), field('Timeout (s)', timeout))),
    h('div', { class: 'actions' }, h('button', { type: 'submit', class: 'primary' }, 'Save settings')));
  return form;
}

/** Re-applies title/background/custom CSS from the dashboard view (it carries the resolved background URL). */
async function refreshChrome() {
  const res = await attempt(() => api.dashboard());
  if (res) applySettings(res.data.settings, res.data.revision);
}

function authForm(auth = {}) {
  const me = session.me;
  if (!me.authAvailable) {
    return h('section', { class: 'editor' }, h('h2', {}, 'Login'),
      h('p', { class: 'muted' }, 'Authentication is not available in this build.'));
  }
  const username = h('input', { type: 'text', value: auth.username || '', autocomplete: 'username' });
  const current = h('input', { type: 'password', autocomplete: 'current-password' });
  const next = h('input', { type: 'password', autocomplete: 'new-password' });
  const toggles = { requireLoginToView: !!auth.requireLoginToView, requireLoginToEdit: !!auth.requireLoginToEdit };

  async function save(e) {
    e.preventDefault();
    const body = { ...toggles };
    if (username.value.trim()) body.username = username.value.trim();
    if (next.value) body.newPassword = next.value;
    if (current.value) body.currentPassword = current.value;
    const res = await attempt(() => api.settings.updateAuth(body), 'Could not save login settings');
    if (!res) return;
    current.value = '';
    next.value = '';
    await sessionChanged();
    showInfo('Login settings saved.');
    render();
  }

  return h('form', { class: 'editor', onsubmit: save },
    h('h2', {}, 'Login'),
    h('p', { class: 'muted' }, auth.passwordSet ? 'A password is set.' : 'No password set yet.'),
    field('Username', username),
    auth.passwordSet ? field('Current password', current, 'Required to change anything here.') : null,
    field(auth.passwordSet ? 'New password' : 'Password', next, 'Leave empty to keep the current password.'),
    checkbox('Require login to view the dashboard', toggles.requireLoginToView, (v) => { toggles.requireLoginToView = v; }),
    checkbox('Require login to edit', toggles.requireLoginToEdit, (v) => { toggles.requireLoginToEdit = v; }),
    h('div', { class: 'actions' }, h('button', { type: 'submit', class: 'primary' }, 'Save login settings')));
}

function backupSection() {
  const file = h('input', { type: 'file', accept: '.zip,application/zip' });
  const importZip = async () => {
    const zip = file.files[0];
    if (!zip) return showError(new Error('Choose a backup .zip first.'));
    if (!window.confirm('Importing replaces all services, tabs, settings, icons and uploads. Continue?')) return null;
    const res = await attempt(() => api.backup.importZip(zip), 'Import failed');
    if (!res) return null;
    showInfo(`Backup imported (revision ${res.data?.revision ?? '?'}).`);
    file.value = '';
    await refreshChrome();
    return null;
  };
  return h('section', { class: 'editor' },
    h('h2', {}, 'Backup'),
    h('p', {}, h('a', { class: 'button', href: api.backup.exportUrl, download: '' }, 'Download backup (.zip)')),
    h('p', { class: 'muted' }, 'The export contains no login credentials; importing keeps the current login settings.'),
    h('div', { class: 'row' }, field('Import backup', file), button('Import', importZip)));
}
