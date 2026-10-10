/* Paso 4: migración 1.0 → 2.0 de punta a punta.
 * Uso: node test-paso4.js <carpeta genesis-gym> <carpeta con la exportación de la base 1.0>
 * Genera el archivo de migración en una carpeta temporal (se borra al final; contiene datos personales),
 * lo restaura en un "celular" vacío y verifica que la 2.0 quede idéntica a la 1.0. */
const fs = require('fs'), path = require('path'), os = require('os'), crypto = require('crypto'), { execFileSync } = require('child_process');
const { preparar, ok, resumen } = require('./servidor');
const V2 = path.resolve(process.argv[2] || '../genesis-gym');
const EXPORT = path.resolve(process.argv[3] || path.join(__dirname, 'datos-ejemplo', 'base-1.0'));
// migrar.js vive en herramientas/ (repositorio); en la copia de trabajo puede estar un nivel arriba
const MIGRAR = [path.join(__dirname, '..', 'herramientas', 'migrar.js'), path.join(__dirname, '..', 'migrar.js')].find(f => fs.existsSync(f));
const huella = docs => crypto.createHash('sha256').update(JSON.stringify([...docs].sort((a, b) => a.ruta < b.ruta ? -1 : a.ruta > b.ruta ? 1 : 0).map(d => ({ ruta: d.ruta, data: d.data })))).digest('hex');

(async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'gg2m-'));
  const salida = path.join(tmp, 'migracion-1.0-prueba.json');
  const informe = execFileSync('node', [MIGRAR, EXPORT, salida]).toString();
  const mig = JSON.parse(fs.readFileSync(salida, 'utf8'));
  // Fuente: cada archivo de la exportación
  const fuente = [];
  for (const col of fs.readdirSync(EXPORT)) for (const f of fs.readdirSync(path.join(EXPORT, col))) fuente.push({ ruta: `${col}/${f.slice(0, -5)}`, data: JSON.parse(fs.readFileSync(path.join(EXPORT, col, f), 'utf8')) });
  ok(mig.documentos.length === fuente.length, `migración: ${mig.documentos.length} documentos, igual que la 1.0`);
  ok(huella(fuente) === mig.huella_sha256, 'migración: huella igual a la de la 1.0 (copia exacta, campo por campo)');
  ok(/Totales: \d+\/\d+ series/.test(informe), 'migración: imprime el informe de verificación');
  const nSeries = fuente.filter(d => d.ruta.startsWith('sesiones/')).reduce((a, d) => a + d.data.ejercicios.reduce((b, e) => b + e.series.length, 0), 0);

  const E = await preparar(V2); const errores = [];
  const ctx = await E.nuevoContexto(); const p = await ctx.newPage(); p.on('pageerror', e => errores.push(e.message));
  await p.goto(E.url); await p.waitForSelector('nav.tabs');
  await p.click('nav [data-vista="historial"]'); await p.waitForSelector('#gg-archivo-respaldo');
  await p.setInputFiles('#gg-archivo-respaldo', salida);
  await p.waitForSelector('[data-gg-restaurar]', { timeout: 10000 }).catch(() => {});
  ok((await p.textContent('#gg-respaldo')).includes('migracion-desde-genesis-gym-1.0'), 'restaurar: reconoce el archivo de migración');
  await p.click('[data-gg-restaurar]'); await Promise.all([p.waitForEvent('load'), p.click('[data-gg-restaurar-si]')]);
  await p.waitForSelector('nav.tabs');
  await p.waitForTimeout(1500); // deja que la app agregue las imágenes del catálogo que falten
  const db = await p.evaluate(() => GGDB.todos());
  const enDb = new Map(db.map(d => [d.ruta, JSON.stringify(d.data)]));
  const cat = JSON.parse(fs.readFileSync(path.join(V2, 'imagenes', 'catalogo.json'), 'utf8'));
  const delCatalogo = d => d.ruta.startsWith('imagenes/') && d.ruta.slice(9) in cat.imagenes;
  ok(mig.documentos.filter(d => !delCatalogo(d)).every(d => enDb.get(d.ruta) === JSON.stringify(d.data)), 'restaurar: cada documento de la 1.0 (salvo imágenes del catálogo) queda idéntico en la 2.0');
  ok(mig.documentos.filter(delCatalogo).every(d => JSON.parse(enDb.get(d.ruta)).origen === 'catalogo-2.0'), 'restaurar: las imágenes de la 1.0 de ejercicios del universo se reemplazan por las del catálogo');
  const extra = db.filter(d => !mig.documentos.some(m => m.ruta === d.ruta));
  ok(extra.every(d => d.ruta.startsWith('imagenes/') && d.data.origen === 'catalogo-2.0'), `restaurar: lo único agregado son imágenes del catálogo de la app (${extra.length})`);
  await p.click('nav [data-vista="historial"]'); await p.waitForSelector('[data-abrir]');
  const nSes = fuente.filter(d => d.ruta.startsWith('sesiones/')).length;
  ok((await p.textContent('main')).includes(`${nSes} sesi`), `historial: muestra las ${nSes} sesiones de la 1.0`);
  const [dl] = await Promise.all([p.waitForEvent('download'), p.click('[data-csv]')]);
  const filas = fs.readFileSync(await dl.path(), 'utf8').replace(/^﻿/, '').split('\r\n');
  ok(filas.length === 1 + nSeries, `CSV: ${filas.length - 1} filas = ${nSeries} series de la 1.0`);
  // Imagen en la ficha (si la 1.0 tenía imágenes)
  const conImg = fuente.find(d => d.ruta.startsWith('imagenes/'));
  if (conImg) {
    const id = conImg.ruta.split('/')[1];
    await p.click('nav [data-vista="rutina"]'); await p.waitForSelector(`[data-ficha="${id}"]`).catch(() => {});
    if (await p.isVisible(`[data-ficha="${id}"]`)) {
      await p.click(`[data-ficha="${id}"]`); await p.waitForSelector('#ficha img', { timeout: 5000 }).catch(() => {});
      ok(await p.isVisible('#ficha img'), `ficha: la imagen de "${id}" se ve tras migrar`);
    }
  }
  ok(E.externas.length === 0, 'ningún pedido a internet');
  ok(errores.length === 0, 'sin errores de JavaScript' + (errores.length ? ': ' + errores.join(' | ') : ''));
  await E.cerrar(); fs.rmSync(tmp, { recursive: true, force: true });
  process.exit(resumen());
})().catch(e => { console.error(e); process.exit(2); });
