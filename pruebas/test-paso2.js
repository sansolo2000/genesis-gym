/* Paso 2: pantallas de la 1.0 funcionando sobre la base local del celular (IndexedDB real, sin simulaciones).
 * Uso: node test-paso2.js <carpeta genesis-gym> <carpeta genesis-app de la 1.0, solo lectura>
 * La rutina que se importa es el EJEMPLO de la 1.0 (contenido de relleno), nunca la rutina real. */
const fs = require('fs'), path = require('path'), os = require('os'), crypto = require('crypto'), { execFileSync } = require('child_process');
const { preparar, ok, resumen } = require('./servidor');
const V2 = path.resolve(process.argv[2] || '../genesis-gym');
const V1 = path.resolve(process.argv[3] || '../../Ejercicios/genesis-app');
const md5 = f => crypto.createHash('md5').update(fs.readFileSync(f)).digest('hex');
const espera = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  // A. Validador: copia exacta de la 1.0 y sus 20 pruebas
  ok(md5(path.join(V2, 'validador-rutina.js')) === md5(path.join(V1, 'validador-rutina.js')), 'validador idéntico al de la 1.0 (md5)');
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'gg2v-'));
  fs.copyFileSync(path.join(V2, 'validador-rutina.js'), path.join(tmp, 'validador-rutina.js'));
  fs.copyFileSync(path.join(V1, 'test-validador.js'), path.join(tmp, 'test-validador.js'));
  fs.copyFileSync(path.join(V1, 'ejemplo-rutina.json'), path.join(tmp, 'ejemplo-rutina.json'));
  let salida = ''; try { salida = execFileSync('node', ['test-validador.js'], { cwd: tmp }).toString(); } catch (e) { salida = String(e.stdout || e); }
  const pasan = (salida.match(/^PASA/gm) || []).length;
  ok(/Todas las pruebas pasan/.test(salida) && pasan === 20, `las ${pasan} pruebas del validador de la 1.0 pasan`);
  fs.rmSync(tmp, { recursive: true, force: true });

  const E = await preparar(V2);
  const ctx = await E.nuevoContexto(); const page = await ctx.newPage(); let P = page;
  const errores = []; page.on('pageerror', e => errores.push(e.message));
  const db = () => P.evaluate(async () => (await GGDB.todos()).reduce((m, d) => (m[d.ruta] = d.data, m), {}));
  const rutas = async pref => Object.keys(await db()).filter(k => k.startsWith(pref)).sort();

  // B. Primer uso: sin rutina, sin depender de Claude
  await page.goto(E.url); await page.waitForSelector('nav.tabs');
  await page.waitForSelector('text=Aún no hay rutina', { timeout: 15000 }).catch(() => {});
  const txt0 = await page.textContent('main');
  ok(txt0.includes('Aún no hay rutina'), 'primer uso: muestra "Aún no hay rutina"');
  ok(!/Claude/.test(txt0), 'primer uso: no pide abrir la app desde Claude');
  ok(await page.isVisible('.gg-prueba'), 'muestra el aviso "Versión de prueba"');

  // C. Importar la rutina de ejemplo (mismo flujo que la 1.0)
  await page.click('nav [data-vista="importar"]');
  await page.fill('#json-txt', '{ esto no es json');
  await page.click('[data-validar]');
  ok((await page.textContent('main')).includes('no es JSON válido'), 'importar: rechaza texto que no es JSON');
  const ejemplo = fs.readFileSync(path.join(V1, 'ejemplo-rutina.json'), 'utf8');
  // Validar con el cuadro vacío
  await page.fill('#json-txt', ''); await page.click('[data-validar]'); await page.waitForSelector('.note.err');
  const tvac = await page.textContent('main');
  ok(tvac.includes('No hay nada que validar') && !tvac.includes('Devuelve esta lista a Entrenamiento'), 'validar con el cuadro vacío: indica qué hacer sin culpar a la rutina');
  ok((await page.getAttribute('#archivo', 'accept')) === null, 'selector de archivo sin filtro de tipo (deja elegir el .json recibido por WhatsApp)');
  // Archivo vacío (lo que pasa al elegirlo desde WhatsApp en algunos celulares)
  await page.setInputFiles('#archivo', { name: 'rutina-vacia.json', mimeType: 'application/json', buffer: Buffer.alloc(0) });
  await page.waitForSelector('.note.err');
  const tv = await page.textContent('main');
  ok(tv.includes('llegó vacío') && tv.includes('No se pudo leer el archivo') && !tv.includes('Devuelve esta lista a Entrenamiento'), 'importar archivo vacío: explica el problema del archivo sin culpar a la rutina');
  // Archivo válido elegido con el selector
  await page.setInputFiles('#archivo', { name: 'ejemplo-rutina.json', mimeType: 'application/json', buffer: Buffer.from(ejemplo, 'utf8') });
  await page.waitForSelector('text=Rutina válida');
  ok((await page.textContent('main')).includes('Rutina válida'), 'importar desde archivo: el ejemplo se lee y valida');
  await page.fill('#json-txt', ejemplo);
  await page.click('[data-validar]');
  ok((await page.textContent('main')).includes('Rutina válida'), 'importar: el ejemplo de la 1.0 es válido');
  await page.click('[data-activar]'); await page.waitForSelector('.note.ok >> text=Rutina activada');
  const d1 = await db();
  ok(d1['config/rutina_activa'] && d1['config/rutina_activa'].rutina.rutina.id === 'ejemplo-formato', 'importar: rutina activa guardada en la base local');
  ok(!!d1['rutinas/ejemplo-formato_v1'], 'importar: queda archivada como rutinas/ejemplo-formato_v1');

  // D. Registrar series
  await page.click('nav [data-vista="hoy"]');
  await page.click('[data-abrir]');
  await page.waitForSelector('#c-0-0');
  const fechaId = await page.getAttribute('[data-fecha-editar]', 'data-fecha-editar').catch(() => null);
  await page.fill('#c-0-0', '22,5'); await page.fill('#r-0-0', '9');
  await espera(1200);
  ok((await page.textContent('#estado-guardado')).includes('Guardado'), 'registro: indica "Guardado ✓"');
  let ses = Object.entries(await db()).filter(([k]) => k.startsWith('sesiones/'));
  ok(ses.length === 1 && ses[0][1].ejercicios[0].series[0].carga_real_kg === 22.5 && ses[0][1].ejercicios[0].series[0].reps_reales === 9, 'registro: carga 22,5 kg y 9 reps guardadas en la base local');
  const idSes = ses[0][0];
  // copiar lo indicado en la serie 2
  if (await page.isVisible('[data-copiar="0|1"]')) { await page.click('[data-copiar="0|1"]'); await espera(1200); }
  // E. Cierre
  await page.click('[data-cerrar]');
  ok((await page.textContent('main')).includes('indica el RPE'), 'cierre: exige RPE y duración');
  await page.click('[data-rpe="7"]'); await page.fill('#duracion', '45'); await espera(900);
  await page.click('[data-cerrar]'); await espera(900);
  ses = await db();
  ok(ses[idSes].estado === 'cerrada' && ses[idSes].rpe_sesion === 7 && ses[idSes].duracion_min === 45, 'cierre: sesión cerrada con RPE 7 y 45 min');

  // F. Los datos sobreviven al cerrar y abrir la app
  await page.close();
  const page2 = await ctx.newPage(); P = page2; page2.on('pageerror', e => errores.push(e.message));
  await page2.goto(E.url); await page2.waitForSelector('nav.tabs');
  await page2.click('nav [data-vista="historial"]');
  await page2.waitForSelector('[data-abrir]');
  const hist = await page2.textContent('main');
  ok(hist.includes('1 sesión en la base') && hist.includes('Cerrada'), 'reabrir: el historial muestra la sesión cerrada');
  await page2.click('[data-abrir]'); await page2.waitForSelector('#c-0-0');
  ok((await page2.inputValue('#c-0-0')) === '22,5' && (await page2.inputValue('#r-0-0')) === '9', 'reabrir: la serie conserva 22,5 kg × 9');

  // G. Sin conexión
  await page2.reload(); await ctx.setOffline(true); await page2.reload(); await page2.waitForSelector('nav.tabs');
  await page2.click('nav [data-vista="historial"]'); await page2.waitForSelector('[data-abrir]');
  ok((await page2.textContent('main')).includes('1 sesión en la base'), 'sin conexión: historial disponible');
  await page2.click('[data-abrir]'); await page2.waitForSelector('#nota');
  await page2.fill('#nota', 'registrada sin internet'); await espera(1200);
  ok((await db())[idSes].nota === 'registrada sin internet', 'sin conexión: se puede seguir registrando');
  await ctx.setOffline(false);

  // H. CSV para el Evaluador (sin menú Compartir en el navegador de prueba → descarga)
  await page2.click('nav [data-vista="historial"]');
  const [descarga] = await Promise.all([page2.waitForEvent('download', { timeout: 8000 }).catch(() => null), page2.click('[data-csv]')]);
  ok(!!descarga, 'CSV: se descarga un archivo');
  if (descarga) {
    const csv = fs.readFileSync(await descarga.path(), 'utf8');
    const filas = csv.replace(/^﻿/, '').split('\r\n');
    const nSeries = (await db())[idSes].ejercicios.reduce((a, e) => a + e.series.length, 0);
    ok(csv.charCodeAt(0) === 0xFEFF, 'CSV: con BOM (Excel lee bien las tildes)');
    ok(filas[0] === 'fecha;dia_semana;sesion_id;sesion_nombre;rutina_id;rutina_version;estado_sesion;ejercicio_id;ejercicio_nombre;serie_n;serie_tipo;carga_prescrita_kg;carga_indicacion;reps_prescritas;carga_real_kg;reps_reales;seg_reales;rpe_sesion;duracion_sesion_min;nota_sesion', 'CSV: mismas 20 columnas que la 1.0');
    ok(filas.length === 1 + nSeries, `CSV: una fila por serie (${filas.length - 1}/${nSeries})`);
    ok(filas.some(f => f.includes(';22,5;9;')), 'CSV: incluye la serie 22,5 kg × 9');
    ok(/genesis-fuerza-\d{4}-\d{2}-\d{2}\.csv/.test(descarga.suggestedFilename()), 'CSV: nombre genesis-fuerza-AAAA-MM-DD.csv');
  }

  // I. Cambiar fecha (mismos casos que la prueba de la 1.0, contra la base real)
  const p3 = page2;
  await p3.evaluate(async () => {
    const db = await GGDB.abrir();
    const s = await db.doc(Object.keys((await GGDB.todos()).reduce((m, d) => (m[d.ruta] = 1, m), {})).find(k => k.startsWith('sesiones/'))).get();
    const base = s.data();
    for (const [f, dia] of [['2026-10-05', 'lunes'], ['2026-10-03', 'sabado']]) {
      await db.doc('sesiones/' + f + '_' + base.sesion_id).set({ ...base, fecha: f, dia_semana: dia, nota: 'nota original', rpe_sesion: 5 });
    }
    window.__fail = {};
    const orig = db.doc;
    db.doc = ruta => { const d = orig(ruta); const set = d.set, del = d.delete;
      d.set = x => window.__fail.set ? Promise.reject(Object.assign(new Error('x'), { code: 'unavailable' })) : set(x);
      d.delete = () => window.__fail.del ? Promise.reject(Object.assign(new Error('x'), { code: 'unavailable' })) : del();
      return d; };
  });
  const sesId = (await db())[idSes].sesion_id;
  await p3.click('nav [data-vista="historial"]'); await espera(300);
  await p3.click(`[data-abrir="2026-10-05|${sesId}"]`); await p3.waitForSelector('[data-fecha-editar]');
  await p3.click('[data-fecha-editar]');
  await p3.fill('#nueva-fecha', '2030-01-01'); await p3.click('[data-fecha-guardar]'); await espera(100);
  ok((await p3.textContent('.note.err')).includes('futura'), 'cambiar fecha: rechaza fecha futura');
  await p3.fill('#nueva-fecha', '2026-10-03'); await p3.click('[data-fecha-guardar]'); await espera(100);
  ok((await p3.textContent('.note.err')).includes('Ya hay un registro'), 'cambiar fecha: rechaza una fecha con la misma sesión');
  await p3.evaluate(() => window.__fail.set = true);
  await p3.fill('#nueva-fecha', '2026-10-06'); await p3.click('[data-fecha-guardar]'); await espera(300);
  ok((await p3.textContent('.note.err')).includes('La fecha no cambió'), 'cambiar fecha: informa el error de escritura');
  ok((await rutas('sesiones/')).join() === [`sesiones/2026-10-03_${sesId}`, `sesiones/2026-10-05_${sesId}`, idSes].sort().join(), 'cambiar fecha: sin cambios en la base tras el error');
  await p3.evaluate(() => window.__fail.set = false);
  await p3.fill('#nueva-fecha', '2026-10-06'); await p3.click('[data-fecha-guardar]'); await espera(500);
  const r6 = await rutas('sesiones/');
  ok(r6.includes(`sesiones/2026-10-06_${sesId}`) && !r6.includes(`sesiones/2026-10-05_${sesId}`), 'cambiar fecha: el registro pasa del 05 al 06');
  const d6 = (await db())[`sesiones/2026-10-06_${sesId}`];
  ok(d6.dia_semana === 'martes' && d6.nota === 'nota original' && d6.rpe_sesion === 5 && d6.cambios_fecha[0].de === '2026-10-05', 'cambiar fecha: datos conservados y traza cambios_fecha');
  ok((await p3.textContent('#estado-guardado')).includes('Fecha cambiada'), 'cambiar fecha: mensaje de éxito');

  // J. Eliminar un registro
  await p3.click('[data-borrar]'); await p3.click('[data-borrar-si]'); await espera(500);
  ok(!(await rutas('sesiones/')).includes(`sesiones/2026-10-06_${sesId}`), 'eliminar registro: se borra de la base local');

  ok(E.externas.length === 0, 'ningún pedido a internet (fuera de las bibliotecas de Google al abrir Importar)' + (E.externas.length ? ': ' + [...new Set(E.externas)].join(', ') : ''));
  ok(errores.length === 0, 'sin errores de JavaScript' + (errores.length ? ': ' + errores.join(' | ') : ''));
  await E.cerrar(); process.exit(resumen());
})().catch(e => { console.error(e); process.exit(2); });
