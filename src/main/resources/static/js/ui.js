// Shared UI plumbing around the Heim factories: dialogs, confirmations, error callouts, appearance.

const { Heim } = window;

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])';

function visibleFocusables(root) {
  return [...root.querySelectorAll(FOCUSABLE)].filter((n) => n.offsetParent !== null || n === document.activeElement);
}

/**
 * Shows a Heim.Dialog root: mounts behaviour, traps focus, closes on Escape / scrim / close buttons and
 * returns focus to the opener. onAction gets every other action. Returns { root, close }.
 */
export function openDialog(dialogRoot, { onAction, onClose } = {}) {
  const opener = document.activeElement;
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    dialogRoot.remove();
    if (onClose) onClose();
    if (opener && opener.isConnected) opener.focus();
  };
  document.body.appendChild(dialogRoot);
  Heim.mount(dialogRoot, {
    onAction: (name, detail, event) => {
      if (name === 'close-dialog') close();
      else if (onAction) onAction(name, detail, event);
    },
  });
  dialogRoot.addEventListener('keydown', (e) => {
    if (e.key !== 'Tab') return;
    const items = visibleFocusables(dialogRoot.querySelector('[role="dialog"]'));
    if (!items.length) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) { last.focus(); e.preventDefault(); }
    else if (!e.shiftKey && document.activeElement === last) { first.focus(); e.preventDefault(); }
  });
  const target = dialogRoot.querySelector('.dialog__body input, .dialog__body select, .dialog__body textarea')
    || dialogRoot.querySelector('[role="dialog"] button');
  if (target) target.focus();
  return { root: dialogRoot, close };
}

/** 440px confirmation modal. Resolves true when the destructive action is chosen. */
export function confirmDialog({ title, body, confirmLabel }) {
  return new Promise((resolve) => {
    let answer = false;
    const cancelButton = Heim.Button({ label: 'Cancel', action: 'close-dialog' });
    const dlg = Heim.Dialog({
      kind: 'modal',
      title,
      body: [paragraph(body)],
      footer: [el('span', 'dialog__footer-spacer'), cancelButton,
        Heim.Button({ label: confirmLabel, variant: 'danger', action: 'confirm' })],
    });
    const { close } = openDialog(dlg, {
      onAction: (name) => { if (name === 'confirm') { answer = true; close(); } },
      onClose: () => resolve(answer),
    });
    cancelButton.focus();
  });
}

export function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function paragraph(text) {
  const p = el('p', null, text);
  p.style.margin = '0';
  return p;
}

/** Message for an ApiError (or any Error). */
export function errorText(err) {
  return (err && err.message) || 'Something went wrong. Try again.';
}

/** Danger callout with the error message. */
export function errorCallout(err) {
  const callout = Heim.Callout({ tone: 'danger', body: errorText(err) });
  callout.dataset.error = '';
  return callout;
}

/** Replaces the error callout that is the first child of `container` (or removes it when err is null). */
export function showError(container, err) {
  const old = container && container.querySelector(':scope > [data-error]');
  if (old) old.remove();
  if (!err || !container || err.handled) return;
  const callout = errorCallout(err);
  container.prepend(callout);
  callout.scrollIntoView({ block: 'nearest' });
}

/** Opens the browser's file chooser. Resolves the picked file or null. */
export function pickFile(accept) {
  return new Promise((resolve) => {
    const input = el('input');
    input.type = 'file';
    input.accept = accept;
    input.addEventListener('change', () => resolve(input.files[0] || null), { once: true });
    input.addEventListener('cancel', () => resolve(null), { once: true });
    input.click();
  });
}

/** Debounces fn by ms. */
export function debounce(fn, ms) {
  let timer = null;
  return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), ms); };
}

/* ---------- appearance ---------- */

/** Page title and theme; `ui` is the adapted dashboard. */
export function applyAppearance(ui) {
  document.title = ui.title;
  Heim.theme.set(ui.theme);
}

/** Re-requests /custom.css after it changed. */
export function reloadCustomCss() {
  const link = document.getElementById('custom-css');
  if (link) link.href = `/custom.css?v=${Date.now()}`;
}

/** Background on the .app root: `css` is a ready `url("…")` value or null. */
export function applyBackground(root, css) {
  root.classList.toggle('app--has-background', !!css);
  if (css) root.style.setProperty('--background-image', css);
  else root.style.removeProperty('--background-image');
}
