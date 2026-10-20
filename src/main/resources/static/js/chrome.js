// Page-wide appearance driven by settings: title, theme, background image, custom stylesheet.

import { cssUrl } from './dom.js';

let pageTitle = 'Heim';

export function getPageTitle() {
  return pageTitle;
}

/** `settings` is DashboardView.settings: { title, theme, background: {source, url}, customCss }. */
export function applySettings(settings, revision) {
  if (!settings) return;
  pageTitle = settings.title || 'Heim';
  document.title = pageTitle;
  document.documentElement.dataset.theme = settings.theme || 'auto';
  setBackground(settings.background?.url);
  setCustomCss(settings.customCss, revision);
  document.dispatchEvent(new CustomEvent('heim:title', { detail: pageTitle }));
}

export function setBackground(url) {
  const body = document.body;
  if (url) {
    body.style.setProperty('background-image', cssUrl(url));
    body.classList.add('has-background');
  } else {
    body.style.removeProperty('background-image');
    body.classList.remove('has-background');
  }
}

/** Points the custom stylesheet link at /custom.css, cache-busted by config revision. */
export function setCustomCss(enabled, revision) {
  const link = document.getElementById('custom-css');
  if (!link) return;
  if (enabled === false) {
    link.removeAttribute('href');
  } else {
    link.setAttribute('href', '/custom.css' + (revision != null ? `?v=${encodeURIComponent(revision)}` : ''));
  }
}
