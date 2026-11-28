// Extend the upstream Webservices view after Angular has rendered its listener rows.
(() => {
    const root = location.pathname.split('/iaf/')[0];
    let groups;
    let loading = false;
    async function update() {
        const heading = [...document.querySelectorAll('h4')].find(h => h.textContent.trim() === 'Available ApiListeners');
        const table = heading?.closest('.ibox')?.querySelector('table');
        if (!table?.tBodies[0] || loading) return;
        if (!groups) {
            loading = true;
            try {
                const response = await fetch(root + '/iaf/api/visco-api-groups');
                groups = new Set(response.ok ? await response.json() : []);
            } catch {
                return;
            } finally {
                loading = false;
            }
        }
        if (!groups.size || !table.isConnected) return;
        const rows = [...table.tBodies[0].rows];
        const values = rows.map(row => row.cells[1]?.textContent.trim().replace(/^\//, '').split('/')[0]);
        const visible = values.some(value => groups.has(value));
        function setCell(row, tag, value) {
            let cell = row.querySelector('[data-visco-group]');
            if (!visible) {
                cell?.remove();
                return;
            }
            if (!cell) {
                cell = document.createElement(tag);
                cell.dataset.viscoGroup = '';
                row.insertBefore(cell, row.cells[3] || null);
            }
            if (cell.textContent !== value) cell.textContent = value;
        }
        setCell(table.tHead.rows[0], 'th', 'Auth group');
        rows.forEach((row, i) => setCell(row, 'td', groups.has(values[i]) ? values[i] : ''));
    }
    new MutationObserver(update).observe(document.body, {childList: true, subtree: true, characterData: true});
    update();
})();
