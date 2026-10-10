/* App instalable: manifest, íconos, caché sin conexión y aviso de versión nueva.
 * Uso: node test-pwa.js <carpeta genesis-gym> */
const fs = require('fs'), path = require('path');
const { preparar, ok, resumen } = require('./servidor');
(async () => {
  const E = await preparar(process.argv[2] || '../genesis-gym');
  const ver = fs.readFileSync(path.join(E.raiz, 'version.js'), 'utf8').match(/GG_VERSION = '([^']+)'/)[1];
  const sw = fs.readFileSync(path.join(E.raiz, 'sw.js'), 'utf8');
  const lista = JSON.parse('[' + sw.match(/const ARCHIVOS = \[([\s\S]*?)\];/)[1].replace(/'/g, '"') + ']');
  const ctx = await E.nuevoContexto(); const page = await ctx.newPage();
  const errores = []; page.on('pageerror', e => errores.push(e.message));

  const man = JSON.parse(fs.readFileSync(path.join(E.raiz, 'manifest.webmanifest'), 'utf8'));
  ok(man.name && man.short_name && man.start_url && man.display === 'standalone', 'manifest completo y en modo standalone');
  ok(man.icons.some(i => i.purpose === 'maskable') && man.icons.some(i => i.sizes === '192x192') && man.icons.some(i => i.sizes === '512x512'), 'manifest con íconos 192, 512 y maskable');
  for (const f of lista) { const r = await page.request.get(E.url + (f === './' ? '' : f)); ok(r.status() === 200, `existe ${f}`); }

  let cargas = 0; page.on('load', () => cargas++);
  await page.goto(E.url);
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.waitForTimeout(2500);
  ok(cargas === 1, `primera apertura: la pantalla no se recarga sola al instalarse (${cargas} carga/s)`);
  await page.waitForSelector('#gg-pie');
  ok((await page.textContent('#gg-pie')).includes(ver), `versión visible en el pie: ${ver}`);
  ok(!(await page.isVisible('#gg-aviso-version')), 'primer uso: aviso de versión nueva oculto');
  const c = await page.evaluate(async v => { const ks = (await caches.keys()).sort(); return { ks, n: (await (await caches.open('genesis-gym-2-v' + v)).keys()).length }; }, ver);
  ok(c.ks.join() === ['genesis-gym-2-imagenes-v3', 'genesis-gym-2-v' + ver].join() && c.n === lista.length, `cachés ${c.ks} · cascarón con ${c.n}/${lista.length} archivos`);
  const nImg = async () => page.evaluate(async () => (await (await caches.open('genesis-gym-2-imagenes-v3')).keys()).length);
  for (let i = 0; i < 100 && (await nImg()) < 568; i++) await page.waitForTimeout(300);
  ok((await nImg()) === 568, `caché de imágenes aparte con ${await nImg()} imágenes`);
  let pedidosImg = 0, pedidosFedb = 0; page.on('request', r => { if (/\/imagenes\/.+\.(svg|png|jpg)$/.test(r.url())) { pedidosImg++; if (r.url().includes('/imagenes/free-exercise-db/')) pedidosFedb++; } });

  await page.reload(); await ctx.setOffline(true); await page.reload();
  await page.waitForSelector('nav.tabs');
  ok(await page.isVisible('nav.tabs'), 'sin conexión: la app abre desde la caché');
  const cargadas = await page.evaluate(async () => { await document.fonts.ready; return [...document.fonts].filter(f => f.status === 'loaded').map(f => f.family.replace(/"/g, '') + ' ' + f.weight); });
  ok(cargadas.includes('Barlow 400') && cargadas.includes('Barlow Condensed 700'), 'sin conexión: fuentes Barlow cargadas desde la app (' + cargadas.join(', ') + ')');
  await ctx.setOffline(false);

  // Simula un celular que tenía el catálogo anterior (sin las 18 fotos de free-exercise-db): al actualizar baja solo esas
  const quitadas = await page.evaluate(async () => { const c = await caches.open('genesis-gym-2-imagenes-v3'); let n = 0; for (const k of await c.keys()) if (k.url.includes('/imagenes/free-exercise-db/')) { await c.delete(k); n++; } return n; });
  ok(quitadas === 18 && (await nImg()) === 550, `celular con el catálogo anterior: 550 imágenes (sin las ${quitadas} fotos nuevas)`);
  const desde = E.servidos.length;
  fs.writeFileSync(path.join(E.raiz, 'version.js'), "self.GG_VERSION = '9.9.9';\n");
  await page.reload();
  ok(!(await page.isVisible('#gg-aviso-version')), 'antes de buscar la versión nueva: aviso oculto');
  await page.evaluate(async () => (await navigator.serviceWorker.getRegistration()).update());
  await page.waitForSelector('#gg-aviso-version:not([hidden])', { timeout: 10000 }).catch(() => {});
  ok(await page.isVisible('#gg-aviso-version'), 'versión nueva: aparece el aviso');
  await Promise.all([page.waitForEvent('load'), page.click('#gg-actualizar')]);
  await page.waitForFunction(() => (document.getElementById('gg-pie') || {}).textContent?.includes('9.9.9'), null, { timeout: 10000 }).catch(() => {});
  ok((await page.textContent('#gg-pie')).includes('9.9.9'), 'versión nueva: tras "Actualizar" queda la nueva');
  ok((await page.evaluate(() => caches.keys())).sort().join() === 'genesis-gym-2-imagenes-v3,genesis-gym-2-v9.9.9', 'versión nueva: se borra la caché vieja del cascarón y se conservan las imágenes');
  for (let i = 0; i < 50 && (await nImg()) < 568; i++) await page.waitForTimeout(200);
  const img = E.servidos.slice(desde).filter(u => /\/imagenes\/.+\.(svg|png|jpg)$/.test(u)); pedidosImg = img.length; pedidosFedb = img.filter(u => u.includes('/imagenes/free-exercise-db/')).length;
  ok((await nImg()) === 568 && pedidosImg === 18 && pedidosFedb === 18, `versión nueva: baja solo las 18 fotos nuevas y no vuelve a bajar las otras 550 (${pedidosImg} pedidos, ${pedidosFedb} de free-exercise-db)`);

  ok(E.externas.length === 0, 'ningún pedido a internet' + (E.externas.length ? ': ' + E.externas.join(', ') : ''));
  ok(errores.length === 0, 'sin errores de JavaScript' + (errores.length ? ': ' + errores.join(' | ') : ''));
  await E.cerrar(); process.exit(resumen());
})().catch(e => { console.error(e); process.exit(2); });
