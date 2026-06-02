'use strict';

// Strip /flow/... suffix to get the WAR context root (e.g. /viscolink)
export const BASE = window.location.pathname.replace(/\/flow\/.*$/, '');

export async function getStorage() {
  const r = await fetch(`${BASE}/flow-api/storage`);
  if (!r.ok) throw new Error(r.status);
  return r.json();
}

export async function getTraces({ storage, limit, offset, flowFilter, patientFilter }) {
  const url = new URL(`${BASE}/flow-api/traces`, location.origin);
  url.searchParams.set('storage', storage);
  url.searchParams.set('limit', String(limit));
  url.searchParams.set('offset', String(offset));
  ['storageId', 'endTime', 'duration', 'name', 'flow', 'patientId', 'correlationId', 'status']
    .forEach(n => url.searchParams.append('metadataNames', n));
  if (patientFilter && flowFilter) {
    url.searchParams.set('patientFilter', patientFilter);
    url.searchParams.set('flowFilter', flowFilter);
  } else if (patientFilter) {
    url.searchParams.set('filterHeader', 'patientId');
    url.searchParams.set('filter', patientFilter);
  } else if (flowFilter) {
    url.searchParams.set('filterHeader', 'flow');
    url.searchParams.set('filter', flowFilter);
  } else {
    url.searchParams.set('filterHeader', 'name');
    url.searchParams.set('filter', 'Pipeline');
  }
  const r = await fetch(url);
  if (!r.ok) throw new Error(r.status);
  return r.json();
}

export async function getTraceCount(storage) {
  const url = new URL(`${BASE}/flow-api/trace-count`, location.origin);
  url.searchParams.set('storage', storage);
  const r = await fetch(url);
  if (!r.ok) throw new Error(r.status);
  return r.json();
}

export async function getTrace(storage, storageId) {
  const url = new URL(`${BASE}/flow-api/trace/${encodeURIComponent(storageId)}`, location.origin);
  url.searchParams.set('storage', storage);
  const r = await fetch(url);
  if (!r.ok) throw new Error(r.status);
  return r.json();
}

export async function getAdapterFlow(config, adapter) {
  const url = new URL(`${BASE}/flow-api/adapter-flow`, location.origin);
  url.searchParams.set('config', config);
  url.searchParams.set('adapter', adapter);
  const r = await fetch(url);
  if (!r.ok) throw new Error(r.status);
  return r.text();
}

export async function copyToTest(storage, storageId) {
  const url = new URL(`${BASE}/flow-api/copy-to-test/${encodeURIComponent(storageId)}`, location.origin);
  url.searchParams.set('storage', storage);
  const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  if (!r.ok) throw new Error(r.status);
  const list = await r.json();
  return list[0]; // single report in Test storage, with its new storageId
}

export async function getConfigXml(config) {
  const url = new URL(`${BASE}/flow-api/config-xml`, location.origin);
  url.searchParams.set('config', config);
  const r = await fetch(url);
  if (!r.ok) throw new Error(r.status);
  return r.text();
}
