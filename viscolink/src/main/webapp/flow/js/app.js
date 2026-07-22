'use strict';

import { BASE, getStorage, getTraces, getTrace, getAdapterFlow, getConfigXml, copyToTest } from './api.js';
import { onRoute, navigate, currentRoute } from './router.js';
import { processCheckpoints } from './checkpoints.js';
import { parseAdapterFlow, extractAdaptersFromReport, annotateForwardsFromConfig } from './forwards.js';
import {
  esc, shortName, fmtTime, fmtDur, pill, fwdClass,
  formatPayload, prettyXml,
  renderRow, renderTraceRow, metaItem,
} from './render.js';

// ── Constants ─────────────────────────────────────────────────────────────────
const STORAGE_DEFAULT  = 'DatabaseDebugStorage';
const REFRESH_MS       = 10_000;
const INDENT_PX        = 18;
const PAGE_SIZE_INITIAL = 50;
const PAGE_SIZE_MORE    = 50;

// ── State ─────────────────────────────────────────────────────────────────────
let storage      = STORAGE_DEFAULT;
let allTraces    = [];
let selectedId   = null;
let currentOffset = 0;
let lastPageFull = false;
let loadingMore  = false;
let cdTimer      = null;
let cdLeft       = REFRESH_MS / 1000;
let flowFilter   = '';
let patientFilter = '';
let _rows        = [];
let _exitStateMap = {};
let _extensions  = window.__viscoFlowExtensions ?? [];

// Size the action column to the actual number of buttons a row renders: the two base actions
// (rerun + copy-test) plus however many ViscoFlow extension buttons are registered (an extension
// may add e.g. a share button). Driven via a CSS var so the column never clips and never over-reserves —
// the flexible Flow/Pipeline column absorbs the difference. (~27px per button slot + padding.)
const _actionBtnCount = 2 + _extensions.length;
document.documentElement.style.setProperty('--action-w', (27 * _actionBtnCount + 14) + 'px');

// ── Expose window functions for inline onclick handlers ───────────────────────
// Function declarations are hoisted so these assignments work at module top.
window.selectRow      = selectRow;
window.toggleRow      = toggleRow;
window.togglePayload  = togglePayload;
window.showTagDetail  = showTagDetail;
window.copyText       = copyText;
window.copyRowInput   = idx => copyText(_rows[idx]?.input  ?? '');
window.copyRowOutput  = idx => copyText(_rows[idx]?.output ?? '');
window.copyTagValue   = copyTagValue;
window.jumpToExitChip   = jumpToExitChip;
window.jumpToExitSource = jumpToExitSource;
window.jumpToForward    = jumpToForward;
window.loadMoreRows     = loadMoreRows;
window.rerunTrace        = rerunTrace;
window.copyTraceToTest   = copyTraceToTest;
window.triggerExtension  = triggerExtension;

// ── Router ────────────────────────────────────────────────────────────────────
onRoute(handleRoute);

function handleRoute(route) {
  if (route.name === 'trace') {
    const newId = route.params.storageId;
    if (newId === selectedId) return;
    selectedId = newId;
    stopRefresh();
    document.getElementById('dot').classList.add('paused');
    document.getElementById('live-txt').textContent = 'Paused';
    rebuildTable();
    loadDetail(newId);
  } else {
    if (selectedId !== null) {
      selectedId = null;
      document.getElementById('dot').classList.remove('paused');
      document.getElementById('live-txt').textContent = 'Live';
      showEmpty();
      rebuildTable();
      startRefresh();
    }
  }
}

// ── Storage discovery ─────────────────────────────────────────────────────────
async function discoverStorage() {
  try {
    const views = await getStorage();
    const def = Object.values(views).find(v => v.defaultView) ?? Object.values(views)[0];
    if (def?.storageName) storage = def.storageName;
  } catch { /* use STORAGE_DEFAULT */ }
}

// ── Trace list ────────────────────────────────────────────────────────────────
async function fetchTraces(reset = false) {
  if (reset) { currentOffset = 0; allTraces = []; lastPageFull = false; }
  try {
    // The list is always server-side filtered (name~Pipeline by default, or the user's
    // patient/flow filter), so the unfiltered storage count is never a valid denominator.
    // Pagination is driven purely by whether the last page came back full — see updateSentinel.
    const limit = reset ? PAGE_SIZE_INITIAL : Math.max(currentOffset, PAGE_SIZE_INITIAL);
    const page = await getTraces({ storage, limit, offset: 0, flowFilter, patientFilter });
    allTraces     = page;
    currentOffset = page.length;
    lastPageFull  = page.length >= limit;
    rebuildTable();
    updateFlowDropdown();
    updateTraceCount();
  } catch {
    document.getElementById('trace-body').innerHTML =
      `<tr><td colspan="6" class="loading">Could not load traces</td></tr>`;
  }
}

async function loadMoreRows() {
  if (loadingMore) return;
  loadingMore = true;
  updateSentinel();
  try {
    const page = await getTraces({
      storage, limit: PAGE_SIZE_MORE, offset: currentOffset, flowFilter, patientFilter,
    });
    if (page.length > 0) {
      allTraces     = [...allTraces, ...page];
      currentOffset += page.length;
      lastPageFull   = page.length >= PAGE_SIZE_MORE;
      document.getElementById('load-more-sentinel')?.remove();
      document.getElementById('trace-body')
        .insertAdjacentHTML('beforeend', page.map(r => renderTraceRow(r, selectedId, _extensions)).join(''));
      updateTraceCount();
    } else {
      lastPageFull = false;
    }
  } finally {
    loadingMore = false;
    updateSentinel();
  }
}

function rebuildTable() {
  const tbody = document.getElementById('trace-body');
  if (!allTraces.length) {
    tbody.innerHTML = '<tr><td colspan="6" class="loading">No traces</td></tr>';
    return;
  }
  tbody.innerHTML = allTraces.map(r => renderTraceRow(r, selectedId, _extensions)).join('');
  updateSentinel(tbody);
}

function updateTraceCount() {
  const shown = allTraces.length;
  // No reliable total (the storage count is unfiltered); show a "+" when more pages may exist.
  document.getElementById('trace-count').textContent = `${shown}${lastPageFull ? '+' : ''} traces`;
}

function updateSentinel(tbody) {
  tbody = tbody || document.getElementById('trace-body');
  document.getElementById('load-more-sentinel')?.remove();
  // A full last page is the only signal that more may exist on the server, for both the
  // default (name~Pipeline) list and the user-filtered lists. A short page means we are done.
  if (!lastPageFull) return;
  const tr = document.createElement('tr');
  tr.id = 'load-more-sentinel';
  tr.innerHTML = `<td colspan="6" class="load-more-row" title="Load the next page of traces" onclick="loadMoreRows()">${
    loadingMore ? 'Loading…' : '↓ load next page'
  }</td>`;
  tbody.appendChild(tr);
}

function updateFlowDropdown() {
  const sel     = document.getElementById('flow-filter');
  const current = sel.value;
  const flows   = [...new Set(allTraces.map(r => r.flow).filter(Boolean))].sort();
  if (current && !flows.includes(current)) flows.push(current);
  sel.innerHTML = '<option value="">All flows</option>' +
    flows.map(f => `<option value="${esc(f)}"${f === current ? ' selected' : ''}>${esc(f)}</option>`).join('');
}

// ── Row selection ─────────────────────────────────────────────────────────────
function selectRow(id) {
  if (id === selectedId) {
    navigate('#/');
  } else {
    stopRefresh();
    navigate('#/trace/' + id);
  }
}

// ── Toast ─────────────────────────────────────────────────────────────────────
function showToast(msg, type = 'toast-error') {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.className = 'toast ' + type + ' show';
  clearTimeout(el._timer);
  el._timer = setTimeout(() => el.classList.remove('show'), 4000);
}

// ── Replay ────────────────────────────────────────────────────────────────────
async function rerunTrace(storageId) {
  const btn = document.getElementById(`rerun-${storageId}`);
  if (btn) { btn.disabled = true; btn.textContent = '…'; }
  let ok = false;
  try {
    const url = new URL(`${BASE}/flow-api/rerun/${encodeURIComponent(storageId)}`, location.origin);
    url.searchParams.set('storage', storage);
    const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    if (!r.ok) throw new Error(r.status);
    ok = true;
    if (btn) { btn.textContent = '✓'; btn.classList.add('rerun-ok'); }
  } catch {
    if (btn) { btn.textContent = '✗'; btn.classList.add('rerun-err'); }
    showToast('Replay failed', 'toast-error');
  } finally {
    if (btn) btn.disabled = false;
    setTimeout(() => {
      const b = document.getElementById(`rerun-${storageId}`);
      if (b) { b.textContent = '↺'; b.classList.remove('rerun-ok', 'rerun-err'); }
    }, 2000);
    if (ok && selectedId === null) setTimeout(() => fetchTraces(false), 1000);
  }
}

// ── Copy to Ladybug test ──────────────────────────────────────────────────────
async function copyTraceToTest(storageId) {
  const btn = document.getElementById(`copy-test-${storageId}`);
  if (btn) { btn.disabled = true; btn.textContent = '…'; }
  let report = null;
  try {
    report = await copyToTest(storage, storageId);
    if (btn) { btn.textContent = '✓'; btn.classList.add('rerun-ok'); }
  } catch {
    if (btn) { btn.textContent = '✗'; btn.classList.add('rerun-err'); }
    showToast('Copy to test failed', 'toast-error');
  } finally {
    if (btn) btn.disabled = false;
    setTimeout(() => {
      const b = document.getElementById(`copy-test-${storageId}`);
      if (b) { b.textContent = 'T'; b.classList.remove('rerun-ok', 'rerun-err'); }
    }, 2000);
    if (report) openReportInLadybug('Test', report.storageId);
  }
}

// ── Extensions ────────────────────────────────────────────────────────────────
async function triggerExtension(id, storageId) {
  const ext = _extensions.find(e => e.id === id);
  if (!ext) return;
  const btn = document.getElementById(`ext-${id}-${storageId}`);
  if (btn) { btn.disabled = true; btn.textContent = '…'; }
  try {
    // endpoint is a full absolute path supplied by the extension (product-neutral hook):
    // POST to it as-is. ViscoLink must not hardcode any extension's context path.
    const r = await fetch(ext.endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ storageId }),
    });
    if (btn) { btn.textContent = r.ok ? '✓' : '✗'; btn.classList.add(r.ok ? 'rerun-ok' : 'rerun-err'); }
    if (r.ok) {
      // Product-neutral directive: an extension may ask ViscoFlow to open a resulting report in
      // Ladybug by returning { openReport: { storage, storageId } }. ViscoFlow fetches that report
      // and opens it — same as the "copy to test" flow. ViscoLink stays agnostic to the extension.
      let data = null;
      try { data = await r.json(); } catch { /* non-JSON response is fine */ }
      const open = data && data.openReport;
      if (open && open.storageId) {
        openReportInLadybug(open.storage || STORAGE_DEFAULT, open.storageId);
      }
    }
  } catch {
    if (btn) { btn.textContent = '✗'; btn.classList.add('rerun-err'); }
  } finally {
    if (btn) btn.disabled = false;
    setTimeout(() => {
      const b = document.getElementById(`ext-${id}-${storageId}`);
      if (b) { b.textContent = ext.icon; b.classList.remove('rerun-ok', 'rerun-err'); }
    }, 2000);
  }
}

function openReportInLadybug(storageName, storageId) {
  const lbWin = window.open(`${BASE}/iaf/ladybug/`, 'ladybug');
  if (!lbWin) return; // popup blocked

  // Ladybug opens the report by navigating to its (storageName, storageId) route and fetching it
  // itself; it no longer accepts an inline report object over postMessage.
  const send = () => lbWin.postMessage({ action: 'ladybug-openReport', storageName, storageId }, location.origin);

  // Listen for ladybug-ready (fires from Angular ngOnInit on fresh load, or in response to ping)
  const onMsg = (e) => {
    if (e.source === lbWin && e.data?.action === 'ladybug-ready') {
      window.removeEventListener('message', onMsg);
      clearTimeout(cleanup);
      send();
    }
  };
  window.addEventListener('message', onMsg);

  // Ping in case the window was already loaded (no ngOnInit fires again)
  const ping = setTimeout(() => lbWin.postMessage({ action: 'ladybug-ping' }, location.origin), 300);

  // Safety cleanup after 15s
  const cleanup = setTimeout(() => {
    clearTimeout(ping);
    window.removeEventListener('message', onMsg);
  }, 15_000);
}

// ── Detail load ───────────────────────────────────────────────────────────────
async function loadDetail(id) {
  showDetail('<div class="loading">Loading…</div>', '');
  try {
    const data    = await getTrace(storage, id);
    const report  = data.report;
    const adapters = extractAdaptersFromReport(report.name, report.checkpoints);
    const fwdMap  = {};
    const exitStateMap = {};

    const configNames = [...new Set(adapters.map(a => a.config))];
    await Promise.all([
      ...adapters.map(async ({ config, adapter }) => {
        try { Object.assign(fwdMap, parseAdapterFlow(await getAdapterFlow(config, adapter))); }
        catch { /* non-fatal */ }
      }),
      ...configNames.map(async config => {
        try {
          const xml = await getConfigXml(config);
          new DOMParser().parseFromString(xml, 'text/xml')
            .querySelectorAll('Exit[state]')
            .forEach(el => { const s = el.getAttribute('state'); if (s) exitStateMap[s] = s; });
        } catch { /* non-fatal */ }
      }),
    ]);

    renderDetail(report, allTraces.find(x => x.storageId === id), fwdMap, exitStateMap);
  } catch {
    showDetail('<div class="err-msg">Could not load trace detail.</div>', '');
  }
}

// ── Detail render ─────────────────────────────────────────────────────────────
function renderDetail(report, meta, fwdMap = {}, exitStateMap = {}) {
  const { rows, sessionMeta } = processCheckpoints(report.checkpoints ?? []);
  _rows        = rows;
  _exitStateMap = exitStateMap;
  annotateForwardsFromConfig(rows, fwdMap);

  const exitMap = {};
  rows.forEach((row, idx) => {
    if (row.type !== 'pipe' || !row.forwards) return;
    row.forwards.forEach(f => {
      if (!f.isExit) return;
      if (!exitMap[f.to]) exitMap[f.to] = { taken: false, fromRowIdx: -1 };
      if (f.taken) exitMap[f.to] = { taken: true, fromRowIdx: idx };
    });
  });
  Object.keys(exitStateMap).forEach(name => {
    if (!exitMap[name]) exitMap[name] = { taken: false, fromRowIdx: -1 };
  });

  const flow      = meta?.flow || shortName(report.name || '');
  const patientId = sessionMeta.patientId || meta?.patientId || '—';
  const cid       = meta?.correlationId || report.correlationId || sessionMeta.cid || '—';
  const received  = sessionMeta.tsReceived || fmtTime(meta?.endTime) || '—';
  const dur       = report.endTime && report.startTime
    ? fmtDur(String(report.endTime - report.startTime))
    : fmtDur(meta?.duration);

  // Provenance (Axis 2): a replay/synthetic run is recognized by its correlation-id prefix — every
  // replay path bypasses the listener and stamps one (stubbed-run-* from ViscoFlow's StubbedRunner,
  // or <LadybugName>-* from a native Ladybug rerun). Live production messages carry the wire id or none.
  // Read from the correlation id (list metadata carries it even when the trace payload omits it) so old
  // reports (stored before the server-side field existed) are also detected correctly.
  const cidRaw = meta?.correlationId || report.correlationId || sessionMeta.cid || '';
  const isSynthetic = /^(stubbed-run-|ladybug)/i.test(cidRaw);
  const replayBanner = isSynthetic
    ? `<div class="replay-banner" title="Correlation id '${esc(cidRaw)}' marks this as a replay / synthetic run (it did not enter through a listener), not a real production message">⚠ Synthetic — replay, not a live message</div>`
    : '';

  // Stubbed senders (Axis 1, 1-on-1 with Frank!Framework): Ladybug sets Checkpoint.isStubbed() on a
  // sender checkpoint when the outgoing call was replaced by a captured stub instead of executed
  // (Report.addCheckpoint → setStubbed(true)). Independent of provenance: a synthetic replay may run
  // all senders live (no stub badge), and any run that stubbed a sender shows it. We surface the exact
  // per-checkpoint flag the Ladybug report carries.
  const stubbedSenders = rows.filter(r => r.isSender && r.stubbed).map(r => r.name);
  const stubbedBanner = stubbedSenders.length
    ? `<div class="stubbed-banner" title="Ladybug stubbed ${stubbedSenders.length} sender call${stubbedSenders.length > 1 ? 's' : ''} (isStubbed): ${esc(stubbedSenders.join(', '))} — replaced by a captured stub instead of executed">⇥ Stubbed senders: ${esc(stubbedSenders.join(', '))}</div>`
    : '';

  const header = `
    <div class="rh-flow">${esc(flow)}</div>
    <div class="rh-meta">
      ${metaItem('Patient', patientId)}
      ${metaItem('Correlation ID', cid)}
      ${metaItem('Received', received)}
      ${metaItem('Duration', dur)}
      ${metaItem('Pipeline', shortName(report.name || ''))}
    </div>
    ${replayBanner}
    ${stubbedBanner}`;

  let exitsBar = '';
  const exitEntries = Object.entries(exitMap)
    .sort((a, b) => (b[1].taken ? 1 : 0) - (a[1].taken ? 1 : 0));
  if (exitEntries.length > 0) {
    const chips = exitEntries.map(([name, { taken, fromRowIdx }]) => {
      const isErr = exitStateMap[name] !== 'SUCCESS';
      const cls   = taken
        ? (isErr ? 'exit-chip taken-err' : 'exit-chip taken-ok')
        : (isErr ? 'exit-chip skip-err'  : 'exit-chip skip-ok');
      const tip = taken
        ? `title="Jump to the pipe that triggered this exit" `
        : `title="This exit was not taken" `;
      const click = taken
        ? ` onclick="jumpToExitSource(${fromRowIdx})"`
        : ` onclick="jumpToExitChip('${esc(name)}')"`;
      return `<span id="exit-chip-${esc(name)}" ${tip}class="${cls}"${click}>${taken ? '✓' : '–'} ${esc(name)}</span>`;
    }).join('');
    exitsBar = `<div class="exits-bar"><span class="exits-label">Exit Options</span>${chips}</div>`;
  }

  const tree = exitsBar + rows.map((row, i) => renderRow(row, i, _exitStateMap)).join('');
  showDetail(header, tree);
}

// ── Payload panel ─────────────────────────────────────────────────────────────
function toggleRow(idx) {
  const el = document.getElementById(`row-${idx}`);
  if (!el) return;
  if (el.classList.contains('open')) { closeRow(el); return; }
  openRow(el, idx, 'in');
}

function togglePayload(idx, which) {
  const el = document.getElementById(`row-${idx}`);
  if (!el) return;
  if (el.classList.contains('open') && el.dataset.which === which) { closeRow(el); return; }
  openRow(el, idx, which);
}

function openRow(el, idx, which) {
  const row = _rows[idx];
  if (!row) return;
  el.classList.add('open');
  el.dataset.which = which;
  el.querySelector('.toggle').textContent = '▼';
  el.querySelectorAll('.io-btn').forEach(b => b.classList.remove('active'));
  el.querySelectorAll('.sk-tag.active').forEach(t => t.classList.remove('active'));
  el.querySelector(`.${which}-btn`)?.classList.add('active');
  el.querySelector('.payload-panel')?.remove();

  const div = document.createElement('div');
  div.className = 'payload-panel';
  let html = '';

  if (which === 'config') {
    const { text, truncated } = formatPayload(row.configXml || '');
    html = `<div class="payload-label cfg-label payload-label-row"><span>Config XML</span>
              <button class="copy-btn" title="Copy config XML to clipboard" onclick="copyText(_rows[${idx}]?.configXml||'')">Copy</button></div>
            <pre class="payload-pre">${esc(text)}</pre>
            ${truncated ? '<div class="truncated">… truncated</div>' : ''}`;
  } else if (which === 'in') {
    if (row.input != null) {
      const { text, truncated } = formatPayload(row.input);
      html = `<div class="payload-label in-label payload-label-row"><span>Input</span>
                <button class="copy-btn" title="Copy input message to clipboard" onclick="copyRowInput(${idx})">Copy</button></div>
              <pre class="payload-pre">${esc(text)}</pre>
              ${truncated ? '<div class="truncated">… truncated</div>' : ''}`;
    } else {
      html = `<div class="payload-label in-label">Input</div><div class="payload-none">No payload recorded</div>`;
    }
  } else {
    if (row.preserveInput) {
      html += `<div class="preserve-notice">↩ Input preserved — result written to session key below</div>`;
    }
    if (row.output != null) {
      const { text, truncated } = formatPayload(row.output);
      const labelCls  = row.aborted ? 'exc-label' : 'out-label';
      const labelText = row.aborted ? 'Exception' : 'Output';
      html += `<div class="payload-label ${labelCls} payload-label-row"><span>${labelText}</span>
                 <button class="copy-btn" title="Copy output message to clipboard" onclick="copyRowOutput(${idx})">Copy</button></div>
               <pre class="payload-pre">${esc(text)}</pre>
               ${truncated ? '<div class="truncated">… truncated</div>' : ''}`;
    } else if (!row.preserveInput) {
      html += `<div class="payload-label out-label">Output</div><div class="payload-none">No payload recorded</div>`;
    }
  }

  div.innerHTML = html;
  el.appendChild(div);
}

function closeRow(el) {
  el.classList.remove('open');
  delete el.dataset.which;
  delete el.dataset.tagKey;
  const tog = el.querySelector('.toggle');
  if (tog) tog.textContent = '▷';
  el.querySelectorAll('.io-btn').forEach(b => b.classList.remove('active'));
  el.querySelectorAll('.sk-tag.active').forEach(t => t.classList.remove('active'));
  el.querySelector('.payload-panel')?.remove();
}

// ── Tag detail ────────────────────────────────────────────────────────────────
function showTagDetail(rowIdx, kind, itemIdx) {
  const row = _rows[rowIdx];
  if (!row) return;
  const el = document.getElementById(`row-${rowIdx}`);
  if (!el) return;

  const tagKey = `${kind}-${itemIdx}`;
  if (el.classList.contains('open') && el.dataset.which === 'tag' && el.dataset.tagKey === tagKey) {
    closeRow(el); return;
  }

  let item, kindLabel, colorStyle;
  if (kind === 'param')     { item = row.params[itemIdx];        kindLabel = 'Parameter';             colorStyle = 'color:#fab387'; }
  else if (kind === 'read') { item = row.sessionReads[itemIdx];  kindLabel = 'Session Key (read)';    colorStyle = 'color:#60a5fa'; }
  else                      { item = row.sessionWrites[itemIdx]; kindLabel = 'Session Key (written)'; colorStyle = 'color:#cba6f7'; }
  if (!item) return;

  el.classList.add('open');
  el.dataset.which  = 'tag';
  el.dataset.tagKey = tagKey;
  const tog = el.querySelector('.toggle');
  if (tog) tog.textContent = '▼';
  el.querySelectorAll('.io-btn').forEach(b => b.classList.remove('active'));
  el.querySelectorAll('.sk-tag.active').forEach(t => t.classList.remove('active'));
  document.getElementById(`tag-${rowIdx}-${kind}-${itemIdx}`)?.classList.add('active');
  el.querySelector('.payload-panel')?.remove();

  const { text, truncated } = formatPayload(item.value);
  const div = document.createElement('div');
  div.className = 'payload-panel';
  if (row.type === 'pipeline' || row.type === 'context') {
    div.style.marginLeft = (row.level * INDENT_PX) + 'px';
  }
  div.innerHTML =
    `<div class="kv-section-label payload-label-row" style="${colorStyle};border-top:none">` +
      `<span>${esc(kindLabel)}: <strong>${esc(item.name)}</strong></span>` +
      `<button class="copy-btn" title="Copy value to clipboard" onclick="copyTagValue(${rowIdx},'${kind}',${itemIdx})">Copy</button>` +
    `</div>` +
    `<pre class="payload-pre">${esc(text)}</pre>` +
    (truncated ? '<div class="truncated">… truncated</div>' : '');
  el.appendChild(div);
}

function copyTagValue(rowIdx, kind, itemIdx) {
  const row = _rows[rowIdx];
  if (!row) return;
  const item = kind === 'param' ? row.params[itemIdx]
             : kind === 'read'  ? row.sessionReads[itemIdx]
             :                    row.sessionWrites[itemIdx];
  if (item) copyText(item.value ?? '');
}

// ── Navigation helpers ────────────────────────────────────────────────────────
function jumpToExitChip(name) {
  const chip = document.getElementById(`exit-chip-${name}`);
  if (!chip) return;
  chip.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  chip.classList.remove('fwd-highlight'); void chip.offsetWidth;
  chip.classList.add('fwd-highlight');
  setTimeout(() => chip.classList.remove('fwd-highlight'), 1400);
}

function jumpToExitSource(rowIdx) {
  const el = document.getElementById(`row-${rowIdx}`);
  if (!el) return;
  el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  const hdr = el.querySelector('.pipe-row-hdr') || el;
  hdr.classList.remove('fwd-highlight'); void hdr.offsetWidth;
  hdr.classList.add('fwd-highlight');
  setTimeout(() => hdr.classList.remove('fwd-highlight'), 1400);
}

function jumpToForward(fromIdx, targetName) {
  let targetIdx = -1;
  for (let i = fromIdx + 1; i < _rows.length; i++) {
    if (_rows[i].type === 'pipe' && _rows[i].name === targetName) { targetIdx = i; break; }
  }
  if (targetIdx === -1) {
    for (let i = fromIdx - 1; i >= 0; i--) {
      if (_rows[i].type === 'pipe' && _rows[i].name === targetName) { targetIdx = i; break; }
    }
  }
  if (targetIdx === -1) return;
  const el = document.getElementById(`row-${targetIdx}`);
  if (!el) return;
  el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  const hdr = el.querySelector('.pipe-row-hdr') || el;
  hdr.classList.remove('fwd-highlight'); void hdr.offsetWidth;
  hdr.classList.add('fwd-highlight');
  setTimeout(() => hdr.classList.remove('fwd-highlight'), 1400);
}

// ── Copy ──────────────────────────────────────────────────────────────────────
function copyText(text) {
  navigator.clipboard.writeText(text).catch(() => {
    const ta = document.createElement('textarea');
    ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove();
  });
}

// ── Refresh ───────────────────────────────────────────────────────────────────
function startRefresh() {
  stopRefresh();
  cdLeft = REFRESH_MS / 1000;
  updateCountdown();
  cdTimer = setInterval(() => {
    cdLeft--;
    updateCountdown();
    if (cdLeft <= 0) { cdLeft = REFRESH_MS / 1000; fetchTraces(false); }
  }, 1000);
}

function stopRefresh() {
  clearInterval(cdTimer);
  document.getElementById('countdown').textContent = '';
}

function updateCountdown() {
  document.getElementById('countdown').textContent = `${cdLeft}s`;
}

// ── UI helpers ────────────────────────────────────────────────────────────────
function showEmpty() {
  document.getElementById('empty-state').style.display = '';
  document.getElementById('detail').style.display = 'none';
}

function showDetail(headerHtml, treeHtml) {
  document.getElementById('empty-state').style.display = 'none';
  document.getElementById('detail').style.display = 'flex';
  document.getElementById('trace-header').innerHTML = headerHtml;
  document.getElementById('pipeline-tree').innerHTML = treeHtml;
}

// ── Draggable divider ─────────────────────────────────────────────────────────
(function () {
  const divider = document.getElementById('divider');
  const left    = document.getElementById('left');
  let dragging = false, startX = 0, startW = 0;
  divider.addEventListener('mousedown', e => {
    dragging = true; startX = e.clientX; startW = left.offsetWidth;
    divider.classList.add('dragging');
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    e.preventDefault();
  });
  document.addEventListener('mousemove', e => {
    if (!dragging) return;
    left.style.width = Math.max(180, Math.min(700, startW + e.clientX - startX)) + 'px';
  });
  document.addEventListener('mouseup', () => {
    if (!dragging) return;
    dragging = false;
    divider.classList.remove('dragging');
    document.body.style.cursor = '';
    document.body.style.userSelect = '';
    localStorage.setItem('vf-left-width', left.offsetWidth);
  });
  const saved = localStorage.getItem('vf-left-width');
  if (saved) left.style.width = saved + 'px';
}());

// ── Bootstrap ─────────────────────────────────────────────────────────────────
async function init() {
  await discoverStorage();
  await fetchTraces(true);
  startRefresh();

  document.getElementById('flow-filter').addEventListener('change', e => {
    flowFilter = e.target.value; fetchTraces(true);
  });
  const patInput = document.getElementById('patient-filter');
  patInput.addEventListener('keydown', e => {
    if (e.key === 'Enter') { patientFilter = patInput.value.trim(); fetchTraces(true); }
  });
  patInput.addEventListener('blur', () => {
    patientFilter = patInput.value.trim(); fetchTraces(true);
  });
  document.getElementById('table-wrap').addEventListener('scroll', () => {
    const el = document.getElementById('table-wrap');
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 160 && !loadingMore) loadMoreRows();
  });

  // Handle URL hash present on initial load (e.g. bookmarked trace)
  const route = currentRoute();
  if (route.name === 'trace') handleRoute(route);
}

init();
