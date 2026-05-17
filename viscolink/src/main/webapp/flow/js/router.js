'use strict';

const handlers = [];

export function onRoute(handler) {
  handlers.push(handler);
}

export function navigate(path) {
  window.location.hash = path;
}

export function currentRoute() {
  return parseHash(window.location.hash);
}

function parseHash(hash) {
  const h = (hash || '').replace(/^#/, '');
  if (!h || h === '/') return { name: 'home', params: {} };
  const m = h.match(/^\/trace\/(\S+)$/);
  if (m) return { name: 'trace', params: { storageId: m[1] } };
  return { name: 'home', params: {} };
}

function dispatch() {
  const route = currentRoute();
  handlers.forEach(fn => fn(route));
}

window.addEventListener('hashchange', dispatch);
