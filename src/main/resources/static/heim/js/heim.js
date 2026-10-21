/* @ds-bundle: {"format":4,"namespace":"Heim","components":[{"name":"StatusDot"},{"name":"LinkChip"},{"name":"ServiceIcon"},{"name":"ServiceCard"},{"name":"ServiceGrid"},{"name":"TabBar"},{"name":"TopBar"},{"name":"EmptyState"},{"name":"Button"},{"name":"Field"},{"name":"Switch"},{"name":"SegmentedControl"},{"name":"ChoiceChips"},{"name":"IconPicker"},{"name":"LinkRow"},{"name":"Dialog"},{"name":"Callout"}]} */
/* Heim UI — plain JavaScript, no framework, no build step. Every factory returns a DOM node built from
   ONE template string, so the server (Spring/Thymeleaf) can render the same markup and Heim.mount()
   wires behaviour onto either. Live status: Heim.applyProbe() / Heim.setStatus() swap classes only. */
(function () {
  'use strict';
  var H = {};
  var uid = 0;
  function nextId(p) { uid += 1; return (p || 'heim') + '-' + uid; }

  /* ---------- helpers ---------- */
  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function el(html) {
    var t = document.createElement('template');
    t.innerHTML = html.trim();
    return t.content.childElementCount === 1 ? t.content.firstElementChild : t.content;
  }
  H.esc = esc;
  H.el = el;

  /* ---------- icons (24px grid, 1.75 stroke, currentColor) ---------- */
  var P = {
    settings: '<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M4.6 4.6l1.4 1.4M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4"/>',
    moon: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>',
    monitor: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>',
    pencil: '<path d="M4 20h4L19 9l-4-4L4 16v4z"/><path d="m13.5 6.5 4 4"/>',
    trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>',
    grip: '<circle cx="9" cy="6" r="1.1" fill="currentColor" stroke="none"/><circle cx="15" cy="6" r="1.1" fill="currentColor" stroke="none"/><circle cx="9" cy="12" r="1.1" fill="currentColor" stroke="none"/><circle cx="15" cy="12" r="1.1" fill="currentColor" stroke="none"/><circle cx="9" cy="18" r="1.1" fill="currentColor" stroke="none"/><circle cx="15" cy="18" r="1.1" fill="currentColor" stroke="none"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    x: '<path d="M6 6l12 12M18 6 6 18"/>',
    check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
    'chevron-right': '<path d="m9 6 6 6-6 6"/>',
    'chevron-down': '<path d="m6 9 6 6 6-6"/>',
    'chevron-up': '<path d="m6 15 6-6 6 6"/>',
    'chevron-left': '<path d="m15 6-6 6 6 6"/>',
    external: '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',
    'log-in': '<path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 8l4 4-4 4M14 12H4"/>',
    'log-out': '<path d="M9 20H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h3M16 16l4-4-4-4M20 12H10"/>',
    search: '<circle cx="11" cy="11" r="6.5"/><path d="m20 20-4.2-4.2"/>',
    upload: '<path d="M12 15V4M7 9l5-5 5 5M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/>',
    download: '<path d="M12 4v11M7 10l5 5 5-5M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/>',
    image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="m21 16-5-5-9 9"/>',
    link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
    more: '<circle cx="5" cy="12" r="1.2" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.2" fill="currentColor" stroke="none"/>',
    activity: '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
    alert: '<path d="M12 4 2.5 20h19L12 4z"/><path d="M12 10v4M12 17h.01"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
    eye: '<path d="M2.5 12S6 5 12 5s9.5 7 9.5 7-3.5 7-9.5 7-9.5-7-9.5-7z"/><circle cx="12" cy="12" r="3"/>',
    lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    grid: '<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/>',
    star: '<path d="M12 4l2.4 5 5.6.8-4 3.9.9 5.5L12 16.6l-4.9 2.6.9-5.5-4-3.9 5.6-.8z"/>',
    house: '<path d="M4.5 10.5 12 4l7.5 6.5V20h-15z"/><path d="M10 20v-5h4v5"/>',
    archive: '<rect x="3" y="4" width="18" height="5" rx="1"/><path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9M10 13h4"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 20a8 8 0 0 1 16 0"/>',
    shieldcheck: '<path d="M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z"/><path d="m9 12 2 2 4-4"/>',
    /* service glyphs (library) */
    server: '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01"/>',
    play: '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M10 8.5v7l6-3.5z"/>',
    shield: '<path d="M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z"/>',
    home: '<path d="M4 11 12 4l8 7"/><path d="M6 9.5V20h12V9.5"/><circle cx="12" cy="14" r="2"/>',
    cloud: '<path d="M7 18h10a4 4 0 0 0 .6-8A6 6 0 0 0 6 9.6 4.2 4.2 0 0 0 7 18z"/>',
    chart: '<path d="M4 19h16"/><path d="m5 15 4.5-5 3.5 3 6-7"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="m11 12 9-9M16 7l3 3"/>',
    box: '<path d="M12 3 4 7v10l8 4 8-4V7z"/><path d="M4 7l8 4 8-4M12 11v10"/>',
    network: '<rect x="9" y="3" width="6" height="5" rx="1"/><rect x="3" y="16" width="6" height="5" rx="1"/><rect x="15" y="16" width="6" height="5" rx="1"/><path d="M12 8v4M6 16v-2h12v2"/>',
    film: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4"/>',
    database: '<ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
    terminal: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="m7 9 3 3-3 3M13 15h4"/>',
    music: '<path d="M9 18V5l11-2v13"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/>',
    camera: '<path d="M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"/><circle cx="12" cy="13.5" r="3.5"/>',
    mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m4 7 8 6 8-6"/>',
    book: '<path d="M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z"/><path d="M5 17a3 3 0 0 1 3-3h11"/>',
    printer: '<path d="M7 9V4h10v5"/><rect x="3" y="9" width="18" height="8" rx="2"/><path d="M7 14h10v6H7z"/>',
    wifi: '<path d="M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01"/>'
  };
  H.iconNames = Object.keys(P);
  /** Fill every [data-icon] in root: an empty <span data-icon> is replaced, any other element gets the icon prepended. */
  H.renderIcons = function (root) {
    (root || document).querySelectorAll('[data-icon]').forEach(function (n) {
      var svg = el(H.icon(n.dataset.icon, n.dataset.iconSize === 'sm' ? 'heim-icon--sm' : ''));
      if (n.tagName === 'SPAN' && !n.childNodes.length && !n.className) n.replaceWith(svg);
      else { n.insertBefore(svg, n.firstChild); n.removeAttribute('data-icon'); }
    });
  };
  H.libraryIcons = ['server', 'play', 'shield', 'home', 'cloud', 'chart', 'key', 'box', 'network', 'film', 'database', 'terminal', 'music', 'camera', 'mail', 'book', 'printer', 'wifi', 'activity', 'globe', 'lock', 'archive'];
  H.icon = function (name, cls) {
    return '<svg class="heim-icon ' + (cls || '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">' + (P[name] || P.globe) + '</svg>';
  };

  /* ---------- status ---------- */
  var STATUS = ['up', 'degraded', 'down', 'unknown', 'off'];
  var STATUS_TEXT = { up: 'Up', degraded: 'Degraded', down: 'Down', unknown: 'Checking', off: 'Health check off' };
  H.STATUS_TEXT = STATUS_TEXT;

  /** Overall service status from its links. off = no link is probed (render no indicator). */
  H.computeStatus = function (links) {
    var probed = (links || []).filter(function (l) { return l.probe && l.probe.status !== 'off'; });
    if (!probed.length) return { status: 'off', down: 0, total: 0 };
    var known = probed.filter(function (l) { return l.probe.status !== 'unknown'; });
    var down = known.filter(function (l) { return l.probe.status === 'down'; }).length;
    var status = !known.length ? 'unknown' : down === 0 ? 'up' : down === known.length ? 'down' : 'degraded';
    return { status: status, down: down, total: probed.length };
  };

  /** StatusDot — the only place status colours live. Toggle with Heim.setStatus(el, status). */
  H.StatusDot = function (props) {
    props = props || {};
    var s = props.status || 'unknown';
    var label = props.label || STATUS_TEXT[s];
    return el('<span class="status-dot status-dot--' + s + (props.size === 'sm' ? ' status-dot--sm' : '') + '" data-status="' + s + '"' +
      (props.decorative ? ' aria-hidden="true"' : ' role="img" aria-label="' + esc(label) + '" title="' + esc(label) + '"') + '></span>');
  };
  H.setStatus = function (dot, status, label) {
    if (!dot) return;
    STATUS.forEach(function (s) { dot.classList.remove('status-dot--' + s); });
    dot.classList.add('status-dot--' + status);
    dot.dataset.status = status;
    if (dot.getAttribute('role') === 'img') {
      var l = label || STATUS_TEXT[status];
      dot.setAttribute('aria-label', l); dot.title = l;
    }
  };

  /* ---------- tooltip text for a probe ---------- */
  function probeMeta(probe) {
    if (!probe || probe.status === 'off') return 'Health check off';
    if (probe.status === 'unknown') return 'Checking…';
    if (probe.error) return probe.error + (probe.latency != null ? ' · ' + probe.latency + ' ms' : '');
    return (probe.http != null ? probe.http : '—') + ' · ' + (probe.latency != null ? probe.latency + ' ms' : '—');
  }
  H.probeMeta = probeMeta;

  /** LinkChip — one link of a service. <a> so it works without JS; the tooltip is CSS (hover + focus). */
  H.LinkChip = function (props) {
    var link = props.link, probe = link.probe || { status: 'off' };
    var tid = nextId('tt');
    var dot = probe.status === 'off' ? '' : '<span class="status-dot status-dot--sm status-dot--' + probe.status + '" data-status="' + probe.status + '" aria-hidden="true"></span>';
    var newTab = link.newTab !== false;
    return el(
      '<a class="link-chip has-tooltip' + (link.primary ? ' link-chip--primary' : '') + '" href="' + esc(link.url) + '"' +
      (newTab ? ' target="_blank" rel="noopener noreferrer"' : '') +
      ' data-link-id="' + esc(link.id) + '" aria-describedby="' + tid + '">' +
        dot +
        '<span class="link-chip__label">' + esc(link.label) + '</span>' +
        (link.primary ? '<span class="sr-only">(primary)</span>' : '') +
        '<span class="tooltip' + (props.tooltipBelow ? ' tooltip--below' : '') + '" role="tooltip" id="' + tid + '">' +
          '<span class="tooltip__url">' + esc(link.url) + '</span>' +
          '<span class="tooltip__meta" data-probe-meta>' + esc((link.kind ? link.kind + ' · ' : '') + probeMeta(probe)) + '</span>' +
        '</span>' +
      '</a>');
  };

  /** ServiceIcon — image, library glyph, or initials tile. */
  H.initials = function (name) {
    var words = String(name || '?').replace(/[^A-Za-z0-9 ]/g, ' ').trim().split(/\s+/);
    var s = words.length > 1 ? words[0][0] + words[1][0] : words[0].slice(0, 2);
    return s.toUpperCase();
  };
  H.ServiceIcon = function (props) {
    var icon = props.icon || {}, size = props.size === 'lg' ? ' service-icon--lg' : '';
    if (icon.src) return el('<span class="service-icon' + size + '" aria-hidden="true"><img src="' + esc(icon.src) + '" alt=""></span>');
    if (icon.glyph) return el('<span class="service-icon' + size + '" aria-hidden="true">' + H.icon(icon.glyph) + '</span>');
    return el('<span class="service-icon service-icon--initials' + size + '" aria-hidden="true">' + esc(H.initials(props.name)) + '</span>');
  };

  /** ServiceCard — the widget. Clicking anywhere opens the primary link; chips open their own. */
  H.ServiceCard = function (props) {
    var svc = props.service, maxLinks = props.maxLinks || 3;
    var links = svc.links || [];
    var primary = links.filter(function (l) { return l.primary; })[0] || links[0] || { url: '#' };
    var st = H.computeStatus(links);
    var statusText = st.status === 'degraded' ? st.down + ' of ' + st.total + ' links down' : STATUS_TEXT[st.status];
    var card = el(
      '<li class="service-card" data-service-id="' + esc(svc.id) + '" data-status="' + st.status + '">' +
        '<div class="service-card__header">' +
          '<button type="button" class="icon-button icon-button--sm service-card__handle" data-action="reorder" aria-label="Reorder ' + esc(svc.name) + '. Use arrow keys to move.">' + H.icon('grip') + '</button>' +
          '<span data-slot="icon"></span>' +
          '<div class="service-card__body">' +
            '<div class="service-card__title-row">' +
              '<a class="service-card__primary service-card__name" href="' + esc(primary.url) + '"' + (primary.newTab !== false ? ' target="_blank" rel="noopener noreferrer"' : '') + '>' + esc(svc.name) + '</a>' +
            '</div>' +
            (svc.description ? '<p class="service-card__description" title="' + esc(svc.description) + '">' + esc(svc.description) + '</p>' : '') +
          '</div>' +
          (st.status === 'off' ? '' :
          '<span class="service-card__status">' +
            '<span class="status-label status-label--' + st.status + '" data-status-label' + (st.status === 'degraded' || st.status === 'down' ? '' : ' hidden') + '>' + STATUS_TEXT[st.status] + '</span>' +
            '<span class="status-dot status-dot--' + st.status + '" data-status="' + st.status + '" role="img" aria-label="' + esc(statusText) + '" title="' + esc(statusText) + '"></span>' +
          '</span>') +
          '<span class="service-card__actions">' +
            '<button type="button" class="icon-button icon-button--sm" data-action="edit-service" aria-label="Edit ' + esc(svc.name) + '">' + H.icon('pencil') + '</button>' +
            '<button type="button" class="icon-button icon-button--sm icon-button--danger" data-action="delete-service" aria-label="Delete ' + esc(svc.name) + '">' + H.icon('trash') + '</button>' +
          '</span>' +
        '</div>' +
        '<div class="service-card__links" role="group" aria-label="Links"></div>' +
      '</li>');
    card.querySelector('[data-slot="icon"]').replaceWith(H.ServiceIcon({ icon: svc.icon, name: svc.name }));
    var wrap = card.querySelector('.service-card__links');
    var visible = links.length > maxLinks ? links.slice(0, maxLinks - 1) : links;
    var extra = links.slice(visible.length);
    visible.forEach(function (l) { wrap.appendChild(H.LinkChip({ link: l })); });
    if (extra.length) wrap.appendChild(H.LinkOverflow({ links: extra, serviceName: svc.name }));
    return card;
  };

  /** "+N" overflow: a menu button listing the remaining links. */
  H.LinkOverflow = function (props) {
    var mid = nextId('menu');
    var node = el(
      '<span class="link-overflow">' +
        '<button type="button" class="link-chip link-overflow__button" aria-haspopup="menu" aria-expanded="false" aria-controls="' + mid + '" data-overflow-toggle aria-label="' + props.links.length + ' more links for ' + esc(props.serviceName) + '">+' + props.links.length + '</button>' +
        '<ul class="menu" role="menu" id="' + mid + '" hidden></ul>' +
      '</span>');
    var menu = node.querySelector('.menu');
    props.links.forEach(function (l) {
      var p = l.probe || { status: 'off' };
      menu.appendChild(el(
        '<li role="none"><a role="menuitem" class="menu__item" href="' + esc(l.url) + '"' + (l.newTab !== false ? ' target="_blank" rel="noopener noreferrer"' : '') + ' data-link-id="' + esc(l.id) + '">' +
          (p.status === 'off' ? '<span class="status-dot status-dot--sm" style="visibility:hidden" aria-hidden="true"></span>' : '<span class="status-dot status-dot--sm status-dot--' + p.status + '" data-status="' + p.status + '" aria-hidden="true"></span>') +
          '<span class="menu__item-text"><span>' + esc(l.label) + '</span><span class="menu__item-sub">' + esc(l.url) + '</span></span>' +
          '<span class="menu__item-meta" data-probe-meta>' + esc(p.status === 'off' ? 'off' : probeMeta(p)) + '</span>' +
        '</a></li>'));
    });
    return node;
  };

  H.AddServiceCard = function () {
    return el('<li><button type="button" class="service-card service-card--add" data-action="add-service" style="width:100%;height:100%">' + H.icon('plus') + 'Add service</button></li>');
  };

  /** ServiceGrid — list of cards; editing adds handles, actions and the add card. */
  H.ServiceGrid = function (props) {
    var grid = el('<ul class="service-grid' + (props.editing ? ' service-grid--editing' : '') + '" aria-label="Services"></ul>');
    (props.services || []).forEach(function (s) { grid.appendChild(H.ServiceCard({ service: s, maxLinks: props.maxLinks })); });
    if (props.editing) grid.appendChild(H.AddServiceCard());
    return grid;
  };

  /** Push one probe result into the live page: chip dot, tooltip, overflow row and the card's overall dot. */
  H.applyProbe = function (root, r) {
    root = root || document;
    var card = root.querySelector('.service-card[data-service-id="' + r.serviceId + '"]');
    if (!card) return;
    card.querySelectorAll('[data-link-id="' + r.linkId + '"]').forEach(function (a) {
      var dot = a.querySelector('.status-dot');
      if (dot) H.setStatus(dot, r.status);
      var meta = a.querySelector('[data-probe-meta]');
      if (meta) meta.textContent = (r.kind ? r.kind + ' · ' : '') + probeMeta(r);
    });
    // recompute overall from the chip/menu dots now in the card
    var states = Array.prototype.map.call(card.querySelectorAll('.service-card__links [data-link-id] .status-dot[data-status]'), function (d) { return { probe: { status: d.dataset.status } }; });
    var st = H.computeStatus(states);
    card.dataset.status = st.status;
    var main = card.querySelector('.service-card__status > .status-dot');
    if (main) H.setStatus(main, st.status, st.status === 'degraded' ? st.down + ' of ' + st.total + ' links down' : null);
    var lbl = card.querySelector('[data-status-label]');
    if (lbl) {
      lbl.className = 'status-label status-label--' + st.status;
      lbl.textContent = STATUS_TEXT[st.status];
      lbl.hidden = !(st.status === 'degraded' || st.status === 'down');
    }
  };

  /* ---------- tabs + top bar ---------- */
  H.TabBar = function (props) {
    var bar = el('<div class="tab-bar' + (props.editing ? ' tab-bar--editing' : '') + '" role="tablist" aria-label="Tabs"></div>');
    (props.tabs || []).forEach(function (t) {
      var active = t.id === props.active;
      bar.appendChild(el(
        '<button type="button" class="tab' + (active ? ' tab--active' : '') + '" role="tab" aria-selected="' + active + '" tabindex="' + (active ? 0 : -1) + '" data-tab-id="' + esc(t.id) + '"' + (t.color ? ' style="--tab-color:' + esc(t.color) + '"' : '') + '>' +
          '<span class="tab__inner">' +
            '<span class="tab__handle">' + H.icon('grip', 'heim-icon--sm') + '</span>' +
            (t.color ? '<span class="tab__color" aria-hidden="true"></span>' : '') +
            (t.glyph ? H.icon(t.glyph, 'heim-icon--sm') : '') +
            '<span class="tab__name">' + esc(t.name) + '</span>' +
            (t.isDefault && props.editing ? '<span class="tab__default" title="Default tab">' + H.icon('star', 'heim-icon--sm') + '<span class="sr-only">(default)</span></span>' : '') +
          '</span>' +
        '</button>'));
    });
    if (props.editing) {
      bar.appendChild(el('<button type="button" class="icon-button icon-button--sm tab-bar__add" data-action="edit-tab" aria-label="Edit current tab">' + H.icon('pencil', 'heim-icon--sm') + '</button>'));
      bar.appendChild(el('<button type="button" class="button button--ghost button--sm tab-bar__add" data-action="add-tab">' + H.icon('plus', 'heim-icon--sm') + 'Add tab</button>'));
    }
    return bar;
  };

  /** TopBar — returns a fragment: the bar plus the tab row used under 960px. */
  H.TopBar = function (props) {
    var frag = document.createDocumentFragment();
    var auth = props.user
      ? '<button type="button" class="icon-button" data-action="logout" aria-label="Log out ' + esc(props.user) + '" title="Log out (' + esc(props.user) + ')">' + H.icon('log-out') + '</button>'
      : props.loginHref === false ? '' : '<a class="button button--ghost button--sm" href="' + esc(props.loginHref || '/login') + '">' + H.icon('log-in', 'heim-icon--sm') + 'Log in</a>';
    var bar = el(
      '<header class="top-bar">' +
        '<a class="top-bar__brand" href="/" aria-label="' + esc(props.title || 'Heim') + ' home">' +
          '<span class="brand-mark">' + H.icon('house') + '</span>' +
          '<span class="top-bar__title">' + esc(props.title || 'Heim') + '</span>' +
        '</a>' +
        '<span class="top-bar__divider" aria-hidden="true"></span>' +
        '<nav class="top-bar__tabs" aria-label="Tabs"></nav>' +
        '<div class="top-bar__actions">' +
          (props.canEdit === false ? '' : '<button type="button" class="button button--sm edit-toggle" data-action="toggle-edit" aria-pressed="' + !!props.editing + '">' + H.icon(props.editing ? 'check' : 'pencil', 'heim-icon--sm') + '<span class="edit-toggle__label">' + (props.editing ? 'Done' : 'Edit') + '</span></button>') +
          '<a class="icon-button" href="' + esc(props.settingsHref || '/settings') + '" aria-label="Settings" title="Settings">' + H.icon('settings') + '</a>' +
          auth +
        '</div>' +
      '</header>');
    if (props.tabs) {
      bar.querySelector('.top-bar__tabs').appendChild(H.TabBar(props));
      frag.appendChild(bar);
      var row = el('<nav class="tab-row" aria-label="Tabs"></nav>');
      row.appendChild(H.TabBar(props));
      frag.appendChild(row);
    } else {
      bar.querySelector('.top-bar__tabs').remove();
      bar.querySelector('.top-bar__divider').remove();
      frag.appendChild(bar);
    }
    return frag;
  };

  /* ---------- empty state ---------- */
  H.EmptyState = function (props) {
    var actions = (props.actions || []).map(function (a) {
      return '<button type="button" class="button' + (a.primary ? ' button--primary' : '') + '" data-action="' + esc(a.action || '') + '">' + (a.icon ? H.icon(a.icon, 'heim-icon--sm') : '') + esc(a.label) + '</button>';
    }).join('');
    return el(
      '<div class="empty-state' + (props.firstRun ? ' empty-state--first-run' : '') + '">' +
        '<span class="empty-state__icon">' + H.icon(props.icon || 'grid', 'heim-icon--lg') + '</span>' +
        '<h2 class="empty-state__title">' + esc(props.title) + '</h2>' +
        (props.body ? '<p class="empty-state__body">' + esc(props.body) + '</p>' : '') +
        (actions ? '<div class="empty-state__actions">' + actions + '</div>' : '') +
      '</div>');
  };

  /* ---------- form primitives ---------- */
  H.Button = function (props) {
    var v = props.variant ? ' button--' + props.variant : '';
    return el('<button type="' + (props.type || 'button') + '" class="button' + v + (props.size === 'sm' ? ' button--sm' : '') + '"' + (props.disabled ? ' disabled' : '') + (props.action ? ' data-action="' + esc(props.action) + '"' : '') + '>' + (props.icon ? H.icon(props.icon, 'heim-icon--sm') : '') + esc(props.label) + '</button>');
  };
  H.Field = function (props) {
    var id = props.id || nextId('f'), hid = id + '-hint';
    var ctl;
    if (props.type === 'select') {
      ctl = '<select class="select" id="' + id + '"' + (props.hint ? ' aria-describedby="' + hid + '"' : '') + '>' + (props.options || []).map(function (o) { return '<option' + (o === props.value ? ' selected' : '') + '>' + esc(o) + '</option>'; }).join('') + '</select>';
    } else if (props.type === 'textarea') {
      ctl = '<textarea class="textarea' + (props.code ? ' textarea--code' : '') + '" id="' + id + '" spellcheck="false" placeholder="' + esc(props.placeholder || '') + '">' + esc(props.value || '') + '</textarea>';
    } else {
      ctl = '<input class="input' + (props.mono ? ' input--mono' : '') + '" id="' + id + '" type="' + (props.type || 'text') + '" value="' + esc(props.value || '') + '" placeholder="' + esc(props.placeholder || '') + '"' + (props.hint ? ' aria-describedby="' + hid + '"' : '') + (props.error ? ' aria-invalid="true"' : '') + '>';
      if (props.suffix || props.icon) ctl = '<div class="input-group">' + (props.icon ? H.icon(props.icon) : '') + ctl + (props.suffix ? '<span class="input-group__suffix">' + esc(props.suffix) + '</span>' : '') + '</div>';
    }
    return el('<div class="field"><label class="field__label" for="' + id + '">' + esc(props.label) + (props.optional ? ' <span class="field__label-optional">(optional)</span>' : '') + '</label>' + ctl +
      (props.error ? '<p class="field__error">' + esc(props.error) + '</p>' : props.hint ? '<p class="field__hint" id="' + hid + '">' + esc(props.hint) + '</p>' : '') + '</div>');
  };
  H.Switch = function (props) {
    var id = props.id || nextId('sw');
    return el('<div class="field field--inline"><span class="field__text"><label class="field__label" for="' + id + '">' + esc(props.label) + '</label>' + (props.hint ? '<span class="field__hint">' + esc(props.hint) + '</span>' : '') + '</span>' +
      '<input type="checkbox" role="switch" class="switch" id="' + id + '"' + (props.checked ? ' checked' : '') + (props.disabled ? ' disabled' : '') + '></div>');
  };
  H.SegmentedControl = function (props) {
    var seg = el('<div class="segmented' + (props.block ? ' segmented--block' : '') + '" role="radiogroup" aria-label="' + esc(props.label) + '"></div>');
    props.options.forEach(function (o) {
      var on = o.value === props.value;
      seg.appendChild(el('<button type="button" class="segmented__option" role="radio" aria-checked="' + on + '" tabindex="' + (on ? 0 : -1) + '" data-value="' + esc(o.value) + '">' + (o.icon ? H.icon(o.icon, 'heim-icon--sm') : '') + esc(o.label) + '</button>'));
    });
    return seg;
  };
  H.ChoiceChips = function (props) {
    var g = el('<div class="choice-chips" role="group" aria-label="' + esc(props.label) + '"></div>');
    props.options.forEach(function (o) {
      var on = (props.selected || []).indexOf(o.value) > -1;
      g.appendChild(el('<button type="button" class="choice-chip" aria-pressed="' + on + '" data-value="' + esc(o.value) + '">' + H.icon('check', 'heim-icon--sm choice-chip__check') + esc(o.label) + '</button>'));
    });
    return g;
  };
  H.Callout = function (props) {
    return el('<div class="callout' + (props.tone ? ' callout--' + props.tone : '') + '" role="' + (props.tone === 'danger' ? 'alert' : 'note') + '">' + H.icon(props.icon || (props.tone ? 'alert' : 'info')) + '<div>' + (props.title ? '<strong class="callout__title">' + esc(props.title) + '</strong>' : '') + esc(props.body) + '</div></div>');
  };

  /* ---------- icon picker ---------- */
  H.IconPicker = function (props) {
    var svc = props.service || {}, src = props.source || 'favicon';
    var primary = (svc.links || []).filter(function (l) { return l.primary; })[0] || (svc.links || [])[0] || { url: '' };
    var pick = el(
      '<div class="icon-picker" data-icon-source="' + src + '">' +
        '<div class="icon-picker__preview"><span data-slot="preview"></span><span class="icon-picker__preview-label">Preview</span></div>' +
        '<div class="icon-picker__panel">' +
          '<span data-slot="seg"></span>' +
          '<div data-panel="favicon"><div class="favicon-row"><span class="favicon-row__url">' + esc(primary.url) + '/favicon.ico</span><button type="button" class="button button--sm" data-action="fetch-favicon">' + H.icon('download', 'heim-icon--sm') + 'Fetch</button></div><p class="field__hint" style="margin-top:8px">Fetched from the primary link. Falls back to initials if none is found.</p></div>' +
          '<div data-panel="library" hidden><div class="input-group">' + H.icon('search') + '<input class="input" type="search" placeholder="Search icons" aria-label="Search icons" value="' + esc(props.query || '') + '"></div><div class="icon-grid" role="group" aria-label="Icon results" style="margin-top:8px"></div></div>' +
          '<div data-panel="url" hidden><div class="field"><label class="field__label" for="icon-url">Image URL</label><input class="input input--mono" id="icon-url" type="url" placeholder="https://example.com/icon.png"><p class="field__hint">PNG, SVG or ICO. Square images look best.</p></div></div>' +
          '<div data-panel="upload" hidden><label class="drop-zone" tabindex="0">' + H.icon('upload', 'heim-icon--lg') + '<span><span class="drop-zone__title">Drop an image</span> or <span class="drop-zone__link">browse</span></span><span>PNG, SVG, ICO · up to 1 MB</span><input type="file" accept="image/*" class="sr-only"></label></div>' +
        '</div>' +
      '</div>');
    pick.querySelector('[data-slot="preview"]').replaceWith(H.ServiceIcon({ icon: svc.icon, name: svc.name, size: 'lg' }));
    pick.querySelector('[data-slot="seg"]').replaceWith(H.SegmentedControl({ label: 'Icon source', value: src, block: true, options: [
      { value: 'favicon', label: 'Favicon' }, { value: 'library', label: 'Library' }, { value: 'url', label: 'URL' }, { value: 'upload', label: 'Upload' }] }));
    var grid = pick.querySelector('.icon-grid');
    H.libraryIcons.forEach(function (n) {
      grid.appendChild(el('<button type="button" class="icon-grid__item" aria-pressed="' + (svc.icon && svc.icon.glyph === n) + '" data-glyph="' + n + '">' + H.icon(n) + '<span>' + n + '</span></button>'));
    });
    showPanel(pick, src);
    return pick;
  };
  function showPanel(pick, src) {
    pick.dataset.iconSource = src;
    pick.querySelectorAll('[data-panel]').forEach(function (p) { p.hidden = p.dataset.panel !== src; });
  }

  /* ---------- link row (service editor) ---------- */
  var KINDS = ['Public', 'Domain', 'Hostname', 'IP', 'VPN', 'Other'];
  H.KINDS = KINDS;
  H.LinkRow = function (props) {
    var l = props.link || {}, i = props.index || 0, d = props.defaults || { interval: 60, timeout: 5000, codes: '200-399' };
    var hc = l.health || {}, p = l.probe || { status: hc.enabled === false ? 'off' : 'unknown' };
    var id = nextId('lr');
    var on = hc.enabled !== false && p.status !== 'off';
    var state = on ? '<span class="status-dot status-dot--sm status-dot--' + p.status + '" data-status="' + p.status + '" aria-hidden="true"></span>' + esc(probeMeta(p)) : 'Off';
    return el(
      '<li class="link-row' + (l.primary ? ' link-row--primary' : '') + '" data-link-id="' + esc(l.id || id) + '">' +
        '<div class="link-row__main">' +
          '<button type="button" class="icon-button icon-button--sm link-row__handle" data-action="reorder-link" aria-label="Reorder link ' + (i + 1) + '. Use arrow keys to move.">' + H.icon('grip') + '</button>' +
          '<input class="input link-row__label" aria-label="Link ' + (i + 1) + ' label" placeholder="Label" value="' + esc(l.label || '') + '">' +
          '<select class="select link-row__kind" aria-label="Link ' + (i + 1) + ' kind">' + KINDS.map(function (k) { return '<option' + (k === l.kind ? ' selected' : '') + '>' + k + '</option>'; }).join('') + '</select>' +
          '<div class="link-row__actions">' +
            '<button type="button" class="icon-button icon-button--sm icon-button--danger" data-action="remove-link" aria-label="Remove link ' + (i + 1) + '">' + H.icon('trash') + '</button>' +
          '</div>' +
          '<input class="input input--mono link-row__url" type="url" aria-label="Link ' + (i + 1) + ' URL" placeholder="https://" value="' + esc(l.url || '') + '">' +
        '</div>' +
        '<div class="link-row__options">' +
          '<label class="check-label"><input type="radio" class="radio" name="primary-link" ' + (l.primary ? 'checked' : '') + '> Primary link</label>' +
          '<label class="check-label"><input type="checkbox" role="switch" class="switch switch--sm" ' + (l.newTab !== false ? 'checked' : '') + '> Open in new tab</label>' +
        '</div>' +
        '<details class="health-panel"' + (props.open ? ' open' : '') + '>' +
          '<summary class="health-panel__summary">' + H.icon('chevron-right', 'heim-icon--sm health-panel__chevron') + 'Health check<span class="health-panel__state">' + state + '</span></summary>' +
          '<div class="health-panel__body">' +
            '<div class="field field--inline field--wide"><span class="field__text"><label class="field__label" for="' + id + '-en">Check this link</label><span class="field__hint">Probes the URL on an interval and shows the result as a status dot.</span></span><input type="checkbox" role="switch" class="switch" id="' + id + '-en" data-health-toggle ' + (on ? 'checked' : '') + '></div>' +
            '<div class="field field--wide"><label class="field__label" for="' + id + '-pu">Probe URL <span class="field__label-optional">(optional)</span></label><input class="input input--mono" id="' + id + '-pu" type="url" placeholder="' + esc(l.url || 'Same as link URL') + '" value="' + esc(hc.probeUrl || '') + '" data-health-field><p class="field__hint">Use a health endpoint if the link redirects to a login page.</p></div>' +
            '<div class="field"><label class="field__label" for="' + id + '-sc">Accepted status codes</label><input class="input input--mono" id="' + id + '-sc" placeholder="' + esc(d.codes || '200-399') + '" value="' + esc(hc.codes || '') + '" data-health-field></div>' +
            '<div class="field" style="justify-content:flex-end;gap:8px"><label class="check-label"><input type="checkbox" class="checkbox" data-health-field ' + (hc.ignoreTls ? 'checked' : '') + '> Ignore TLS errors</label><label class="check-label"><input type="checkbox" class="checkbox" data-health-field ' + (hc.followRedirects !== false ? 'checked' : '') + '> Follow redirects</label></div>' +
            '<div class="field"><label class="field__label" for="' + id + '-iv">Interval</label><div class="input-group"><input class="input" id="' + id + '-iv" inputmode="numeric" placeholder="' + d.interval + '" value="' + esc(hc.interval || '') + '" data-health-field><span class="input-group__suffix">s</span></div></div>' +
            '<div class="field"><label class="field__label" for="' + id + '-to">Timeout</label><div class="input-group"><input class="input" id="' + id + '-to" inputmode="numeric" placeholder="' + d.timeout + '" value="' + esc(hc.timeout || '') + '" data-health-field><span class="input-group__suffix">ms</span></div></div>' +
          '</div>' +
        '</details>' +
      '</li>');
  };

  /* ---------- dialogs ---------- */
  /** Dialog — kind "drawer" (service editor) or "modal" (tab editor, confirmations). Body/footer are nodes or HTML. */
  H.Dialog = function (props) {
    var tid = nextId('dlg');
    var root = el(
      '<div class="dialog-root">' +
        '<div class="scrim" data-action="close-dialog"></div>' +
        '<section class="' + (props.kind === 'modal' ? 'modal' : 'drawer') + '" role="dialog" aria-modal="true" aria-labelledby="' + tid + '">' +
          '<header class="dialog__header"><h2 class="dialog__title" id="' + tid + '">' + esc(props.title) + '</h2><button type="button" class="icon-button" data-action="close-dialog" aria-label="Close">' + H.icon('x') + '</button></header>' +
          '<div class="dialog__body"></div>' +
          '<footer class="dialog__footer"></footer>' +
        '</section>' +
      '</div>');
    var body = root.querySelector('.dialog__body'), foot = root.querySelector('.dialog__footer');
    [].concat(props.body || []).forEach(function (n) { body.appendChild(typeof n === 'string' ? el(n) : n); });
    if (props.footer) [].concat(props.footer).forEach(function (n) { foot.appendChild(typeof n === 'string' ? el(n) : n); });
    else foot.remove();
    return root;
  };

  /** ServiceEditor — the drawer assembled from Field, ChoiceChips, IconPicker and LinkRow. */
  H.ServiceEditor = function (props) {
    var svc = props.service || { name: '', links: [] }, tabs = props.tabs || [];
    var details = el('<div class="dialog__section"><p class="section-label">Details</p><div class="field-row" data-slot="r"></div></div>');
    var row = details.querySelector('[data-slot="r"]');
    row.appendChild(H.Field({ label: 'Name', value: svc.name, placeholder: 'Jellyfin' }));
    row.appendChild(H.Field({ label: 'Description', optional: true, value: svc.description, placeholder: 'One line, shown under the name' }));
    var tabsF = el('<div class="field"><span class="field__label" id="svc-tabs">Show on tabs</span></div>');
    tabsF.appendChild(H.ChoiceChips({ label: 'Show on tabs', selected: svc.tabs || [], options: tabs.map(function (t) { return { value: t.id, label: t.name }; }) }));
    details.appendChild(tabsF);
    var icon = el('<div class="dialog__section"><p class="section-label">Icon</p></div>');
    icon.appendChild(H.IconPicker({ service: svc, source: props.iconSource || 'library', query: props.iconQuery }));
    var links = el('<div class="dialog__section"><p class="section-label"><span>Links</span><span style="text-transform:none;letter-spacing:0">' + (svc.links || []).length + ' of 8</span></p><ol class="link-list"></ol><button type="button" class="button button--sm" data-action="add-link" style="align-self:flex-start">' + H.icon('plus', 'heim-icon--sm') + 'Add link</button></div>');
    var list = links.querySelector('.link-list');
    (svc.links || []).forEach(function (l, i) { list.appendChild(H.LinkRow({ link: l, index: i, open: i === (props.openHealth == null ? -1 : props.openHealth), defaults: props.defaults })); });
    var footer = [
      el('<button type="button" class="button button--danger-ghost" data-action="confirm-delete">' + H.icon('trash', 'heim-icon--sm') + 'Delete</button>'),
      el('<span class="dialog__footer-spacer"></span>'),
      el('<button type="button" class="button" data-action="close-dialog">Cancel</button>'),
      el('<button type="submit" class="button button--primary" data-action="save-service">Save</button>')
    ];
    return H.Dialog({ kind: 'drawer', title: props.isNew ? 'Add service' : 'Edit service', body: [details, icon, links], footer: footer });
  };

  /* ---------- theme ---------- */
  /** Theme: 'system' | 'light' | 'dark'. Sets <html data-theme>, follows the OS while on 'system'. */
  H.theme = {
    mq: window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null,
    pref: 'system',
    apply: function () {
      var t = this.pref === 'system' ? (this.mq && this.mq.matches ? 'dark' : 'light') : this.pref;
      document.documentElement.setAttribute('data-theme', t);
      return t;
    },
    init: function (pref) {
      var self = this;
      try { this.pref = pref || localStorage.getItem('heim-theme') || 'system'; } catch (e) { this.pref = pref || 'system'; }
      if (this.mq && !this._bound) { this._bound = true; this.mq.addEventListener('change', function () { if (self.pref === 'system') self.apply(); }); }
      return this.apply();
    },
    set: function (pref) { this.pref = pref; try { localStorage.setItem('heim-theme', pref); } catch (e) { /* storage blocked */ } return this.apply(); }
  };

  /* ---------- behaviour: one delegated mount ---------- */
  function closeMenus(root, except) {
    root.querySelectorAll('[data-overflow-toggle][aria-expanded="true"]').forEach(function (b) {
      if (b === except) return;
      b.setAttribute('aria-expanded', 'false');
      var m = document.getElementById(b.getAttribute('aria-controls')); if (m) m.hidden = true;
    });
  }
  function move(item, dir) {
    var list = item.parentElement, sib = dir < 0 ? item.previousElementSibling : item.nextElementSibling;
    if (!sib || sib.querySelector('.service-card--add')) return false;
    if (dir < 0) list.insertBefore(item, sib); else list.insertBefore(sib, item);
    return true;
  }
  function announce(root, msg) {
    var live = root.querySelector('[data-heim-live]');
    if (!live) { live = el('<div class="sr-only" aria-live="polite" data-heim-live></div>'); root.appendChild(live); }
    live.textContent = msg;
  }

  /**
   * Heim.mount(root, { onAction(name, detail, event), demo }) — wires menus, tabs, segmented controls,
   * chips, link rows, keyboard reorder and drag reorder. demo: true stops links from navigating.
   */
  H.mount = function (root, opts) {
    root = root || document.body; opts = opts || {};
    var emit = function (name, detail, ev) { if (opts.onAction) opts.onAction(name, detail || {}, ev); };

    root.addEventListener('click', function (e) {
      var t = e.target;
      var tog = t.closest('[data-overflow-toggle]');
      if (tog) {
        e.preventDefault(); e.stopPropagation();
        var open = tog.getAttribute('aria-expanded') !== 'true';
        closeMenus(root, tog);
        tog.setAttribute('aria-expanded', String(open));
        var m = document.getElementById(tog.getAttribute('aria-controls'));
        if (m) { m.hidden = !open; if (open) { var f = m.querySelector('[role="menuitem"]'); if (f) f.focus(); } }
        return;
      }
      if (!t.closest('.menu')) closeMenus(root);

      var tab = t.closest('[role="tab"]');
      if (tab) {
        var id = tab.dataset.tabId;
        root.querySelectorAll('[role="tab"]').forEach(function (x) { var on = x.dataset.tabId === id; x.setAttribute('aria-selected', on); x.classList.toggle('tab--active', on); x.tabIndex = on ? 0 : -1; });
        emit('select-tab', { id: id }, e); return;
      }
      var seg = t.closest('.segmented__option');
      if (seg) {
        seg.parentElement.querySelectorAll('.segmented__option').forEach(function (o) { var on = o === seg; o.setAttribute('aria-checked', on); o.tabIndex = on ? 0 : -1; });
        var pick = seg.closest('.icon-picker'); if (pick) showPanel(pick, seg.dataset.value);
        emit('segment', { value: seg.dataset.value, group: seg.parentElement.getAttribute('aria-label') }, e); return;
      }
      var chip = t.closest('.choice-chip');
      if (chip) { chip.setAttribute('aria-pressed', chip.getAttribute('aria-pressed') !== 'true'); emit('choice', { value: chip.dataset.value, on: chip.getAttribute('aria-pressed') === 'true' }, e); return; }
      var g = t.closest('.icon-grid__item');
      if (g) {
        g.parentElement.querySelectorAll('.icon-grid__item').forEach(function (x) { x.setAttribute('aria-pressed', x === g); });
        var pv = g.closest('.icon-picker').querySelector('.service-icon');
        if (pv) { pv.className = 'service-icon service-icon--lg'; pv.innerHTML = H.icon(g.dataset.glyph); }
        emit('pick-icon', { glyph: g.dataset.glyph }, e); return;
      }
      var act = t.closest('[data-action]');
      if (act) {
        var name = act.dataset.action;
        if (name === 'remove-link') { var li = act.closest('.link-row'); if (li) li.remove(); }
        if (name === 'confirm-delete') {
          var foot = act.closest('.dialog__footer');
          if (foot && !foot.querySelector('.confirm')) {
            act.hidden = true;
            act.insertAdjacentElement('afterend', el('<span class="confirm" role="alert">Delete this service?<button type="button" class="button button--sm button--danger" data-action="delete-service">Delete</button><button type="button" class="button button--sm button--ghost" data-action="cancel-delete">Keep</button></span>'));
          }
        }
        if (name === 'cancel-delete') { var c = act.closest('.confirm'); var b = c.previousElementSibling; c.remove(); if (b) { b.hidden = false; b.focus(); } }
        var card = act.closest('.service-card');
        emit(name, { serviceId: card && card.dataset.serviceId, el: act }, e);
        return;
      }
      if (opts.demo) { var a = t.closest('a[href]'); if (a) { e.preventDefault(); emit('open-link', { href: a.getAttribute('href') }, e); } }
    });

    root.addEventListener('change', function (e) {
      var t = e.target;
      if (t.matches('[data-health-toggle]')) {
        var body = t.closest('.health-panel__body');
        body.querySelectorAll('[data-health-field]').forEach(function (f) { f.disabled = !t.checked; });
        var st = t.closest('.health-panel').querySelector('.health-panel__state');
        if (st) st.innerHTML = t.checked ? '<span class="status-dot status-dot--sm status-dot--unknown" aria-hidden="true"></span>Checking…' : 'Off';
      }
      if (t.matches('.radio[name="primary-link"]')) {
        root.querySelectorAll('.link-row').forEach(function (r) { r.classList.toggle('link-row--primary', r.contains(t)); });
      }
    });

    root.addEventListener('keydown', function (e) {
      var t = e.target;
      if (e.key === 'Escape') {
        if (root.querySelector('[data-overflow-toggle][aria-expanded="true"]')) { var open = root.querySelector('[data-overflow-toggle][aria-expanded="true"]'); closeMenus(root); open.focus(); return; }
        if (root.querySelector('[role="dialog"]')) emit('close-dialog', {}, e);
      }
      var horiz = e.key === 'ArrowLeft' || e.key === 'ArrowRight', vert = e.key === 'ArrowUp' || e.key === 'ArrowDown';
      if (!horiz && !vert) return;
      var dir = (e.key === 'ArrowLeft' || e.key === 'ArrowUp') ? -1 : 1;
      if (t.matches('[role="tab"]') && horiz) {
        var tabs = Array.prototype.slice.call(t.parentElement.querySelectorAll('[role="tab"]'));
        var n = tabs[(tabs.indexOf(t) + dir + tabs.length) % tabs.length]; n.focus(); n.click(); e.preventDefault(); return;
      }
      if (t.matches('.segmented__option')) {
        var os = Array.prototype.slice.call(t.parentElement.querySelectorAll('.segmented__option'));
        var o = os[(os.indexOf(t) + dir + os.length) % os.length]; o.focus(); o.click(); e.preventDefault(); return;
      }
      if (t.matches('[role="menuitem"]') && vert) {
        var items = Array.prototype.slice.call(t.closest('.menu').querySelectorAll('[role="menuitem"]'));
        var it = items[(items.indexOf(t) + dir + items.length) % items.length]; it.focus(); e.preventDefault(); return;
      }
      if (t.matches('[data-action="reorder"], [data-action="reorder-link"]')) {
        var item = t.closest('.service-card, .link-row');
        if (move(item, dir)) {
          t.focus(); e.preventDefault();
          var pos = Array.prototype.indexOf.call(item.parentElement.children, item) + 1;
          announce(root, 'Moved to position ' + pos);
          emit(item.classList.contains('link-row') ? 'reorder-links' : 'reorder-services', { id: item.dataset.serviceId || item.dataset.linkId, position: pos }, e);
        }
      }
    });

    /* pointer drag reorder for cards (edit mode only) */
    var dragged = null;
    root.addEventListener('pointerdown', function (e) {
      var h = e.target.closest('.service-card__handle');
      if (!h || !h.closest('.service-grid--editing')) return;
      var card = h.closest('.service-card'); card.setAttribute('draggable', 'true');
    });
    root.addEventListener('dragstart', function (e) {
      var card = e.target.closest && e.target.closest('.service-card');
      if (!card) return; dragged = card; card.classList.add('service-card--dragging');
      e.dataTransfer.effectAllowed = 'move'; try { e.dataTransfer.setData('text/plain', card.dataset.serviceId); } catch (x) { /* ignore */ }
    });
    root.addEventListener('dragover', function (e) {
      if (!dragged) { var dz = e.target.closest && e.target.closest('.drop-zone'); if (dz) { e.preventDefault(); dz.classList.add('drop-zone--over'); } return; }
      var over = e.target.closest && e.target.closest('.service-card:not(.service-card--add)');
      if (!over || over === dragged) return;
      e.preventDefault();
      var r = over.getBoundingClientRect(), after = (e.clientX - r.left) > r.width / 2;
      over.parentElement.insertBefore(dragged, after ? over.nextElementSibling : over);
    });
    root.addEventListener('dragleave', function (e) { var dz = e.target.closest && e.target.closest('.drop-zone'); if (dz) dz.classList.remove('drop-zone--over'); });
    root.addEventListener('drop', function (e) { var dz = e.target.closest && e.target.closest('.drop-zone'); if (dz) { e.preventDefault(); dz.classList.remove('drop-zone--over'); emit('drop-file', { files: e.dataTransfer.files }, e); } });
    root.addEventListener('dragend', function () {
      if (!dragged) return;
      dragged.classList.remove('service-card--dragging'); dragged.removeAttribute('draggable');
      emit('reorder-services', { id: dragged.dataset.serviceId, position: Array.prototype.indexOf.call(dragged.parentElement.children, dragged) + 1 });
      dragged = null;
    });
    document.addEventListener('click', function (e) { if (!root.contains(e.target)) closeMenus(root); });
    return root;
  };

  /**
   * Dashboard — renders the whole main view into root from a config object
   * ({title, tabs, services, user}) and re-renders on tab switch / edit toggle.
   * The backend hands the same JSON the REST API returns.
   */
  H.Dashboard = function (root, cfg) {
    var state = { tab: cfg.tab || (cfg.tabs.filter(function (t) { return t.isDefault; })[0] || cfg.tabs[0] || {}).id, editing: !!cfg.editing };
    function servicesFor(id) {
      if (cfg.servicesFor) return cfg.servicesFor(id);
      var tab = cfg.tabs.filter(function (t) { return t.id === id; })[0];
      return tab && tab.showAll ? cfg.services.slice() : cfg.services.filter(function (s) { return (s.tabs || []).indexOf(id) > -1; });
    }
    function render() {
      var focusTab = document.activeElement && document.activeElement.dataset && document.activeElement.dataset.tabId;
      root.innerHTML = '';
      root.className = 'app' + (cfg.background ? ' app--has-background' : '');
      if (cfg.background) root.style.setProperty('--background-image', cfg.background);
      root.appendChild(H.TopBar({ title: cfg.title, tabs: cfg.tabs, active: state.tab, editing: state.editing, user: cfg.user, canEdit: cfg.canEdit, settingsHref: cfg.settingsHref, loginHref: cfg.loginHref }));
      var main = el('<main class="page" id="main"></main>');
      var tab = cfg.tabs.filter(function (t) { return t.id === state.tab; })[0] || { name: '' };
      var list = cfg.firstRun ? [] : servicesFor(state.tab);
      var counts = { degraded: 0, down: 0 };
      list.forEach(function (s) { var st = H.computeStatus(s.links).status; if (counts[st] != null) counts[st] += 1; });
      if (!cfg.firstRun) {
        main.appendChild(el('<div class="grid-toolbar"><h1 class="grid-toolbar__title">' + esc(tab.name) + '</h1><span class="grid-toolbar__meta"><span>' + list.length + ' service' + (list.length === 1 ? '' : 's') + '</span>' +
          (counts.degraded ? '<span class="status-label"><span class="status-dot status-dot--sm status-dot--degraded" aria-hidden="true"></span>' + counts.degraded + ' degraded</span>' : '') +
          (counts.down ? '<span class="status-label"><span class="status-dot status-dot--sm status-dot--down" aria-hidden="true"></span>' + counts.down + ' down</span>' : '') +
          '</span></div>'));
      }
      if (state.editing && !cfg.firstRun) main.appendChild(el('<div class="edit-banner" role="status">' + H.icon('pencil', 'heim-icon--sm') + '<span class="edit-banner__text">Editing <strong>' + esc(tab.name) + '</strong>. Drag cards or tabs to reorder; changes save automatically.</span><button type="button" class="button button--sm button--primary" data-action="toggle-edit">Done</button></div>'));
      if (cfg.firstRun) {
        main.appendChild(H.EmptyState({ firstRun: true, icon: 'house', title: 'Add your first service', body: 'A service is one app with one or more links — public, LAN, hostname or VPN. Heim checks each link and shows which ones are up.', actions: [{ label: 'Add service', primary: true, icon: 'plus', action: 'add-service' }, { label: 'Import configuration', icon: 'upload', action: 'import' }] }));
      } else if (!list.length && !state.editing) {
        main.appendChild(H.EmptyState({ icon: 'grid', title: 'No services on ' + tab.name, body: 'Add a service, or pick this tab in an existing service’s editor.', actions: [{ label: 'Add service', icon: 'plus', action: 'add-service' }] }));
      } else {
        main.appendChild(H.ServiceGrid({ services: list, editing: state.editing }));
      }
      root.appendChild(main);
      if (focusTab) { var f = root.querySelector('.top-bar [data-tab-id="' + focusTab + '"]') || root.querySelector('[data-tab-id="' + focusTab + '"]'); if (f) f.focus(); }
    }
    H.mount(root, {
      demo: cfg.demo,
      onAction: function (name, d, e) {
        if (name === 'select-tab') { state.tab = d.id; render(); }
        else if (name === 'toggle-edit') { state.editing = !state.editing; render(); var b = root.querySelector('.edit-toggle'); if (b) b.focus(); }
        if (cfg.onAction) cfg.onAction(name, d, e);
      }
    });
    render();
    return { state: state, render: render };
  };

  /* ---------- sample data (mockups and tests only) ---------- */
  function L(id, label, kind, url, probe, extra) { var o = { id: id, label: label, kind: kind, url: url, probe: probe }; for (var k in extra || {}) o[k] = extra[k]; return o; }
  var up = function (http, ms) { return { status: 'up', http: http, latency: ms }; };
  var down = function (err, ms) { return { status: 'down', error: err, latency: ms }; };
  var OFF = { status: 'off' };
  H.sample = {
    title: 'Heim',
    tabs: [
      { id: 'dashboard', name: 'Dashboard', isDefault: true, showAll: true },
      { id: 'networking', name: 'Networking' },
      { id: 'media', name: 'Media' },
      { id: 'infrastructure', name: 'Infrastructure' }
    ],
    services: [
      { id: 'proxmox', name: 'Proxmox', description: 'Virtualization cluster · 3 nodes', icon: { glyph: 'server' }, tabs: ['dashboard', 'infrastructure'], links: [
        L('pve-pub', 'Public', 'Public', 'https://pve.example.com', up(200, 41)),
        L('pve-dom', 'Domain', 'Domain', 'https://pve.home.arpa', up(200, 12), { primary: true }),
        L('pve-ip', 'IP', 'IP', 'https://192.168.1.10:8006', down('Timeout', 5000))] },
      { id: 'jellyfin', name: 'Jellyfin', description: 'Movies, shows and music', icon: { glyph: 'play' }, tabs: ['dashboard', 'media'], links: [
        L('jf-pub', 'Public', 'Public', 'https://jellyfin.example.com', up(200, 58), { primary: true }),
        L('jf-lan', 'LAN', 'IP', 'http://192.168.1.20:8096', up(200, 4)),
        L('jf-host', 'Hostname', 'Hostname', 'http://media-server:8096', up(200, 6)),
        L('jf-vpn', 'VPN', 'VPN', 'http://100.64.0.5:8096', up(200, 31))] },
      { id: 'pihole', name: 'Pi-hole', description: 'Network-wide ad blocking', icon: { glyph: 'shield' }, tabs: ['dashboard', 'networking'], links: [
        L('ph-dom', 'Admin', 'Domain', 'https://pihole.home.arpa/admin', up(200, 9), { primary: true }),
        L('ph-ip', 'IP', 'IP', 'http://192.168.1.2/admin', up(200, 3))] },
      { id: 'homeassistant', name: 'Home Assistant', description: 'Automations, lights and sensors', icon: { glyph: 'home' }, tabs: ['dashboard'], links: [
        L('ha-pub', 'Public', 'Public', 'https://ha.example.com', up(200, 64), { primary: true }),
        L('ha-lan', 'LAN', 'IP', 'http://192.168.1.30:8123', up(200, 5))] },
      { id: 'nextcloud', name: 'Nextcloud', description: 'Files, calendar and contacts', icon: { glyph: 'cloud' }, tabs: ['dashboard'], links: [
        L('nc-pub', 'Public', 'Public', 'https://cloud.example.com', up(302, 112), { primary: true })] },
      { id: 'opnsense', name: 'OPNsense', description: 'Firewall and router', icon: null, tabs: ['networking', 'infrastructure'], links: [
        L('fw-dom', 'Domain', 'Domain', 'https://fw.home.arpa', OFF, { primary: true }),
        L('fw-ip', 'IP', 'IP', 'https://192.168.1.1', OFF)] },
      { id: 'portainer', name: 'Portainer', description: 'Container management', icon: { glyph: 'box' }, tabs: ['infrastructure'], links: [
        L('pt-dom', 'Domain', 'Domain', 'https://portainer.home.arpa', down('Connection refused', 2), { primary: true }),
        L('pt-ip', 'IP', 'IP', 'https://192.168.1.40:9443', down('Timeout', 5000))] },
      { id: 'grafana', name: 'Grafana', description: 'Metrics and dashboards', icon: { glyph: 'chart' }, tabs: ['infrastructure'], links: [
        L('gf-dom', 'Domain', 'Domain', 'https://grafana.home.arpa', up(200, 22), { primary: true })] },
      { id: 'vaultwarden', name: 'Vaultwarden', description: 'Password manager', icon: { glyph: 'key' }, tabs: ['dashboard'], links: [
        L('vw-pub', 'Public', 'Public', 'https://vault.example.com', up(200, 47), { primary: true })] },
      { id: 'uptimekuma', name: 'Uptime Kuma', description: 'Status pages and alerting', icon: null, tabs: ['infrastructure'], links: [
        L('uk-dom', 'Domain', 'Domain', 'http://status.home.arpa:3001', { status: 'unknown' }, { primary: true })] }
    ],
    servicesFor: function (tabId) {
      var tab = this.tabs.filter(function (t) { return t.id === tabId; })[0];
      if (tab && tab.showAll) return this.services.slice();
      return this.services.filter(function (s) { return s.tabs.indexOf(tabId) > -1; });
    }
  };

  window.Heim = H;
})();
