// Dashboard: tab bar + one card per service of the active tab.

import { api } from '../api.js';
import { h, safeUrl } from '../dom.js';
import { session } from '../session.js';
import { applySettings } from '../chrome.js';
import { onStatus } from '../status.js';
import { showError } from '../ui.js';
import { serviceIcon, linkDot, serviceBadge, paintStatus } from '../components.js';

export async function dashboardView(root, [tabId]) {
  let dashboard;
  try {
    ({ data: dashboard } = await api.dashboard());
  } catch (err) {
    if (!err.handled) showError(err, 'Could not load the dashboard');
    return null;
  }
  applySettings(dashboard.settings, dashboard.revision);

  const tabs = dashboard.tabs || [];
  const active = tabs.find((t) => t.id === tabId) || tabs.find((t) => t.isDefault) || tabs[0];
  session.currentTabId = active?.id || null;

  root.append(
    h('h1', { class: 'page-title' }, dashboard.settings?.title || 'Heim'),
    tabBar(tabs, active),
    active ? serviceGrid(active) : h('p', { class: 'empty' }, 'No tabs yet.'));

  return onStatus((state) => paintStatus(root, state));
}

function tabBar(tabs, active) {
  if (tabs.length <= 1 && !session.canEdit) return null;
  return h('nav', { class: 'tabbar', 'aria-label': 'Tabs' },
    tabs.map((tab) => h('a', {
      class: 'tab' + (tab === active ? ' active' : ''),
      href: `#/tab/${encodeURIComponent(tab.id)}`,
      'aria-current': tab === active ? 'page' : null,
      style: tab.color ? { '--tab-color': tab.color } : null,
    }, tab.name)),
    session.canEdit ? h('a', { class: 'tab tab-manage', href: '#/tabs' }, 'Manage tabs') : null);
}

function serviceGrid(tab) {
  const services = tab.services || [];
  return h('section', { class: 'grid' },
    services.map(serviceCard),
    session.canEdit ? h('a', { class: 'card card-add', href: '#/edit/service/new' }, '+ Add service') : null,
    services.length === 0 && !session.canEdit ? h('p', { class: 'empty' }, 'No services on this tab.') : null);
}

function serviceCard(service) {
  const links = service.links || [];
  const primary = links.find((l) => l.id === service.primaryLinkId) || links[0];
  const others = links.filter((l) => l !== primary);
  const href = primary ? safeUrl(primary.url) : null;

  const main = h(href ? 'a' : 'div', {
    class: 'card-main',
    href,
    target: href && primary.openInNewTab ? '_blank' : null,
    rel: href && primary.openInNewTab ? 'noopener noreferrer' : null,
    title: primary ? primary.url : null,
  },
  serviceIcon(service),
  h('div', { class: 'card-text' },
    h('div', { class: 'card-name' }, service.name, primary?.probeEnabled ? linkDot(primary.id) : null),
    service.description ? h('div', { class: 'card-desc' }, service.description) : null));

  return h('article', {
    class: 'card',
    dataset: { serviceId: service.id },
    style: service.color ? { '--service-color': service.color } : null,
  },
  main,
  serviceBadge(service.id),
  others.length ? h('div', { class: 'chips' }, others.map(linkChip)) : null,
  session.canEdit
    ? h('a', { class: 'card-edit', href: `#/edit/service/${encodeURIComponent(service.id)}`, title: 'Edit service' }, 'Edit')
    : null);
}

function linkChip(link) {
  const href = safeUrl(link.url);
  return h(href ? 'a' : 'span', {
    class: 'chip',
    href,
    target: href && link.openInNewTab ? '_blank' : null,
    rel: href && link.openInNewTab ? 'noopener noreferrer' : null,
    title: link.url,
  },
  link.probeEnabled ? linkDot(link.id) : null,
  h('span', { class: 'chip-label' }, link.label || link.url),
  link.kind ? h('span', { class: 'chip-kind' }, link.kind) : null);
}
