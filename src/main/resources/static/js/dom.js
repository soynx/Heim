// Tiny DOM helpers. All user content goes through text nodes / attributes, never innerHTML.

/**
 * Creates an element.
 *   h('a', { class: 'chip', href: url, onclick: fn, dataset: { id } }, 'text', childNode)
 * Props: `class`, `text`, `style` (object of CSS properties), `dataset`, `on<event>` handlers,
 * boolean values toggle the attribute, everything else is set as an attribute.
 * Children: strings/numbers become text nodes; null/undefined/false are skipped; arrays are flattened.
 */
export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === undefined || value === null) continue;
    if (key === 'class') el.className = value;
    else if (key === 'text') el.textContent = value;
    else if (key === 'style') for (const [p, v] of Object.entries(value)) el.style.setProperty(p, v);
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value);
    else if (key === 'value') el.value = value;
    else if (key === 'checked' || key === 'selected' || key === 'disabled' || key === 'hidden'
      || key === 'required' || key === 'multiple' || key === 'open') el[key] = !!value;
    else if (typeof value === 'boolean') { if (value) el.setAttribute(key, ''); }
    else el.setAttribute(key, String(value));
  }
  append(el, children);
  return el;
}

export function append(parent, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    parent.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return parent;
}

/** Replaces all children of `el`. */
export function replace(el, ...children) {
  el.replaceChildren();
  return append(el, children);
}

/** URL safe to put in href/src: blocks script-capable schemes, allows everything else (smb:, ssh:, ...). */
export function safeUrl(url) {
  if (!url) return null;
  const scheme = /^\s*([a-z][a-z0-9+.-]*):/i.exec(url);
  if (scheme && ['javascript', 'data', 'vbscript'].includes(scheme[1].toLowerCase())) return null;
  return url;
}

/** Value for CSS `url(...)`, quoted and escaped. */
export function cssUrl(url) {
  const safe = safeUrl(url);
  return safe ? `url("${safe.replace(/["\\\n\r]/g, (c) => '\\' + c.charCodeAt(0).toString(16) + ' ')}")` : 'none';
}

/** Labeled form field: <label><span>Label</span>input</label>. */
export function field(label, input, hint) {
  return h('label', { class: 'field' }, h('span', { class: 'field-label' }, label), input,
    hint ? h('small', { class: 'field-hint' }, hint) : null);
}

/** Checkbox with a trailing label. */
export function checkbox(label, checked, onchange, props = {}) {
  const input = h('input', { type: 'checkbox', checked, onchange: (e) => onchange(e.target.checked), ...props });
  return h('label', { class: 'check' }, input, h('span', {}, label));
}

/** Button; `onclick` may be async, the button is disabled while it runs. */
export function button(label, onclick, props = {}) {
  const el = h('button', { type: 'button', ...props }, label);
  el.addEventListener('click', async (e) => {
    el.disabled = true;
    try { await onclick(e); } finally { el.disabled = false; }
  });
  return el;
}

/** Empty string → null, otherwise integer. */
export function intOrNull(value) {
  const s = String(value ?? '').trim();
  return s === '' ? null : Number.parseInt(s, 10);
}
