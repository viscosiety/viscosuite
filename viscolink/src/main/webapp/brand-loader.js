// Optional deployment brand for the suite's own pages. A deployment that runs ViscoSuite under its
// own name can make a brand.json reachable at the URL in this script's data-brand-url attribute:
//
//   { "productName": "...", "names": { "link": "...", "store": "...", "suite": "...", "company": "..." },
//     "colors": { "primary": "#rrggbb", "dark": "#rrggbb" } }
//
// When it answers, the page shows those names ([data-product-name], [data-brand-name="<key>"],
// <html data-brand-title="product|<key>">) and colours. When it does not (the usual case), nothing
// changes. Names are set as plain text; colours only when they are #rrggbb.
(function () {
  var script = document.currentScript;
  var url = script && script.getAttribute('data-brand-url');
  if (!url || !window.fetch) return;

  var HEX = /^#[0-9a-fA-F]{6}$/;

  function apply(brand) {
    var names = brand.names || {};
    var product = typeof brand.productName === 'string' ? brand.productName : null;
    if (product) window.__viscoProductName = product;
    document.querySelectorAll('[data-product-name]').forEach(function (el) {
      if (product) el.textContent = product;
    });
    document.querySelectorAll('[data-brand-name]').forEach(function (el) {
      var value = names[el.getAttribute('data-brand-name')];
      if (typeof value === 'string' && value) el.textContent = value;
    });
    var titleKey = document.documentElement.getAttribute('data-brand-title');
    if (titleKey) {
      var titleName = titleKey === 'product' ? product : names[titleKey];
      var prefix = document.documentElement.getAttribute('data-brand-title-prefix') || '';
      if (typeof titleName === 'string' && titleName) document.title = prefix + titleName;
    }
    var colors = brand.colors || {};
    if (HEX.test(colors.primary || '') && HEX.test(colors.dark || '')) {
      var p = colors.primary, d = colors.dark, s = document.documentElement.style;
      s.setProperty('--teal', p);
      s.setProperty('--teal-dark', d);
      s.setProperty('--teal-light', 'color-mix(in srgb, ' + p + ' 12%, white)');
      s.setProperty('--teal-mid', 'color-mix(in srgb, ' + p + ' 38%, white)');
      s.setProperty('--brand-page', d);
      s.setProperty('--brand-card', 'color-mix(in srgb, ' + d + ' 88%, ' + p + ')');
      s.setProperty('--brand-border', 'color-mix(in srgb, ' + d + ' 70%, ' + p + ')');
      s.setProperty('--brand-accent', p);
    }
  }

  fetch(url, { headers: { Accept: 'application/json' } })
    .then(function (response) { return response.ok ? response.json() : null; })
    .then(function (brand) {
      if (!brand || typeof brand !== 'object') return;
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { apply(brand); }, { once: true });
      } else {
        apply(brand);
      }
    })
    .catch(function () { /* no brand: the page stays as it is */ });
})();
