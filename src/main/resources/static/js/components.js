// Small reusable render functions shared by several views.

import { h } from './dom.js';
import { api } from './api.js';

/** Service icon: cached image, or the first letter of the name. */
export function serviceIcon(service, className = 'icon') {
  const src = api.icons.url(service.icon?.cachedFile);
  const initial = (service.name || '?').trim().charAt(0).toUpperCase() || '?';
  const fallback = h('span', { class: `${className} icon-initial`, 'aria-hidden': 'true' }, initial);
  if (!src) return fallback;
  const img = h('img', { class: className, src, alt: '', loading: 'lazy' });
  img.addEventListener('error', () => img.replaceWith(fallback), { once: true });
  return img;
}

/** Status dot for a link; `data-link-status` lets views update it in place. */
export function linkDot(linkId) {
  return h('span', { class: 'dot', dataset: { linkStatus: linkId }, hidden: true });
}

/** Overall status badge for a service; `data-service-status` lets views update it in place. */
export function serviceBadge(serviceId) {
  return h('span', { class: 'badge', dataset: { serviceStatus: serviceId }, hidden: true });
}

/** Applies status state to all dots/badges below `root`. */
export function paintStatus(root, state) {
  root.querySelectorAll('[data-link-status]').forEach((el) => {
    const s = state.links[el.dataset.linkStatus];
    el.hidden = !s;
    if (!s) return;
    el.dataset.state = s.state;
    const parts = [s.state];
    if (s.statusCode != null) parts.push(`HTTP ${s.statusCode}`);
    if (s.latencyMs != null) parts.push(`${s.latencyMs} ms`);
    if (s.error) parts.push(s.error);
    if (s.checkedAt) parts.push(`checked ${new Date(s.checkedAt).toLocaleTimeString()}`);
    el.title = parts.join(' · ');
  });
  root.querySelectorAll('[data-service-status]').forEach((el) => {
    const s = state.services[el.dataset.serviceStatus];
    el.hidden = !s;
    if (!s) return;
    el.dataset.state = s;
    el.textContent = s;
  });
}

export const LINK_KINDS = ['public', 'domain', 'hostname', 'ip', 'vpn', 'other'];
