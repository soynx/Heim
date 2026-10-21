// The one place where backend JSON and the design's shapes (heim.d.ts) meet. Views only ever hand
// design-shaped objects to Heim factories and backend-shaped bodies to the API.

const DEFAULT_CODES = '200-399';

const KIND_TO_UI = { public: 'Public', domain: 'Domain', hostname: 'Hostname', ip: 'IP', vpn: 'VPN', other: 'Other' };
const KIND_FROM_UI = Object.fromEntries(Object.entries(KIND_TO_UI).map(([k, v]) => [v, k]));

export const kindToUi = (kind) => KIND_TO_UI[kind] || 'Other';
export const kindFromUi = (kind) => KIND_FROM_UI[kind] || 'other';

/** Backend theme 'auto' | 'light' | 'dark' ↔ Heim.theme 'system' | 'light' | 'dark'. */
export const themeToUi = (theme) => (theme === 'light' || theme === 'dark' ? theme : 'system');
export const themeFromUi = (theme) => (theme === 'light' || theme === 'dark' ? theme : 'auto');

const BLOCKED_SCHEMES = ['javascript', 'data', 'vbscript', 'file'];

/** URL safe for an href: script-capable schemes become '#', everything else (smb:, ssh:, …) passes. */
export function safeHref(url) {
  const scheme = /^\s*([a-z][a-z0-9+.-]*):/i.exec(url || '');
  if (!url || (scheme && BLOCKED_SCHEMES.includes(scheme[1].toLowerCase()))) return '#';
  return url;
}

/** URL safe for an image src: same-origin paths or http(s) only. */
export function safeImageSrc(url) {
  if (!url) return null;
  if (url.startsWith('/') && !url.startsWith('//')) return url;
  return /^https?:\/\//i.test(url) ? url : null;
}

/** CSS `url("…")` value, or null when the URL is not usable. */
export function cssUrl(url) {
  const safe = safeImageSrc(url);
  return safe ? `url("${safe.replace(/["\\\n\r]/g, (c) => `\\${c.charCodeAt(0).toString(16)} `)}")` : null;
}

/** A colour that can only ever be a colour inside an inline style; anything else is dropped. */
export function safeColor(color) {
  return /^(#[0-9a-f]{3,8}|[a-z]{3,20}|(rgb|hsl)a?\([\d.,%\s]+\))$/i.test(color || '') ? color : null;
}

/** Service icon: the server's cached file, or null for initials. */
export function iconToUi(icon) {
  return icon && icon.cachedFile ? { src: `/icons/${encodeURIComponent(icon.cachedFile)}` } : null;
}

/** LinkStatus (or its absence) → design Probe. */
export function probeToUi(linkStatus, probeEnabled) {
  if (!probeEnabled) return { status: 'off' };
  if (!linkStatus || linkStatus.state === 'UNKNOWN') return { status: 'unknown' };
  return {
    status: linkStatus.state === 'UP' ? 'up' : 'down',
    http: linkStatus.statusCode ?? undefined,
    latency: linkStatus.latencyMs ?? undefined,
    error: linkStatus.error || undefined,
  };
}

/** Payload for Heim.applyProbe. */
export function probeEvent(serviceId, link) {
  return { serviceId, linkId: link.id, kind: link.kind, ...link.probe };
}

function dashboardLink(link, primaryLinkId, statusLinks) {
  const kind = kindToUi(link.kind);
  return {
    id: link.id,
    label: link.label || kind,
    kind,
    url: safeHref(link.url),
    primary: link.id === primaryLinkId,
    newTab: link.openInNewTab !== false,
    probeEnabled: link.probeEnabled,
    probe: probeToUi(statusLinks[link.id], link.probeEnabled),
  };
}

function dashboardService(svc, statusLinks) {
  const links = svc.links || [];
  const primaryLinkId = links.some((l) => l.id === svc.primaryLinkId) ? svc.primaryLinkId : links[0]?.id;
  return {
    id: svc.id,
    name: svc.name,
    description: svc.description || '',
    icon: iconToUi(svc.icon),
    tabs: svc.tabIds || [],
    links: links.map((l) => dashboardLink(l, primaryLinkId, statusLinks)),
  };
}

/**
 * GET /api/dashboard → { title, theme, background, tabs, services, servicesByTab }.
 * Each service object is shared between the tabs it appears on, so a status update reaches all of them.
 */
export function dashboardToUi(view, status) {
  const statusLinks = status?.links || {};
  const byId = new Map();
  const servicesByTab = {};
  const tabs = (view.tabs || []).map((tab) => {
    servicesByTab[tab.id] = (tab.services || []).map((svc) => {
      if (!byId.has(svc.id)) byId.set(svc.id, dashboardService(svc, statusLinks));
      return byId.get(svc.id);
    });
    return { id: tab.id, name: tab.name, isDefault: tab.isDefault, showAll: tab.showAll, color: safeColor(tab.color) };
  });
  const s = view.settings || {};
  return {
    title: s.title || 'Heim',
    theme: themeToUi(s.theme),
    background: cssUrl(s.background?.url),
    tabs,
    services: [...byId.values()],
    servicesByTab,
  };
}

/** Writes a status snapshot into the services' links (a snapshot replaces every earlier result). */
export function applySnapshot(services, status) {
  const statusLinks = status?.links || {};
  services.forEach((svc) => svc.links.forEach((link) => {
    link.probe = probeToUi(statusLinks[link.id], link.probeEnabled);
  }));
}

/* ---------- service editor ---------- */

export function healthToUi(probe) {
  const p = probe || {};
  return {
    enabled: !!p.enabled,
    probeUrl: p.url || null,
    codes: p.expectedStatus && p.expectedStatus !== DEFAULT_CODES ? p.expectedStatus : null,
    ignoreTls: !!p.ignoreTls,
    followRedirects: !!p.followRedirects,
    interval: p.intervalSeconds ?? null,
    timeout: p.timeoutSeconds != null ? p.timeoutSeconds * 1000 : null,
  };
}

/** Design health (timeout in ms) → backend probe (seconds); keeps fields the editor does not show. */
export function healthFromUi(health, original) {
  return {
    ...(original || {}),
    enabled: !!health.enabled,
    url: health.probeUrl || null,
    expectedStatus: health.codes || DEFAULT_CODES,
    ignoreTls: !!health.ignoreTls,
    followRedirects: !!health.followRedirects,
    intervalSeconds: health.interval ?? null,
    timeoutSeconds: health.timeout != null ? Math.max(1, Math.round(health.timeout / 1000)) : null,
  };
}

/** ServiceDto → design Service for Heim.ServiceEditor. */
export function serviceToEditor(dto, status) {
  const statusLinks = status?.links || {};
  const links = dto.links || [];
  const primaryLinkId = links.some((l) => l.id === dto.primaryLinkId) ? dto.primaryLinkId : links[0]?.id;
  return {
    id: dto.id,
    name: dto.name,
    description: dto.description || '',
    icon: iconToUi(dto.icon),
    tabs: dto.tabIds || [],
    links: links.map((l) => ({
      id: l.id,
      label: l.label || '',
      kind: kindToUi(l.kind),
      url: l.url,
      primary: l.id === primaryLinkId,
      newTab: l.openInNewTab !== false,
      health: healthToUi(l.probe),
      probe: probeToUi(statusLinks[l.id], l.probe?.enabled),
    })),
  };
}

/** A new link row: health check on, everything else inherited. */
export function newEditorLink(primary) {
  return {
    label: '', kind: 'Public', url: '', primary, newTab: true,
    health: { enabled: true, followRedirects: false },
    probe: { status: 'unknown' },
  };
}

/** Design probe defaults from SettingsDto.probe. */
export function defaultsToUi(probe) {
  return { interval: probe?.intervalSeconds ?? 60, timeout: (probe?.timeoutSeconds ?? 5) * 1000, codes: DEFAULT_CODES };
}

/**
 * Editor form values → ServiceRequest. `form` = { name, description, tabIds, icon, links: [{ id, label,
 * kind, url, primary, newTab, health }] }; `original` is the ServiceDto being edited (null when new).
 */
export function serviceRequest(form, original) {
  const before = new Map((original?.links || []).map((l) => [l.id, l]));
  const links = form.links.map((l) => ({
    id: l.id,
    label: l.label || null,
    kind: kindFromUi(l.kind),
    url: l.url,
    openInNewTab: l.newTab,
    probe: healthFromUi(l.health, before.get(l.id)?.probe),
  }));
  const primary = form.links.find((l) => l.primary) || form.links[0];
  return {
    name: form.name,
    description: form.description || null,
    color: original?.color ?? null,
    icon: form.icon,
    primaryLinkId: primary ? primary.id : null,
    links,
    tabIds: form.tabIds,
  };
}

/* ---------- settings ---------- */

/** SettingsDto.background → the image URL in use, like DashboardView.background.url. */
export function backgroundUrl(ref) {
  if (!ref || ref.source === 'none') return null;
  if (ref.cachedFile) return `/uploads/${encodeURIComponent(ref.cachedFile)}`;
  return ref.source === 'url' ? ref.value : null;
}

/** Health-check defaults form (interval s, timeout ms) → SettingsRequest.probe (seconds). */
export function probeDefaultsRequest(interval, timeoutMs) {
  return { intervalSeconds: interval, timeoutSeconds: Math.max(1, Math.round(timeoutMs / 1000)) };
}

/* ---------- tabs ---------- */

/** Tab editor values → TabRequest; icon is not edited in the UI and is kept as it was. */
export function tabRequest(form, original) {
  return {
    name: form.name,
    icon: original?.icon ?? null,
    color: form.color || null,
    isDefault: form.isDefault,
    showAll: form.showAll,
  };
}
