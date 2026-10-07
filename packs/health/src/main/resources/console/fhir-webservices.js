(function () {
    var BLOCK_ID  = '__viscofhir_facades__';
    var FOOTER_ID = '__viscofhir_footer__';
    var basePath  = window.location.pathname.split('/iaf/')[0];
    var facadesUrl = basePath + '/iaf/api/fhir-facades';

    var cachedFacades = null;
    var checkTimer    = null;

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // ── Webservices block ────────────────────────────────────────────────────

    function fhirBaseUri(f) {
        return basePath + '/fhir/' + f.fhirVersion.toLowerCase() + '/' + f.facadeName + '/';
    }

    function buildBlock(facades) {
        if (facades.length === 0) {
            var empty = '<tr><td colspan="6" style="color:#888;font-style:italic">No FHIR facades registered</td></tr>';
            return '<div id="' + BLOCK_ID + '" class="card mt-3">'
                + '<div class="card-header"><strong>Available FHIR Facades</strong></div>'
                + '<div class="card-body p-0">'
                + '<table class="table table-sm table-striped mb-0">'
                + '<thead class="thead-light"><tr>'
                + '<th>FHIR Version</th><th>Facade Name</th><th>Resource Type</th><th>Operation</th><th>FHIR Base URI</th><th>Proxy CDR Base URL</th>'
                + '</tr></thead><tbody>' + empty + '</tbody></table></div></div>';
        }

        // Group operations by (fhirVersion, facadeName), preserving server sort order.
        var groups = [];
        var keyIndex = {};
        facades.forEach(function (f) {
            var key = f.fhirVersion + '\x00' + f.facadeName;
            if (keyIndex[key] === undefined) {
                keyIndex[key] = groups.length;
                groups.push([]);
            }
            groups[keyIndex[key]].push(f);
        });

        // Within each group put proxy ops (resourceType === '*') first.
        groups.forEach(function (ops) {
            ops.sort(function (a, b) {
                var ap = a.resourceType === '*' ? 0 : 1;
                var bp = b.resourceType === '*' ? 0 : 1;
                if (ap !== bp) return ap - bp;
                return (a.resourceType + '\x00' + a.operation)
                    .localeCompare(b.resourceType + '\x00' + b.operation);
            });
        });

        var rows = '';
        groups.forEach(function (ops) {
            var f0   = ops[0];
            var span = ops.length;
            var uri  = fhirBaseUri(f0);
            var hasProxy = ops.some(function (o) { return o.resourceType === '*'; });

            ops.forEach(function (f, i) {
                var isIntercept = hasProxy && f.resourceType !== '*';
                rows += '<tr>';

                // Span shared identity/link columns across all ops in this group.
                if (i === 0) {
                    rows += '<td rowspan="' + span + '" style="vertical-align:middle">' + esc(f0.fhirVersion) + '</td>';
                    rows += '<td rowspan="' + span + '" style="vertical-align:middle"><code>' + esc(f0.facadeName) + '</code></td>';
                }

                // Resource type — prefix intercepted ops with an arrow to show they
                // override the catch-all proxy for that specific resource type.
                if (isIntercept) {
                    rows += '<td style="padding-left:1.4em;color:#555">&#8627;&nbsp;' + esc(f.resourceType) + '</td>';
                } else {
                    rows += '<td>' + esc(f.resourceType || '*') + '</td>';
                }
                rows += '<td>' + esc(f.operation) + '</td>';

                if (i === 0) {
                    var metaUri = uri + 'metadata';
                    rows += '<td rowspan="' + span + '" style="vertical-align:middle">'
                        + '<a href="' + esc(uri) + '" target="_blank">' + esc(uri) + '</a>'
                        + '&nbsp;<a href="' + esc(metaUri) + '" target="_blank" title="FHIR Capability Statement" style="color:#888;font-size:0.85em;text-decoration:none">&#9432;</a>'
                        + '</td>';
                    var proxyLink = f0.proxyCdrBaseUrl
                        ? '<a href="' + esc(f0.proxyCdrBaseUrl) + '" target="_blank">' + esc(f0.proxyCdrBaseUrl) + '</a>'
                        : '';
                    rows += '<td rowspan="' + span + '" style="vertical-align:middle">' + proxyLink + '</td>';
                }

                rows += '</tr>';
            });
        });

        return '<div id="' + BLOCK_ID + '" class="card mt-3">'
            + '<div class="card-header"><strong>Available FHIR Facades</strong></div>'
            + '<div class="card-body p-0">'
            + '<table class="table table-sm table-striped mb-0">'
            + '<thead class="thead-light"><tr>'
            + '<th>FHIR Version</th><th>Facade Name</th><th>Resource Type</th><th>Operation</th><th>FHIR Base URI</th><th>Proxy CDR Base URL</th>'
            + '</tr></thead>'
            + '<tbody>' + rows + '</tbody>'
            + '</table>'
            + '</div>'
            + '</div>';
    }

    function inject(wrapper, facades) {
        if (document.getElementById(BLOCK_ID)) return;
        var tmp = document.createElement('div');
        tmp.innerHTML = buildBlock(facades);
        wrapper.appendChild(tmp.firstChild);
    }

    function checkWebservicesBlock() {
        if (window.location.hash !== '#/webservices') return;
        if (document.getElementById(BLOCK_ID)) return;

        var wrapper = document.querySelector('app-webservices .wrapper-content');
        if (!wrapper) return;

        if (cachedFacades !== null) {
            inject(wrapper, cachedFacades);
            return;
        }

        fetch(facadesUrl)
            .then(function (r) { return r.json(); })
            .then(function (data) {
                cachedFacades = data;
                if (window.location.hash !== '#/webservices') return;
                var w = document.querySelector('app-webservices .wrapper-content');
                if (w) inject(w, cachedFacades);
            })
            .catch(function (e) {
                console.warn('[ViscoLink] FHIR facades API unavailable — will not retry:', e);
                cachedFacades = [];
            });
    }

    // ── Footer ───────────────────────────────────────────────────────────────

    function checkFooter() {
        if (document.getElementById(FOOTER_ID)) return;
        // Append inside the footer's first child element so our text stays inline
        // with the existing content. Appending directly to .footer would add a new
        // flex item, causing a line break.
        var target = document.querySelector('.footer > *');
        if (!target) return;
        var span = document.createElement('span');
        span.id = FOOTER_ID;
        span.textContent = ' | Extended for Healthcare Data Purposes by ';
        var a = document.createElement('a');
        a.href = 'https://viscosiety.com';
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
        a.textContent = 'Viscosiety';
        span.appendChild(a);
        target.appendChild(span);
    }

    // ── Shared scheduling ────────────────────────────────────────────────────

    // Debounce: wait for Angular to finish its render burst before checking.
    // MutationObserver fires on every single DOM change; without debouncing we'd
    // run checks dozens of times per Angular change-detection cycle.
    function check() {
        checkTimer = null;
        checkWebservicesBlock();
        checkFooter();
    }

    function scheduleCheck() {
        if (checkTimer !== null) return;
        checkTimer = setTimeout(check, 80);
    }

    // Re-inject whenever our additions go missing (Angular re-renders, navigation).
    new MutationObserver(scheduleCheck).observe(document.body, { childList: true, subtree: true });

    window.addEventListener('hashchange', scheduleCheck);
    document.addEventListener('DOMContentLoaded', scheduleCheck);
})();
