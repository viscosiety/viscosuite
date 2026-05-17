'use strict';

export function parseAdapterFlow(mermaid) {
  const lines = mermaid.split('\n');

  const nodes = {};
  lines.forEach(l => {
    const m = l.match(/\s+(d\d+e\d+)\(.*?data-html-node="b">([^<]+)<\/text>(?:<text>([^<]*)<\/text>)?/);
    if (m) nodes[m[1]] = { name: m[2], hasClass: !!m[3] };
  });

  const sourceIds = new Set();
  lines.forEach(l => { const m = l.match(/^\s*(d\d+e\d+)\s+-->/); if (m) sourceIds.add(m[1]); });
  const isExitId = id => !nodes[id]?.hasClass || !sourceIds.has(id);

  const fwdMap = {};
  lines.forEach(l => {
    const fromM  = l.match(/^\s*(d\d+e\d+)\s+-->/);
    const toM    = l.match(/-->\s+\|.*?\|\s+(d\d+e\d+)/);
    const labelM = l.match(/\|<text>(?:<text>)?([^<]+)/);
    if (!fromM || !toM || !labelM) return;
    const fromNode = nodes[fromM[1]], toNode = nodes[toM[1]];
    if (!fromNode || !toNode) return;
    if (!fwdMap[fromNode.name]) fwdMap[fromNode.name] = [];
    fwdMap[fromNode.name].push({ forwardName: labelM[1].trim(), to: toNode.name, isExit: isExitId(toM[1]) });
  });

  return fwdMap;
}

export function extractAdaptersFromReport(reportName, checkpoints) {
  const seen = new Set();
  const result = [];
  const add = name => {
    const m = name.match(/^([^/]+)\/(.+)$/);
    if (!m || seen.has(name)) return;
    seen.add(name);
    result.push({ config: m[1], adapter: m[2] });
  };
  const rm = (reportName || '').match(/^Pipeline (.+)$/);
  if (rm) add(rm[1]);
  (checkpoints || [])
    .filter(c => c.type === 1 && c.name.startsWith('Pipeline '))
    .forEach(c => add(c.name.slice(9)));
  return result;
}

export function annotateForwardsFromConfig(rows, fwdMap) {
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i];
    if (row.type !== 'pipe') continue;
    const cfgFwds = fwdMap[row.name];
    if (!cfgFwds || cfgFwds.length === 0) continue;

    let nextPipe = null;
    for (let j = i + 1; j < rows.length; j++) {
      if (rows[j].type === 'pipe' && rows[j].level <= row.level) { nextPipe = rows[j]; break; }
    }

    let pipelineExitState = null;
    for (let k = i - 1; k >= 0; k--) {
      if ((rows[k].type === 'pipeline' || rows[k].type === 'context') && rows[k].exitState) {
        pipelineExitState = rows[k].exitState;
        break;
      }
    }

    // SenderPipe wrappers get forwardName=null; inherit from first child row that has one
    let resolvedForwardName = row.forwardName;
    if (!resolvedForwardName) {
      for (let j = i + 1; j < rows.length; j++) {
        if (rows[j].level <= row.level) break;
        if (rows[j].forwardName) { resolvedForwardName = rows[j].forwardName; break; }
      }
    }

    row.forwards = cfgFwds.map(f => {
      let taken = false;
      if (!f.isExit && nextPipe) {
        taken = f.to === nextPipe.name;
      } else if (f.isExit && (!nextPipe || nextPipe.level < row.level)) {
        const exitFwds = cfgFwds.filter(x => x.isExit);
        if (exitFwds.length === 1) {
          taken = true;
        } else {
          const fn  = (resolvedForwardName || '').toLowerCase();
          const fn2 = f.forwardName.toLowerCase();
          const hasExactMatch = exitFwds.some(x => x.forwardName.toLowerCase() === fn);
          if (hasExactMatch) {
            taken = fn2 === fn;
          } else {
            const successLike = fn === 'success' || fn === 'pass' || /^2\d\d$/.test(fn);
            const errorLike   = fn === 'exception' || fn === 'error' || fn === 'failure' || /^[45]\d\d$/.test(fn);
            if (successLike)           taken = fn2 === 'success' || fn2 === 'pass';
            else if (errorLike)        taken = fn2 === 'exception' || fn2 === 'error' || fn2 === 'failure';
            else if (pipelineExitState) taken = f.to.toUpperCase() === pipelineExitState.toUpperCase();
          }
        }
      }
      return { ...f, taken };
    });
  }
}
