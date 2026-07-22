'use strict';

const INDENT_PX  = 18;
const MAX_PAYLOAD = 8_000;

// ── Primitive helpers ─────────────────────────────────────────────────────────

export function esc(s) {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

export function shortName(name) {
  const slash = (name || '').lastIndexOf('/');
  if (slash >= 0) return name.slice(slash + 1);
  const space = (name || '').indexOf(' ');
  if (space >= 0) return name.slice(space + 1);
  return name || '';
}

export function fmtTime(ts) {
  if (!ts) return '';
  if (typeof ts === 'number' || /^\d{10,}$/.test(ts)) {
    return new Date(Number(ts)).toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }
  const m = ts.match(/(\d{2}:\d{2}:\d{2})/);
  return m ? m[1] : ts;
}

export function fmtDur(ms) {
  if (!ms) return '';
  const n = Number(ms);
  if (isNaN(n)) return ms;
  return n >= 1000 ? (n / 1000).toFixed(1) + ' s' : n + ' ms';
}

export function pill(status) {
  if (!status) return '<span class="pill pill-run">● –</span>';
  if (status.toLowerCase() === 'success') return '<span class="pill pill-ok">✓</span>';
  return '<span class="pill pill-err">✗</span>';
}

export function fwdClass(name) {
  if (!name) return 'fwd-other';
  const n = name.toLowerCase();
  if (n === 'success' || /^2\d\d$/.test(name)) return 'fwd-ok';
  if (n === 'exception' || n === 'error' || /^[45]\d\d$/.test(name)) return 'fwd-err';
  if (n === 'stub') return 'fwd-stub';
  return 'fwd-other';
}

// ── Payload formatting ────────────────────────────────────────────────────────

export function formatPayload(raw) {
  if (!raw) return { text: '', truncated: false };
  const t = raw.trim();
  let text;
  if (t.startsWith('<')) {
    text = prettyXml(t);
  } else if (t.startsWith('{') || t.startsWith('[')) {
    try { text = JSON.stringify(JSON.parse(t), null, 2); } catch { text = t; }
  } else {
    text = t.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  }
  const truncated = text.length > MAX_PAYLOAD;
  return { text: truncated ? text.slice(0, MAX_PAYLOAD) : text, truncated };
}

export function prettyXml(xml) {
  try {
    const s = xml.replace(/>\s+</g, '><').replace(/^\s*<\?xml[^?]*\?>\s*/, '');
    let out = '', depth = 0, i = 0;
    while (i < s.length) {
      if (s[i] !== '<') {
        const end  = s.indexOf('<', i);
        const text = s.slice(i, end < 0 ? s.length : end).trim();
        if (text) out += text;
        i = end < 0 ? s.length : end;
        continue;
      }
      const close = s.indexOf('>', i);
      if (close < 0) { out += s.slice(i); break; }
      const tag    = s.slice(i, close + 1);
      i = close + 1;
      const isClose = tag.startsWith('</');
      const isSelf  = tag.endsWith('/>');
      const isDecl  = tag.startsWith('<?') || tag.startsWith('<!');
      if (isClose) depth = Math.max(0, depth - 1);
      if (out) out += '\n';
      out += '  '.repeat(depth) + tag;
      if (!isClose && !isSelf && !isDecl) depth++;
    }
    return out.trim() || xml;
  } catch { return xml; }
}

// ── Trace list row ────────────────────────────────────────────────────────────

/**
 * Provenance test (Axis 2): a report whose correlation id carries a replay prefix did NOT enter
 * through a live listener — it is synthetic (viscoFlow StubbedRunner `stubbed-run-*`, or a native
 * Ladybug rerun `<LadybugName>-*`). Mirrors the server-side StubMetadataFieldExtractor and the
 * detail-view banner, and stays correct for old rows whose stored `stubbed` column predates it.
 */
export function isSyntheticCid(cid) {
  return /^(stubbed-run-|ladybug)/i.test(cid || '');
}

export function renderTraceRow(row, selectedId, extensions = []) {
  const id   = row.storageId;
  const flow = row.flow || shortName(row.name || '');
  const pat  = row.patientId || '';
  const synthetic = isSyntheticCid(row.correlationId);
  const cls  = [id === selectedId ? 'selected' : '', synthetic ? 'row-synthetic' : '']
    .filter(Boolean).join(' ');
  const sel  = cls ? ` class="${cls}"` : '';
  const mark = synthetic
    ? `<span class="origin-tag" title="Synthetic — replay / clone; did not enter through a listener">synthetic</span>`
    : '';
  const extBtns = extensions.map(e =>
    `<button id="ext-${esc(e.id)}-${id}" class="action-btn ext-btn" title="${esc(e.tooltip)}" onclick="event.stopPropagation();triggerExtension('${esc(e.id)}','${id}')">${esc(e.icon)}</button>`
  ).join('');
  return `<tr${sel} onclick="selectRow('${id}')">
    <td class="td-status">${pill(row.status)}</td>
    <td class="td-name">
      <span class="flow-name" title="${esc(row.flow || '')}">${esc(flow)}</span>${mark}
      ${row.flow ? `<span class="pipe-name-small">${esc(shortName(row.name || ''))}</span>` : ''}
      ${row.correlationId ? `<span class="row-cid" title="${esc(row.correlationId)}">${esc(row.correlationId)}</span>` : ''}
    </td>
    <td class="td-patient"><span class="patient-id" title="${esc(pat)}">${esc(pat)}</span></td>
    <td class="td-time">${esc(fmtTime(row.endTime))}</td>
    <td class="td-dur">${esc(fmtDur(row.duration))}</td>
    <td class="td-action"><div class="row-actions"><button id="rerun-${id}" class="action-btn rerun-btn" title="Replay trace" onclick="event.stopPropagation();rerunTrace('${id}')">↺</button><button id="copy-test-${id}" class="action-btn" title="Copy to Ladybug test" onclick="event.stopPropagation();copyTraceToTest('${id}')">T</button>${extBtns}</div></td>
  </tr>`;
}

export function metaItem(label, val) {
  return `<div class="meta-item">
    <span class="meta-label">${esc(label)}</span>
    <span class="meta-value">${esc(val)}</span>
  </div>`;
}

// ── Pipeline tree row ─────────────────────────────────────────────────────────

export function renderRow(row, idx, exitStateMap = {}) {
  if (row.type === 'context') {
    if (!row.sessionReads || !row.sessionReads.length) return '';
    const tags = row.sessionReads.map((r, ri) =>
      `<span id="tag-${idx}-read-${ri}" class="sk-tag sk-read clickable" title="View session key: ${esc(r.name)}" onclick="showTagDetail(${idx},'read',${ri})">${esc(r.name)}</span>`
    ).join('');
    return `<div id="row-${idx}"><div class="root-ctx"><span class="sk-label">SessionKey in:</span>${tags}</div></div>`;
  }

  const indent = Math.max(0, (row.level - 1) * INDENT_PX);

  if (row.type === 'pipeline') {
    const skTags = (row.sessionReads || []).map((r, ri) =>
      `<span id="tag-${idx}-read-${ri}" class="sk-tag sk-read clickable" title="View session key: ${esc(r.name)}" onclick="showTagDetail(${idx},'read',${ri})">${esc(r.name)}</span>`
    ).join('');
    const ctxLine  = skTags
      ? `<div class="session-ctx" style="margin-left:${row.level * INDENT_PX}px"><span class="sk-label">SessionKey in:</span>${skTags}</div>`
      : '';
    const exitBadge = row.exitState
      ? `<span class="fwd-badge ${fwdClass(row.exitState)}">${esc(row.exitState)}</span>`
      : '';
    return `<div id="row-${idx}"><div class="section-hdr" style="margin-left:${indent}px">
      <span class="arrow">↳</span>${esc(row.name)} ${exitBadge}
    </div>${ctxLine}</div>`;
  }

  // type === 'pipe'
  const inBtn  = `<button class="io-btn in-btn" title="Show message entering this pipe" onclick="togglePayload(${idx},'in')">▷ IN</button>`;
  const outBtn = row.aborted
    ? `<button class="io-btn abort-out-btn" title="Show exception thrown by this pipe" onclick="togglePayload(${idx},'out')">⚠ EXC</button>`
    : `<button class="io-btn out-btn" title="Show message leaving this pipe" onclick="togglePayload(${idx},'out')">▶ OUT</button>`;
  const cfgBtn = row.configXml
    ? `<button class="io-btn cfg-btn" onclick="togglePayload(${idx},'config')" title="Pipe config XML">&lt;/&gt;</button>`
    : '';
  const preserveBadge = row.preserveInput ? `<span class="preserve-badge">↩ pass</span>` : '';

  const pTags = (row.params        || []).map((p, pi) => `<span id="tag-${idx}-param-${pi}" class="sk-tag sk-param clickable" title="View parameter value: ${esc(p.name)}" onclick="showTagDetail(${idx},'param',${pi})">${esc(p.name)}</span>`).join('');
  const rTags = (row.sessionReads  || []).map((r, ri) => `<span id="tag-${idx}-read-${ri}"  class="sk-tag sk-read  clickable" title="View session key read by this pipe: ${esc(r.name)}" onclick="showTagDetail(${idx},'read',${ri})">${esc(r.name)}</span>`).join('');
  const wTags = (row.sessionWrites || []).map((s, si) => `<span id="tag-${idx}-write-${si}" class="sk-tag sk-write clickable" title="View session key written by this pipe: ${esc(s.name)}" onclick="showTagDetail(${idx},'write',${si})">${esc(s.name)}</span>`).join('');
  const paramsGroup = pTags ? `<span class="sk-group"><span class="sk-label">Param:</span>${pTags}</span>` : '';
  const readsGroup  = rTags ? `<span class="sk-group"><span class="sk-label">SessionKey in:</span>${rTags}</span>` : '';
  const writesGroup = wTags ? `<span class="sk-group"><span class="sk-label">SessionKey out:</span>${wTags}</span>` : '';
  const metaLine = (paramsGroup || readsGroup || writesGroup)
    ? `<div class="pipe-meta">${paramsGroup}${readsGroup}${writesGroup}</div>` : '';

  const httpBadge = row.forwardName && (/^\d{3}$/.test(row.forwardName) || !row.forwards?.length)
    ? `<span class="fwd-badge ${fwdClass(row.forwardName)}">→ ${esc(row.forwardName)}</span>` : '';

  let forwardsLine = '';
  if (row.forwards && row.forwards.length > 0 &&
      (row.forwards.length > 1 || row.forwards.some(f => f.isExit))) {
    const anyTaken = row.forwards.some(f => f.taken);
    const items = [...row.forwards]
      .sort((a, b) => (b.taken ? 1 : 0) - (a.taken ? 1 : 0))
      .map(f => {
        let cls;
        if (f.isExit) {
          const isErrExit = exitStateMap[f.to] !== 'SUCCESS';
          cls = isErrExit
            ? (f.taken ? 'fwd-route taken-err' : 'fwd-route skip-err')
            : (f.taken ? 'fwd-route taken'     : 'fwd-route skip');
        } else {
          cls = anyTaken ? (f.taken ? 'fwd-route taken' : 'fwd-route skip') : 'fwd-route skip';
        }
        const icon = anyTaken ? (f.taken ? '✓' : '–') : '?';
        const dest  = f.isExit ? `<span class="fwd-rt-exit">${esc(f.to)}</span>` : esc(f.to);
        const tip = f.isExit
          ? `title="Jump to exit state: ${esc(f.to)}" `
          : `title="Jump to pipe: ${esc(f.to)}" `;
        const click = f.isExit
          ? ` ${tip}class="${cls}" onclick="jumpToExitChip('${esc(f.to)}')"`
          : ` ${tip}class="${cls}" onclick="jumpToForward(${idx},'${esc(f.to)}')"`;
        return `<span${click}>${icon} ${esc(f.forwardName)} → ${dest}</span>`;
      }).join('');
    forwardsLine = `<div class="pipe-forwards">${items}</div>`;
  }

  const senderClass = row.isSender ? ' sender-row' : '';
  const abortClass  = row.aborted  ? ' abort-row'  : '';
  const stubClass   = row.stubbed  ? ' stubbed-row' : '';
  const stubBadge   = row.stubbed
    ? `<span class="pipe-stub-badge" title="Ladybug stubbed this sender call (isStubbed): output came from a captured stub, the sender was not executed">stubbed</span>`
    : '';
  return `<div class="pipe-row${senderClass}${abortClass}${stubClass}" id="row-${idx}" style="margin-left:${indent}px">
    <div class="pipe-row-hdr">
      <button class="toggle" title="Expand / collapse pipe details" onclick="toggleRow(${idx})">▷</button>
      ${row.isSender ? '<span class="sender-icon">↗</span>' : ''}
      <div class="pipe-name-col">
        <span class="pname" title="${esc(row.name)}">${esc(row.name)}</span>
        ${row.className ? `<span class="pclass">${esc(row.className)}</span>` : ''}
      </div>
      ${stubBadge}
      ${preserveBadge}
      ${httpBadge}
      <div class="io-btns">${inBtn}${outBtn}${cfgBtn}</div>
    </div>
    ${metaLine}
    ${forwardsLine}
  </div>`;
}
