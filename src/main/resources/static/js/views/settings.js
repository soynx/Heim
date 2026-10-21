// Settings (screens/settings.html): general, appearance, health-check defaults, login, backup.
// The skeleton below is static markup from the mockup; every saved value is set through DOM properties.

import { api } from '../api.js';
import { session, refreshSession } from '../session.js';
import { navigate, render as rerender } from '../router.js';
import { restartStatus } from '../status.js';
import { themeToUi, themeFromUi, cssUrl, backgroundUrl, defaultsToUi, probeDefaultsRequest } from '../adapter.js';
import { applyBackground, confirmDialog, reloadCustomCss, showError, el } from '../ui.js';

const { Heim } = window;

/** Shown once in a section's footer after the view re-renders, e.g. "Saved." after a login change. */
let flash = null;

const SKELETON = `
<div class="settings-layout">
  <div class="settings-header">
    <a class="icon-button" href="#/" aria-label="Back to dashboard" data-icon="chevron-left"></a>
    <h1 class="settings-header__title">Settings</h1>
  </div>
  <nav class="settings-nav" aria-label="Settings sections">
    <a class="settings-nav__item" href="#general" aria-current="true" data-icon="settings">General</a>
    <a class="settings-nav__item" href="#appearance" data-icon="image">Appearance</a>
    <a class="settings-nav__item" href="#health" data-icon="activity">Health checks</a>
    <a class="settings-nav__item" href="#security" data-icon="lock">Security</a>
    <a class="settings-nav__item" href="#backup" data-icon="archive">Backup</a>
  </nav>
  <div class="settings-main">
    <section class="settings-section" id="general" aria-labelledby="general-h">
      <h2 class="settings-section__title" id="general-h">General</h2>
      <div class="settings-group">
        <div class="settings-group__row">
          <div class="field">
            <label class="field__label" for="s-title">Dashboard title</label>
            <input class="input" id="s-title" maxlength="200" style="max-width:360px" aria-describedby="s-title-hint">
            <p class="field__hint" id="s-title-hint">Shown in the top bar and the browser tab.</p>
          </div>
        </div>
        <div class="settings-group__footer"><button type="button" class="button button--primary button--sm" data-action="save-general">Save</button></div>
      </div>
    </section>

    <section class="settings-section" id="appearance" aria-labelledby="appearance-h">
      <h2 class="settings-section__title" id="appearance-h">Appearance</h2>
      <div class="settings-group">
        <div class="settings-group__row settings-group__row--inline">
          <span class="field__text"><span class="field__label">Theme</span><span class="field__hint">System follows your device’s light or dark setting.</span></span>
          <span data-slot="theme"></span>
        </div>
        <div class="settings-group__row">
          <span class="field__text"><span class="field__label">Background image</span><span class="field__hint">Cards and the top bar turn slightly translucent so they stay readable over any image.</span></span>
          <span data-slot="bg-source"></span>
          <div style="display:flex;gap:16px;align-items:flex-start" data-bg-panels>
            <div class="field" style="flex:1" data-bg-panel="url">
              <label class="field__label" for="s-bg-url">Image URL</label>
              <input class="input input--mono" id="s-bg-url" type="url" maxlength="2048" placeholder="https://images.example.com/photo.jpg">
              <p class="field__hint">JPG, PNG or WebP. Large images are scaled to cover the window.</p>
            </div>
            <label class="drop-zone" tabindex="0" style="flex:1" data-bg-panel="upload"><span data-icon="upload"></span><span><span class="drop-zone__title">Drop an image</span> or <span class="drop-zone__link">browse</span></span><span>JPG, PNG, WebP or GIF · up to 10 MB</span><input type="file" accept="image/png,image/jpeg,image/webp,image/gif" class="sr-only"></label>
            <div class="bg-preview" role="img" aria-label="Preview of the dashboard over the background image"><span class="bg-preview__bar"></span><span class="bg-preview__card"></span><span class="bg-preview__card"></span></div>
          </div>
        </div>
        <div class="settings-group__row">
          <div class="field">
            <label class="field__label" for="s-css">Custom CSS</label>
            <textarea class="textarea textarea--code" id="s-css" spellcheck="false" aria-describedby="s-css-hint" placeholder="/* Loaded after Heim’s styles. Override tokens or target the stable classes. */"></textarea>
            <p class="field__hint" id="s-css-hint">Override any token on <code>:root</code> or target classes like <code>.service-card</code>, <code>.link-chip</code>, <code>.status-dot--down</code>.</p>
          </div>
        </div>
        <div class="settings-group__footer"><button type="button" class="button button--sm" data-action="reset-appearance">Reset</button><button type="button" class="button button--primary button--sm" data-action="save-appearance">Save</button></div>
      </div>
    </section>

    <section class="settings-section" id="health" aria-labelledby="health-h">
      <h2 class="settings-section__title" id="health-h">Health checks</h2>
      <p class="settings-section__desc">Defaults for every link with a health check. A link can override them in its own settings.</p>
      <div class="settings-group">
        <div class="settings-group__row">
          <div class="field-row" style="max-width:480px">
            <div class="field"><label class="field__label" for="s-iv">Default interval</label><div class="input-group"><input class="input" id="s-iv" inputmode="numeric"><span class="input-group__suffix">s</span></div></div>
            <div class="field"><label class="field__label" for="s-to">Default timeout</label><div class="input-group"><input class="input" id="s-to" inputmode="numeric"><span class="input-group__suffix">ms</span></div></div>
          </div>
        </div>
        <div class="settings-group__footer"><button type="button" class="button button--primary button--sm" data-action="save-health">Save</button></div>
      </div>
    </section>

    <section class="settings-section" id="security" aria-labelledby="security-h">
      <h2 class="settings-section__title" id="security-h">Security</h2>
      <div class="settings-group">
        <div class="settings-group__row">
          <div class="field-row" style="max-width:480px">
            <div class="field"><label class="field__label" for="s-user">Username</label><input class="input" id="s-user" maxlength="64" autocomplete="username"></div>
            <div class="field" data-password-set><span class="field__label">Password</span>
              <div style="display:flex;gap:8px;align-items:center"><span class="input" style="display:flex;align-items:center;color:var(--ink-muted);letter-spacing:.15em" aria-label="Password is set">••••••••</span><button type="button" class="button" data-action="change-password">Change</button></div>
            </div>
          </div>
          <div class="field-row" style="max-width:480px" data-new-password>
            <div class="field"><label class="field__label" for="s-pw-new">New password</label><input class="input" id="s-pw-new" type="password" autocomplete="new-password" maxlength="72" aria-describedby="s-pw-new-hint"><p class="field__hint" id="s-pw-new-hint">At least 8 characters.</p></div>
            <div class="field"><label class="field__label" for="s-pw-confirm">Confirm password</label><input class="input" id="s-pw-confirm" type="password" autocomplete="new-password" maxlength="72"></div>
          </div>
          <div class="field-row" style="max-width:480px" data-current-password>
            <div class="field"><label class="field__label" for="s-pw-current">Current password</label><input class="input" id="s-pw-current" type="password" autocomplete="current-password" aria-describedby="s-pw-current-hint"><p class="field__hint" id="s-pw-current-hint">Needed to change the username or password.</p></div>
          </div>
        </div>
        <div class="settings-group__row settings-group__row--inline">
          <span class="field__text"><label class="field__label" for="s-req-view">Require login to view dashboard</label><span class="field__hint">Visitors see the login page until they sign in.</span></span>
          <input type="checkbox" role="switch" class="switch" id="s-req-view">
        </div>
        <div class="settings-group__row settings-group__row--inline">
          <span class="field__text"><label class="field__label" for="s-req-edit">Require login to edit</label><span class="field__hint">Anyone can open links; only signed-in users can change services, tabs and settings.</span></span>
          <input type="checkbox" role="switch" class="switch" id="s-req-edit">
        </div>
        <div class="settings-group__row" data-login-note>
          <div class="callout" role="note"><span data-icon="info"></span><div>Login can only be turned on once a username and password are set. Until then both switches stay disabled, so you can’t lock yourself out.</div></div>
        </div>
        <div class="settings-group__footer"><button type="button" class="button button--sm" data-action="reset-security">Cancel</button><button type="button" class="button button--primary button--sm" data-action="save-security">Save</button></div>
      </div>
    </section>

    <section class="settings-section" id="backup" aria-labelledby="backup-h">
      <h2 class="settings-section__title" id="backup-h">Backup</h2>
      <div class="settings-group">
        <div class="settings-group__row settings-group__row--inline">
          <span class="field__text"><span class="field__label">Export configuration</span><span class="field__hint">Services, tabs, settings and uploaded icons as a .zip. Passwords are not included.</span></span>
          <a class="button" href="/api/backup/export" download data-icon="download">Export</a>
        </div>
        <div class="settings-group__row">
          <span class="field__text"><span class="field__label">Import configuration</span></span>
          <div class="callout callout--warning" role="note"><span data-icon="alert"></span><div><strong class="callout__title">Importing replaces everything</strong>All current services, tabs and settings are overwritten by the file’s contents. Export first if you want to keep them.</div></div>
          <label class="drop-zone" tabindex="0" data-import><span data-icon="upload"></span><span><span class="drop-zone__title">Drop a Heim .zip</span> or <span class="drop-zone__link">browse</span></span><span>heim-backup-*.zip</span><input type="file" accept=".zip,application/zip" class="sr-only"></label>
        </div>
      </div>
    </section>
  </div>
</div>`;

export async function settingsView(root) {
  let settings;
  let etag;
  try {
    ({ data: settings, etag } = await api.settings.get());
  } catch (err) {
    if (!err.handled) {
      const main = el('main', 'page');
      root.appendChild(main);
      showError(main, err);
    }
    return null;
  }
  document.title = `Settings · ${settings.title}`;
  const me = session.me;

  root.appendChild(Heim.TopBar({
    title: settings.title, user: me.authenticated ? me.username : null, canEdit: false,
    settingsHref: '#/settings', loginHref: me.passwordSet ? '#/login' : false,
  }));
  root.appendChild(Heim.el(SKELETON));
  const $ = (sel) => root.querySelector(sel);
  applyBackground(root, cssUrl(backgroundUrl(settings.background)));

  /* ---------- footer messages ---------- */
  function note(section, text) {
    const footer = $(`#${section} .settings-group__footer`);
    footer.querySelector('.settings-group__footer-note')?.remove();
    if (text) footer.prepend(el('span', 'settings-group__footer-note', text));
  }
  function fail(section, err) {
    note(section, null);
    showError($(`#${section} .settings-group`), err);
  }
  async function saving(section, task) {
    showError($(`#${section} .settings-group`), null);
    note(section, null);
    try {
      await task();
    } catch (err) {
      fail(section, err);
    }
  }

  /* ---------- general ---------- */
  const title = $('#s-title');
  title.value = settings.title;
  async function saveGeneral() {
    const value = title.value.trim();
    if (!value) throw new Error('Give the dashboard a title.');
    ({ data: settings, etag } = await api.settings.update({ title: value }, etag));
    root.querySelector('.top-bar__title').textContent = settings.title;
    document.title = `Settings · ${settings.title}`;
    note('general', 'Saved.');
  }

  /* ---------- appearance ---------- */
  const themeSlot = $('[data-slot="theme"]');
  const bgSlot = $('[data-slot="bg-source"]');
  const bgUrl = $('#s-bg-url');
  const bgZone = $('[data-bg-panel="upload"]');
  const bgFile = bgZone.querySelector('input[type="file"]');
  const preview = $('.bg-preview');
  const css = $('#s-css');
  let bgPending = null;
  let theme;
  let bgSource;

  function resetAppearance() {
    theme = themeToUi(settings.theme);
    bgSource = settings.background?.source || 'none';
    bgPending = null;
    const themeControl = Heim.SegmentedControl({ label: 'Theme', value: theme, options: [
      { value: 'system', label: 'System', icon: 'monitor' }, { value: 'light', label: 'Light', icon: 'sun' }, { value: 'dark', label: 'Dark', icon: 'moon' }] });
    const bgControl = Heim.SegmentedControl({ label: 'Background image source', value: bgSource, options: [
      { value: 'none', label: 'None' }, { value: 'upload', label: 'Upload' }, { value: 'url', label: 'URL' }] });
    bgControl.style.alignSelf = 'flex-start';
    themeSlot.replaceChildren(themeControl);
    bgSlot.replaceChildren(bgControl);
    bgUrl.value = settings.background?.source === 'url' ? settings.background.value || '' : '';
    bgZone.querySelector('.drop-zone__title').textContent = settings.background?.source === 'upload' ? 'Replace the image' : 'Drop an image';
    css.value = settings.customCss || '';
    showBackground();
  }
  function showBackground() {
    $('[data-bg-panels]').hidden = bgSource === 'none';
    $('[data-bg-panel="url"]').hidden = bgSource !== 'url';
    bgZone.hidden = bgSource !== 'upload';
    const current = bgSource === settings.background?.source ? backgroundUrl(settings.background) : null;
    const image = bgPending ? `url("${URL.createObjectURL(bgPending)}")` : cssUrl(bgSource === 'url' ? bgUrl.value.trim() : current);
    preview.style.backgroundImage = image || '';
  }
  bgUrl.addEventListener('change', showBackground);
  function useBackground(file) {
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) { fail('appearance', new Error('Background image must be at most 10 MB.')); return; }
    bgPending = file;
    bgZone.querySelector('.drop-zone__title').textContent = file.name;
    showBackground();
  }
  bgFile.addEventListener('change', () => useBackground(bgFile.files[0]));

  async function saveAppearance() {
    const body = { theme: themeFromUi(theme), customCss: css.value };
    if (bgSource === 'none') body.background = { source: 'none' };
    else if (bgSource === 'url') {
      const value = bgUrl.value.trim();
      if (!value) throw new Error('Add an image URL, or pick None.');
      body.background = { source: 'url', value };
    } else if (!bgPending) {
      if (settings.background?.source !== 'upload') throw new Error('Choose an image to upload, or pick None.');
      body.background = { source: 'upload' };
    }
    ({ data: settings, etag } = await api.settings.update(body, etag));
    if (bgPending) ({ data: settings, etag } = await api.settings.uploadBackground(bgPending, etag));
    Heim.theme.set(themeToUi(settings.theme));
    applyBackground(root, cssUrl(backgroundUrl(settings.background)));
    reloadCustomCss();
    resetAppearance();
    note('appearance', 'Saved.');
  }

  /* ---------- health checks ---------- */
  const interval = $('#s-iv');
  const timeout = $('#s-to');
  const defaults = defaultsToUi(settings.probe);
  interval.value = defaults.interval;
  timeout.value = defaults.timeout;
  async function saveHealth() {
    const iv = interval.value.trim();
    const to = timeout.value.trim();
    if (!/^\d+$/.test(iv) || +iv < 5 || +iv > 86400) throw new Error('Interval is a whole number of seconds from 5 to 86400.');
    if (!/^\d+$/.test(to) || +to < 1000 || +to > 300000) throw new Error('Timeout is a whole number of milliseconds from 1000 to 300000.');
    ({ data: settings, etag } = await api.settings.update({ probe: probeDefaultsRequest(+iv, +to) }, etag));
    timeout.value = defaultsToUi(settings.probe).timeout;
    note('health', 'Saved.');
  }

  /* ---------- security ---------- */
  const auth = settings.auth || {};
  const user = $('#s-user');
  const pwNew = $('#s-pw-new');
  const pwConfirm = $('#s-pw-confirm');
  const pwCurrent = $('#s-pw-current');
  const reqView = $('#s-req-view');
  const reqEdit = $('#s-req-edit');
  function resetSecurity() {
    user.value = auth.username || '';
    [pwNew, pwConfirm, pwCurrent].forEach((i) => { i.value = ''; });
    $('[data-password-set]').hidden = !auth.passwordSet;
    $('[data-new-password]').hidden = auth.passwordSet;
    $('[data-current-password]').hidden = true;
    reqView.checked = !!auth.requireLoginToView;
    reqEdit.checked = !!auth.requireLoginToEdit;
    reqView.disabled = !auth.passwordSet;
    reqEdit.disabled = !auth.passwordSet;
    $('[data-login-note]').hidden = auth.passwordSet;
    clearMismatch();
  }
  function clearMismatch() {
    pwConfirm.removeAttribute('aria-invalid');
    pwConfirm.parentElement.querySelector('.field__error')?.remove();
  }
  function needsCurrent() {
    if (auth.passwordSet && (user.value.trim() !== (auth.username || '') || pwNew.value)) {
      $('[data-current-password]').hidden = false;
    }
  }
  user.addEventListener('input', needsCurrent);
  pwNew.addEventListener('input', needsCurrent);
  pwConfirm.addEventListener('input', clearMismatch);

  async function saveSecurity() {
    const body = { requireLoginToView: reqView.checked, requireLoginToEdit: reqEdit.checked };
    const username = user.value.trim();
    if (username !== (auth.username || '')) body.username = username;
    if (pwNew.value || pwConfirm.value) {
      if (pwNew.value !== pwConfirm.value) {
        pwConfirm.setAttribute('aria-invalid', 'true');
        pwConfirm.parentElement.appendChild(el('p', 'field__error', 'Passwords don’t match.'));
        pwConfirm.focus();
        return;
      }
      if (pwNew.value.length < 8) throw new Error('Use at least 8 characters for the password.');
      if (!username) throw new Error('Set a username along with the password.');
      body.newPassword = pwNew.value;
      body.username = username;
    }
    if (pwCurrent.value) body.currentPassword = pwCurrent.value;
    await api.settings.updateAuth(body);
    await refreshSession();
    restartStatus();
    flash = { section: 'security', text: 'Saved.' };
    rerender();
  }

  /* ---------- backup ---------- */
  const importZone = $('[data-import]');
  const importFile = importZone.querySelector('input[type="file"]');
  async function importBackup(file) {
    if (!file) return;
    importFile.value = '';
    const ok = await confirmDialog({
      title: 'Import configuration',
      body: `All current services, tabs and settings are replaced by the contents of ${file.name}.`,
      confirmLabel: 'Import',
    });
    if (!ok) return;
    showError(importZone.parentElement, null);
    try {
      await api.backup.importZip(file);
    } catch (err) {
      showError(importZone.parentElement, err);
      return;
    }
    reloadCustomCss();
    flash = { section: 'backup', text: `Imported ${file.name}.` };
    rerender();
  }
  importFile.addEventListener('change', () => importBackup(importFile.files[0]));

  /* ---------- behaviour ---------- */
  [bgZone, importZone].forEach((zone) => zone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); zone.querySelector('input[type="file"]').click(); }
  }));
  const nav = $('.settings-nav');
  nav.addEventListener('click', (e) => {
    const a = e.target.closest('a');
    if (!a) return;
    e.preventDefault(); // the hash belongs to the router
    nav.querySelectorAll('a').forEach((x) => x.setAttribute('aria-current', x === a ? 'true' : 'false'));
    $(a.getAttribute('href')).scrollIntoView({ block: 'start' });
  });

  Heim.renderIcons(root);
  resetAppearance();
  resetSecurity();
  Heim.mount(root, {
    onAction: (name, detail, event) => {
      switch (name) {
        case 'save-general': saving('general', saveGeneral); break;
        case 'reset-appearance': resetAppearance(); note('appearance', null); showError($('#appearance .settings-group'), null); break;
        case 'save-appearance': saving('appearance', saveAppearance); break;
        case 'save-health': saving('health', saveHealth); break;
        case 'change-password':
          $('[data-new-password]').hidden = false;
          $('[data-current-password]').hidden = false;
          pwNew.focus();
          break;
        case 'reset-security': resetSecurity(); note('security', null); showError($('#security .settings-group'), null); break;
        case 'save-security': saving('security', saveSecurity); break;
        case 'segment':
          if (detail.group === 'Theme') theme = detail.value;
          else { bgSource = detail.value; showBackground(); }
          break;
        case 'drop-file': {
          const file = detail.files && detail.files[0];
          if (event.target.closest('[data-import]')) importBackup(file);
          else useBackground(file);
          break;
        }
        case 'logout': logout(); break;
        default:
      }
    },
  });

  if (flash) {
    note(flash.section, flash.text);
    $(`#${flash.section}`).scrollIntoView({ block: 'start' });
    flash = null;
  }
  return null;
}

async function logout() {
  try {
    await api.auth.logout();
  } catch {
    // The session is gone either way once the page reloads.
  }
  await refreshSession();
  restartStatus();
  navigate('#/');
}
