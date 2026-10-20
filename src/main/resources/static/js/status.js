// Live probe status. Prefers the SSE stream; falls back to polling GET /api/status every 30 s.
// If the status API does not exist (404) everything stays silent and no dots are shown.
//
// Subscribers get (state, change): state = { links: {linkId: LinkStatus}, services: {serviceId: state} },
// change = { serviceId, linkId } for a single update or null after a full snapshot.

import { api } from './api.js';

const POLL_MS = 30_000;
const SSE_RETRY_POLLS = 10; // try the stream again after this many polls

let state = { links: {}, services: {} };
const subscribers = new Set();
let source = null;
let pollTimer = null;
let polls = 0;
let running = false;

export function getStatus() {
  return state;
}

/** Subscribes to status changes; the callback runs immediately with the current state. */
export function onStatus(fn) {
  subscribers.add(fn);
  fn(state, null);
  return () => subscribers.delete(fn);
}

function emit(change) {
  subscribers.forEach((fn) => fn(state, change));
}

function applySnapshot(snapshot) {
  state = { links: snapshot?.links || {}, services: snapshot?.services || {} };
  emit(null);
}

function applyUpdate(update) {
  if (!update) return;
  if (update.linkId) state.links[update.linkId] = update.link;
  if (update.serviceId) state.services[update.serviceId] = update.service;
  emit({ serviceId: update.serviceId, linkId: update.linkId });
}

function parse(event) {
  try { return JSON.parse(event.data); } catch { return null; }
}

function openStream() {
  if (!('EventSource' in window)) return startPolling();
  source = new EventSource(api.status.streamUrl);
  source.addEventListener('snapshot', (e) => applySnapshot(parse(e)));
  source.addEventListener('update', (e) => applyUpdate(parse(e)));
  source.onerror = () => {
    // EventSource would retry by itself, but a 404/401 never recovers; poll instead.
    source.close();
    source = null;
    startPolling();
  };
}

function startPolling() {
  if (!running || pollTimer) return;
  polls = 0;
  const tick = async () => {
    pollTimer = null;
    if (!running) return;
    try {
      const { data } = await api.status.get();
      applySnapshot(data);
    } catch (e) {
      if (e.notFound) return; // status API not available: give up quietly
    }
    if (++polls >= SSE_RETRY_POLLS) return openStream();
    pollTimer = setTimeout(tick, POLL_MS);
  };
  tick();
}

export function startStatus() {
  if (running) return;
  running = true;
  openStream();
}

export function stopStatus() {
  running = false;
  if (source) source.close();
  source = null;
  clearTimeout(pollTimer);
  pollTimer = null;
}

/** Restart, e.g. after login when the stream previously failed with 401. */
export function restartStatus() {
  stopStatus();
  startStatus();
}
