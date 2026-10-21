// Sign in (screens/login.html). The backend has no remember-me, so the mockup's checkbox is left out.

import { api } from '../api.js';
import { session, refreshSession } from '../session.js';
import { navigate } from '../router.js';
import { restartStatus } from '../status.js';
import { el } from '../ui.js';

const { Heim } = window;

export async function loginView(root) {
  const me = await refreshSession(); // the cached session may be stale after a 401
  if (me.authenticated) {
    navigate('#/');
    return null;
  }
  document.title = 'Sign in';

  const main = el('main', 'login');
  const wrap = el('div');
  const form = el('form', 'login-card');
  form.noValidate = true;

  const brand = el('div', 'login-card__brand');
  const mark = el('span', 'brand-mark');
  mark.innerHTML = Heim.icon('house');
  brand.append(mark, el('h1', 'login-card__wordmark', 'Heim'),
    el('p', 'login-card__sub', me.passwordSet ? 'Sign in to continue' : 'Login isn’t set up'));

  const fields = el('div', 'login-card__form');
  if (!me.passwordSet) {
    fields.append(Heim.Callout({ body: 'Set a username and password in Settings to turn on login.' }));
    const back = el('a', 'button button--primary button--block', 'Back to the dashboard');
    back.href = '#/';
    fields.append(back);
  } else {
    const username = Heim.Field({ label: 'Username', id: 'login-username' });
    const user = username.querySelector('input');
    user.name = 'username';
    user.autocomplete = 'username';

    const passwordField = el('div', 'field');
    const passwordLabel = el('label', 'field__label', 'Password');
    passwordLabel.htmlFor = 'login-password';
    const group = el('div', 'input-group');
    const password = el('input', 'input');
    password.id = 'login-password';
    password.type = 'password';
    password.name = 'password';
    password.autocomplete = 'current-password';
    password.style.paddingRight = '40px';
    group.appendChild(password);
    passwordField.append(passwordLabel, group);
    const show = el('button', 'icon-button icon-button--sm');
    show.type = 'button';
    show.style.cssText = 'position:absolute;right:4px';
    show.setAttribute('aria-label', 'Show password');
    show.setAttribute('aria-pressed', 'false');
    show.innerHTML = Heim.icon('eye');
    show.addEventListener('click', () => {
      const on = password.type === 'password';
      password.type = on ? 'text' : 'password';
      show.setAttribute('aria-pressed', String(on));
      show.setAttribute('aria-label', on ? 'Hide password' : 'Show password');
    });
    group.appendChild(show);

    const error = el('div');
    const submit = el('button', 'button button--primary button--block', 'Sign in');
    submit.type = 'submit';
    fields.append(username, passwordField, error, submit);

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      error.replaceChildren();
      submit.disabled = true;
      try {
        await api.auth.login(user.value.trim(), password.value);
      } catch (err) {
        error.replaceChildren(Heim.Callout({ tone: 'danger', body: err.message }));
        password.select();
        return;
      } finally {
        submit.disabled = false;
      }
      await refreshSession();
      restartStatus();
      const target = session.returnTo && !session.returnTo.startsWith('#/login') ? session.returnTo : '#/';
      session.returnTo = null;
      navigate(target);
    });
    queueMicrotask(() => user.focus());
  }

  form.append(brand, fields);
  wrap.append(form, el('p', 'login-footer', 'Heim · self-hosted'));
  main.appendChild(wrap);
  root.appendChild(main);
  return null;
}
