// Tab editor modal (screens/tab-editor.html): name, colour, default, show all, delete.

import { api } from '../api.js';
import { tabRequest } from '../adapter.js';
import { openDialog, showError, el } from '../ui.js';

const { Heim } = window;

const COLORS = [['None', ''], ['Slate', '#64748b'], ['Blue', '#3b6fd8'], ['Teal', '#1f8a84'], ['Green', '#3f8a3a'],
  ['Amber', '#c27a12'], ['Rose', '#c2416b'], ['Violet', '#7656c9']];

let seq = 0;

/** Opens the editor for a new tab (no tabId) or an existing one. onSaved(tabId | null) runs after a change. */
export async function openTabEditor({ tabId, tabs, onSaved }) {
  let tab = null;
  let etag = null;
  if (tabId) {
    try {
      ({ data: tab, etag } = await api.tabs.get(tabId));
    } catch (err) {
      if (!err.handled) showError(document.querySelector('#main'), err);
      return;
    }
  }

  const name = Heim.Field({ label: 'Name', value: tab?.name || '', placeholder: 'Media' });
  const nameInput = name.querySelector('input');
  nameInput.maxLength = 100;
  const colors = colorField(tab?.color || '');
  const current = tabs.find((t) => t.isDefault);
  const isDefault = Heim.Switch({
    label: 'Default tab',
    checked: !!tab?.isDefault,
    disabled: !!tab?.isDefault,
    hint: tab?.isDefault
      ? 'This tab opens first. To change that, make another tab the default.'
      : `Opens first when someone visits the dashboard.${current ? ` Currently: ${current.name}.` : ''}`,
  });
  const showAll = Heim.Switch({
    label: 'Show all services',
    checked: !!tab?.showAll,
    hint: 'Also shows services that are not explicitly added to this tab.',
  });

  const footer = [];
  if (tab && tabs.length > 1) {
    footer.push(Heim.Button({ label: 'Delete tab', variant: 'danger-ghost', icon: 'trash', action: 'ask-delete-tab' }));
  }
  footer.push(el('span', 'dialog__footer-spacer'), Heim.Button({ label: 'Cancel', action: 'close-dialog' }),
    Heim.Button({ label: 'Save', variant: 'primary', action: 'save-tab' }));

  const dlg = Heim.Dialog({ kind: 'modal', title: tab ? 'Edit tab' : 'Add tab', body: [name, colors.field, isDefault, showAll], footer });
  const body = dlg.querySelector('.dialog__body');

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
    const value = nameInput.value.trim();
    if (!value) {
      showError(body, new Error('Give the tab a name.'));
      nameInput.focus();
      return;
    }
    const request = tabRequest({
      name: value,
      color: colors.value(),
      isDefault: isDefault.querySelector('input').checked,
      showAll: showAll.querySelector('input').checked,
    }, tab);
    const res = tab ? await api.tabs.update(tab.id, request, etag) : await api.tabs.create(request);
    close();
    await onSaved(res.data.id);
  }

  async function remove() {
    await api.tabs.remove(tab.id, etag);
    close();
    await onSaved(null);
  }

  // The modal is narrow, so the inline confirmation takes the whole footer while it is open.
  const footerButtons = () => dlg.querySelectorAll('.dialog__footer > .button');
  function askDelete(trigger) {
    footerButtons().forEach((b) => { b.hidden = true; });
    const confirm = el('span', 'confirm', 'Delete this tab?');
    confirm.setAttribute('role', 'alert');
    confirm.append(Heim.Button({ label: 'Delete tab', variant: 'danger', size: 'sm', action: 'delete-tab' }),
      Heim.Button({ label: 'Keep', variant: 'ghost', size: 'sm', action: 'keep-tab' }));
    trigger.after(confirm);
    confirm.querySelector('[data-action="keep-tab"]').focus();
  }

  function keep(trigger) {
    trigger.closest('.confirm').remove();
    footerButtons().forEach((b) => { b.hidden = false; });
    dlg.querySelector('[data-action="ask-delete-tab"]').focus();
  }

  const { close } = openDialog(dlg, {
    onAction: (action, detail) => {
      if (action === 'save-tab') run(save);
      else if (action === 'ask-delete-tab') askDelete(detail.el);
      else if (action === 'keep-tab') keep(detail.el);
      else if (action === 'delete-tab') run(remove);
    },
  });
  dlg.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target === nameInput) { e.preventDefault(); run(save); }
  });
}

/** Swatch radios as in the mockup; a colour that is not in the palette gets its own swatch. */
function colorField(color) {
  const group = `tab-color-${++seq}`;
  const field = el('div', 'field');
  const label = el('span', 'field__label', 'Colour ');
  label.id = `${group}-label`;
  label.appendChild(el('span', 'field__label-optional', '(optional)'));
  const swatches = el('div', 'swatches');
  swatches.setAttribute('role', 'radiogroup');
  swatches.setAttribute('aria-labelledby', label.id);
  const palette = COLORS.some(([, c]) => c.toLowerCase() === color.toLowerCase()) ? COLORS : [...COLORS, ['Custom', color]];
  palette.forEach(([name, c]) => {
    const input = el('input', c ? 'swatch' : 'swatch swatch--none');
    input.type = 'radio';
    input.name = group;
    input.value = c;
    input.setAttribute('aria-label', name);
    if (c) input.style.setProperty('--swatch', c);
    input.checked = c.toLowerCase() === color.toLowerCase();
    swatches.appendChild(input);
  });
  field.append(label, swatches, el('p', 'field__hint', 'Shown as a small dot before the tab name.'));
  return { field, value: () => swatches.querySelector('input:checked')?.value || '' };
}
