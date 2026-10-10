/* Imágenes de los ejercicios (universo de Entrenamiento): incluidas en la app, cargadas en la base,
 * visibles en la ficha, con aviso si no están validadas, guardadas para usarse sin internet.
 * Uso: node test-imagenes.js <carpeta genesis-gym> [universo-ejercicios.json] */
const fs = require('fs'), path = require('path');
const { preparar, ok, resumen } = require('./servidor');
const V2 = path.resolve(process.argv[2] || '../genesis-gym');
const UNI = process.argv[3];
const ej = id => ({ id, nombre: 'Prueba ' + id, descripcion: 'd', musculos_principales: ['m'], musculos_secundarios: [], equipo: 'e', pasos: ['a', 'b'], errores_comunes: ['c'] });
/* waitForFunction no espera funciones async (una promesa cuenta como verdadera): se sondea desde Node. */
const esperarHasta = async (p, fn, ms) => { const fin = Date.now() + ms; while (Date.now() < fin) { if (await p.evaluate(fn).catch(() => false)) return true; await new Promise(r => setTimeout(r, 300)); } return false; };
const SINVAL = 'abduccion-cadera-maquina';
const serie = { n: 1, tipo: 'efectiva', reps: 10, carga_kg: 10 };
const rutinaCon = ids => ({ formato: 'genesis-rutina', version_formato: '1.0',
  rutina: { id: 'prueba-imagenes', nombre: 'Prueba de imágenes', version: 1, vigente_desde: '2026-10-07', autor: 'Entrenamiento' },
  sesiones: [{ id: 'sesion-lunes', dia_semana: 'lunes', nombre: 'A', duracion_estimada_min: 30,
    ejercicios: ids.map((id, i) => ({ ejercicio_id: id, orden: i + 1, descanso_seg: 60, series: [serie] })) }],
  ejercicios: ids.map(ej) });
(async () => {
  const E = await preparar(V2); const errores = [];
  const cat = JSON.parse(fs.readFileSync(path.join(V2, 'imagenes', 'catalogo.json'), 'utf8'));
  const ids = Object.keys(cat.imagenes);
  const srcs = [...new Set(Object.values(cat.imagenes).flatMap(m => m.srcs))];
  ok(ids.length === 287 && cat.version === 4 && cat.version_imagenes === 3, `catálogo v${cat.version} con ${ids.length} ejercicios`);
  ok(srcs.length === 568 && srcs.every(s => fs.existsSync(path.join(V2, s))), `existen los ${srcs.length} archivos de imagen`);
  ok(Object.values(cat.imagenes).filter(m => m.validada_entrenamiento).length === 33, '33 validadas por Entrenamiento');
  // free-exercise-db (Coordinador #32): fotos sin modificar, todas en una sola carpeta para poder retirarlas de una vez
  const FEDB = Object.keys(cat.imagenes).filter(k => cat.imagenes[k].fuente === 'free-exercise-db');
  const fedbSrcs = FEDB.flatMap(k => cat.imagenes[k].srcs);
  ok(FEDB.length === 9 && fedbSrcs.length === 18 && fedbSrcs.every(s => s.startsWith('imagenes/free-exercise-db/') && s.endsWith('.jpg')), `free-exercise-db: ${FEDB.length} ejercicios y ${fedbSrcs.length} fotos, todas en imagenes/free-exercise-db/`);
  ok(srcs.filter(s => s.startsWith('imagenes/free-exercise-db/')).length === 18 && fs.readdirSync(path.join(V2, 'imagenes', 'free-exercise-db')).length === 18, 'en la carpeta free-exercise-db/ no hay nada más que esas 18 fotos');
  ok(FEDB.every(k => /yuhonas\/free-exercise-db/.test(cat.imagenes[k].credito) && /Unlicense/.test(cat.imagenes[k].licencia) && /@f00c92c7dcf1216a928a52c3706c7ce8e2f71ed5:exercises\//.test(cat.imagenes[k].url_fuente)), 'free-exercise-db: crédito, licencia declarada y origen exacto (commit fijado) en cada una');
  const cred = fs.readFileSync(path.join(V2, 'imagenes', 'CREDITOS.md'), 'utf8');
  ok(cred.includes('free-exercise-db') && cred.includes('no verificada') && cred.includes('f00c92c7dcf1216a928a52c3706c7ce8e2f71ed5'), 'CREDITOS.md registra free-exercise-db con el riesgo de procedencia');
  const IA = ['dead-bug', 'face-pull-polea', 'pallof-press', 'peso-muerto-rumano-mancuernas', 'press-inclinado-maquina', 'press-maquina-agarre-neutro', 'remo-mancuerna-una-mano'];
  ok(Object.keys(cat.imagenes).filter(k => cat.imagenes[k].licencia === 'generada-ia').sort().join() === IA.join(), 'las 7 imágenes generadas con IA llevan licencia "generada-ia" (Coordinador v15)');
  if (UNI) {
    const u = JSON.parse(fs.readFileSync(UNI, 'utf8'));
    const difs = u.ejercicios.filter(e => { const m = cat.imagenes[e.ejercicio_id]; return !m || m.srcs.join() !== e.imagenes.map(i => i.origen.startsWith('yuhonas/free-exercise-db@') ? 'imagenes/free-exercise-db/' + i.archivo_app.split('/').pop() : i.archivo_app).join() || m.credito !== e.credito || m.validada_entrenamiento !== ((e.validacion && e.validacion.estado || e.validacion) === 'validada'); });
    ok(difs.length === 0, 'catálogo idéntico al universo (ids, archivos, crédito y validación)' + (difs.length ? ': ' + difs.slice(0, 5).map(e => e.ejercicio_id).join(', ') : ''));
  }
  const ctx = await E.nuevoContexto(); const p = await ctx.newPage(); p.on('pageerror', e => errores.push(e.message));
  // Simula una imagen migrada desde la 1.0 antes del primer arranque con el catálogo
  await p.goto(E.url); await p.waitForSelector('nav.tabs');
  await p.evaluate(async () => { const db = await GGDB.abrir(); await db.doc('imagenes/dead-bug').set({ srcs: ['data:image/svg+xml;base64,PHN2Zy8+'], credito: 'migrada' }); await db.doc('imagenes/ejercicio-fuera').set({ srcs: ['data:image/svg+xml;base64,PHN2Zy8+'], credito: 'ajena' }); });
  await p.evaluate(async () => { const db = await GGDB.abrir(); const r = (await db.doc('imagenes/curl-biceps-polea').get()).data(); r.catalogo_version = 1; await db.doc('imagenes/curl-biceps-polea').set(r); });
  await p.reload(); await p.waitForSelector('nav.tabs');
  await esperarHasta(p, async () => (await GGDB.todos()).filter(d => d.ruta.startsWith('imagenes/') && d.data.origen === 'catalogo-2.0' && d.data.catalogo_version === 4).length === 287, 15000);
  const docs = (await p.evaluate(() => GGDB.todos())).filter(d => d.ruta.startsWith('imagenes/'));
  ok(docs.filter(d => d.data.origen === 'catalogo-2.0' && d.data.catalogo_version === 4).length === 287, `las 287 imágenes se cargan en la base local`);
  ok(docs.find(d => d.ruta === 'imagenes/dead-bug').data.credito !== 'migrada', 'el catálogo reemplaza la imagen migrada de un ejercicio del universo');
  ok(docs.find(d => d.ruta === 'imagenes/ejercicio-fuera').data.credito === 'ajena', 'no toca imágenes de ejercicios fuera del catálogo');
  // Importar: aviso para ejercicios fuera del universo
  await p.click('nav [data-vista="importar"]'); await p.fill('#json-txt', JSON.stringify(rutinaCon(['curl-biceps-polea', 'ejercicio-inventado-x']))); await p.click('[data-validar]');
  await p.waitForSelector('text=Rutina válida');
  ok((await p.textContent('main')).includes('no está en el universo de ejercicios de Entrenamiento') && (await p.textContent('main')).includes('ejercicio-inventado-x'), 'importar: avisa el ejercicio que no está en el universo');
  await p.fill('#json-txt', JSON.stringify(rutinaCon(['curl-biceps-polea', 'dead-bug', 'puente-gluteo', SINVAL, 'press-cubano-mancuernas', 'eliptica']))); await p.click('[data-validar]');
  await p.waitForSelector('text=Rutina válida');
  ok(!(await p.textContent('main')).includes('universo de ejercicios'), 'importar: sin aviso si todos están en el universo');
  await p.click('[data-activar]'); await p.waitForSelector('text=Rutina activada');
  const revisar = async (id, n, credito, etiqueta, conAviso) => {
    await p.click('nav [data-vista="rutina"]'); await p.click(`[data-ficha="${id}"]`); await p.waitForSelector('#ficha img');
    await p.waitForFunction(() => [...document.querySelectorAll('#ficha img')].every(e => e.complete), null, { timeout: 8000 }).catch(() => {});
    const imgs = await p.$$eval('#ficha img', els => els.map(e => ({ ok: e.complete && e.naturalWidth > 0, src: e.getAttribute('src') })));
    ok(imgs.length === n && imgs.every(i => i.ok), `${etiqueta}: ${imgs.length} imagen(es) cargadas (${imgs.map(i => i.src).join(', ')})`);
    ok((await p.textContent('#ficha .credito')).includes(credito), `${etiqueta}: muestra el crédito "${credito}"`);
    ok((await p.isVisible('#ficha >> text=pendiente de validar por Entrenamiento')) === conAviso, `${etiqueta}: ${conAviso ? 'con' : 'sin'} aviso de imagen sin validar`);
    await p.click('[data-cerrar-ficha]');
  };
  await revisar('curl-biceps-polea', 2, 'Everkinetic', 'ficha validada', false);
  await revisar('dead-bug', 1, 'Ilustración aportada por Héctor', 'ficha con ilustración aportada', false); await revisar('puente-gluteo', 2, 'Everkinetic', 'puente de glúteo ahora Everkinetic', false);
  await revisar(SINVAL, 2, 'Everkinetic', 'ficha sin validar', true);
  await revisar('press-cubano-mancuernas', 3, 'Everkinetic', 'ficha con 3 imágenes PNG', true);
  await revisar('eliptica', 2, 'free-exercise-db', 'ficha con fotos JPG de free-exercise-db', false);
  // Todas las imágenes guardadas para usarse sin internet
  await esperarHasta(p, async () => (await (await caches.open('genesis-gym-2-imagenes-v3')).keys()).length >= 568, 60000);
  const enCache = await p.evaluate(async () => (await (await caches.open('genesis-gym-2-imagenes-v3')).keys()).length);
  ok(enCache === 568, `caché de imágenes con ${enCache} de 568`);
  await p.reload(); await p.waitForSelector('nav.tabs');
  await p.waitForFunction(() => /imágenes sin internet: 568 de 568/.test(document.getElementById('gg-pie').textContent), null, { timeout: 8000 }).catch(() => {});
  ok(/imágenes sin internet: 568 de 568/.test(await p.textContent('#gg-pie')), 'pie: "imágenes sin internet: 568 de 568"');
  // Sin internet: una imagen nunca abierta antes también se ve
  await ctx.setOffline(true); await p.reload(); await p.waitForSelector('nav.tabs');
  const nuevas = await p.evaluate(async () => { const r = []; for (const s of ['imagenes/zancada-caminando-inicio.svg', 'imagenes/press-cubano-mancuernas-medio.png', 'imagenes/aduccion-cadera-polea-final.svg', 'imagenes/free-exercise-db/gato-cuadrupedia-final.jpg']) { const x = await fetch(s); r.push(x.ok && (await x.blob()).size > 0); } return r; });
  ok(nuevas.every(Boolean), 'sin conexión: se leen imágenes que nunca se abrieron');
  await revisar(SINVAL, 2, 'Everkinetic', 'sin conexión', true);
  await ctx.setOffline(false);
  ok(errores.length === 0, 'sin errores de JavaScript' + (errores.length ? ': ' + errores.join(' | ') : ''));
  ok(E.externas.length === 0, 'ningún pedido a internet (fuera de las bibliotecas de Google al abrir Importar)');
  await E.cerrar(); process.exit(resumen());
})().catch(e => { console.error(e); process.exit(2); });
