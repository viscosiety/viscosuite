'use strict';

export const SESSION_META_KEYS = new Set(['cid', 'mid', 'patientId', 'tsReceived']);

export function processCheckpoints(checkpoints) {
  const sessionMeta = {};
  for (const cp of checkpoints) {
    if (cp.type === 4) {
      const bare = cp.name.replace(/^SessionKey /, '');
      if (SESSION_META_KEYS.has(bare)) sessionMeta[bare] = cp.message;
    }
  }

  const pipeStack = [];
  const pplStack  = [];
  const rows      = [];

  for (const cp of checkpoints) {
    if (cp.type === 1 && cp.name.startsWith('Sender ')) {
      if (cp.level === 0) {
        // Root-level Sender report (e.g. scheduled cleanupDatabase) — no Pipeline wrapper
        pplStack.push({ name: cp.name, level: 0, rowIdx: rows.length });
        rows.push({ type: 'context', sessionReads: [], exitState: null });
      }
      const parentPipeRow = cp.level > 0 ? findDirectParent(pipeStack, cp.level, rows) : null;
      pipeStack.push({ name: cp.name, level: cp.level, rowIdx: rows.length });
      rows.push({
        type: 'pipe', name: cp.name.slice(7), level: cp.level === 0 ? 1 : cp.level,
        isSender: true,
        className: parentPipeRow?.senderClassName ?? null,
        configXml: parentPipeRow?.senderConfigXml ?? null,
        input: cp.message ?? null, output: null, aborted: false,
        // Ladybug's own per-checkpoint stub flag (Checkpoint.isStubbed()): the sender call was
        // replaced by a captured stub instead of executed. Set on the start and/or end checkpoint.
        stubbed: !!cp.stubbed,
        params: [], sessionReads: [], sessionWrites: [],
        preserveInput: false, forwardName: null, forwards: null,
      });
    } else if (cp.type === 2 && cp.name.startsWith('Sender ')) {
      const si = findLastIndex(pipeStack, e => e.name === cp.name);
      if (si >= 0) {
        rows[pipeStack[si].rowIdx].output = cp.message ?? null;
        if (cp.stubbed) rows[pipeStack[si].rowIdx].stubbed = true;
        pipeStack.splice(si, 1);
      }

    } else if (cp.type === 1 && cp.name.startsWith('Pipeline ')) {
      const rowIdx = rows.length;
      pplStack.push({ name: cp.name, level: cp.level, rowIdx });
      if (cp.level > 0) {
        rows.push({ type: 'pipeline', name: cp.name.slice(9), level: cp.level, sessionReads: [], exitState: null });
      } else {
        rows.push({ type: 'context', sessionReads: [] });
      }
    } else if (cp.type === 2 && cp.name.startsWith('Pipeline ')) {
      const si = findLastIndex(pplStack, e => e.name === cp.name);
      if (si >= 0) pplStack.splice(si, 1);

    } else if (cp.type === 1 && cp.name.startsWith('Pipe ')) {
      pipeStack.push({ name: cp.name, level: cp.level, rowIdx: rows.length });
      rows.push({
        type: 'pipe', name: cp.name.slice(5), level: cp.level,
        input: cp.message ?? null, output: null, aborted: false,
        params: [], sessionReads: [], sessionWrites: [],
        preserveInput: false, forwardName: null, forwards: null,
      });
    } else if (cp.type === 2 && cp.name.startsWith('Pipe ')) {
      const si = findLastIndex(pipeStack, e => e.name === cp.name);
      if (si >= 0) { rows[pipeStack[si].rowIdx].output = cp.message ?? null; pipeStack.splice(si, 1); }

    } else if (cp.type === 3 && (cp.name.startsWith('Pipe ') || cp.name.startsWith('Sender '))) {
      const si = findLastIndex(pipeStack, e => e.name === cp.name);
      if (si >= 0) {
        rows[pipeStack[si].rowIdx].output  = cp.message ?? null;
        rows[pipeStack[si].rowIdx].aborted = true;
        pipeStack.splice(si, 1);
      }
    } else if (cp.type === 3 && cp.name.startsWith('Pipeline ')) {
      const si = findLastIndex(pplStack, e => e.name === cp.name);
      if (si >= 0) pplStack.splice(si, 1);

    } else if (cp.type === 6 && (cp.name.startsWith('Pipe ') || cp.name.startsWith('Sender ')) && cp.message) {
      const m = String(cp.message).match(/className="([^"]+)"/);
      if (m) {
        const si = findLastIndex(pipeStack, e => e.name === cp.name);
        if (si >= 0) {
          const full = m[1];
          rows[pipeStack[si].rowIdx].className = full.slice(full.lastIndexOf('.') + 1);
          rows[pipeStack[si].rowIdx].configXml = cp.message;
          const sm = String(cp.message).match(/<sender[^>]+className="([^"]+)"/);
          if (sm) {
            rows[pipeStack[si].rowIdx].senderClassName = sm[1].slice(sm[1].lastIndexOf('.') + 1);
            const senderEl = String(cp.message).match(/<sender[\s\S]*?\/>/);
            if (senderEl) rows[pipeStack[si].rowIdx].senderConfigXml = senderEl[0];
          }
        }
      }

    } else if (cp.type === 4 && cp.name.startsWith('Parameter ')) {
      const par = findDirectParent(pipeStack, cp.level, rows);
      if (par) par.params.push({ name: cp.name.slice(10), value: cp.message });

    } else if (cp.type === 4 && cp.name.startsWith('SessionKey ')) {
      const pipePar = findDirectParent(pipeStack, cp.level, rows);
      if (pipePar) {
        pipePar.sessionReads.push({ name: cp.name.slice(11), value: cp.message });
      } else {
        const pplEntry = findLastInStack(pplStack, cp.level);
        if (pplEntry) rows[pplEntry.rowIdx].sessionReads.push({ name: cp.name.slice(11), value: cp.message });
      }

    } else if (cp.type === 5 && cp.name.startsWith('SessionKey ')) {
      const par = findDirectParent(pipeStack, cp.level, rows);
      if (par) par.sessionWrites.push({ name: cp.name.slice(11), value: cp.message });

    } else if (cp.type === 5 && cp.name === 'PreserveInput') {
      const par = findDirectParent(pipeStack, cp.level, rows);
      if (par) par.preserveInput = true;

    } else if (cp.type === 5 && cp.name === 'forwardName') {
      if (pipeStack.length > 0) rows[pipeStack[pipeStack.length - 1].rowIdx].forwardName = cp.message;

    } else if (cp.type === 5 && cp.name === 'exitState') {
      const pplEntry = findLastInStack(pplStack, cp.level);
      if (pplEntry) rows[pplEntry.rowIdx].exitState = cp.message;

    } else if (cp.type === 5 && cp.name !== 'errorMessage') {
      // Sender-style forward: checkpoint name IS the forward taken
      const par = findDirectParent(pipeStack, cp.level, rows);
      if (par && par.forwardName === null) par.forwardName = cp.name;
    }
  }

  return { rows, sessionMeta };
}

export function findDirectParent(stack, childLevel, rows) {
  for (let i = stack.length - 1; i >= 0; i--)
    if (stack[i].level === childLevel - 1) return rows[stack[i].rowIdx];
  return null;
}

export function findLastInStack(stack, childLevel) {
  for (let i = stack.length - 1; i >= 0; i--)
    if (stack[i].level === childLevel - 1) return stack[i];
  return null;
}

export function findLastIndex(arr, pred) {
  for (let i = arr.length - 1; i >= 0; i--) if (pred(arr[i])) return i;
  return -1;
}
