/* Paso 3: perfil, respaldo completo y restauración (base real del navegador, sin simulaciones).
 * Uso: node test-paso3.js <carpeta genesis-gym> <carpeta genesis-app de la 1.0, solo lectura>
 * Usa solo la rutina de EJEMPLO de la 1.0 y una rutina inventada para la prueba. */
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const { preparar, ok, resumen } = require('./servidor');
const V2 = path.resolve(process.argv[2] || '../genesis-gym');
const V1 = path.resolve(process.argv[3] || '../../Ejercicios/genesis-app');
const espera = ms => new Promise(r => setTimeout(r, ms));
const huella = docs => crypto.createHash('sha256').update(JSON.stringify([...docs].sort((a, b) => a.ruta < b.ruta ? -1 : a.ruta > b.ruta ? 1 : 0).map(d => ({ ruta: d.ruta, data: d.data })))).digest('hex');

(async () => {
  const E = await preparar(V2);
  const errores = [];
  const abrir = async () => { const ctx = await E.nuevoContexto(); const p = await ctx.newPage(); p.on('pageerror', e => errores.push(e.message)); await p.goto(E.url); await p.waitForSelector('nav.tabs'); return { ctx, p }; };
  const todos = p => p.evaluate(() => GGDB.todos());
  const ejemplo = fs.readFileSync(path.join(V1, 'ejemplo-rutina.json'), 'utf8');

  // 1. Perfil al primer uso
  const A = await abrir(); const p = A.p;
  await p.waitForSelector('#gg-perfil-nombre', { timeout: 10000 }).catch(() => {});
  ok(await p.isVisible('text=¿De quién es este celular?'), 'primer uso: pregunta de quién es el celular');
  await p.type('#gg-perfil-nombre', 'Prueba Génesis'); await p.click('[data-gg-perfil]');
  await p.waitForSelector('text=Aún no tienes respaldo', { timeout: 5000 }).catch(() => {});
  ok((await todos(p)).some(d => d.ruta === 'config/perfil' && d.data.nombre === 'Prueba Génesis'), 'perfil guardado en la base local');
  ok(await p.isVisible('text=Aún no tienes respaldo'), 'Hoy: avisa que aún no hay respaldo');

  // 2. Datos: rutina de ejemplo + una sesión con una serie
  await p.click('nav [data-vista="importar"]'); await p.fill('#json-txt', ejemplo); await p.click('[data-validar]'); await p.click('[data-activar]');
  await p.waitForSelector('text=Rutina activada');
  await p.click('nav [data-vista="hoy"]'); await p.click('[data-abrir]'); await p.waitForSelector('#c-0-0');
  await p.fill('#c-0-0', '17,5'); await p.fill('#r-0-0', '11'); await espera(1200);

  // 3. Crear respaldo
  await p.click('nav [data-vista="historial"]'); await p.waitForSelector('[data-gg-respaldar]');
  const [dl] = await Promise.all([p.waitForEvent('download', { timeout: 8000 }).catch(() => null), p.click('[data-gg-respaldar]')]);
  ok(!!dl, 'respaldo: se genera un archivo');
  const hoy = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Santiago', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  ok(dl && dl.suggestedFilename() === `genesis-respaldo-prueba-genesis-${hoy}.json`, 'respaldo: nombre con perfil y fecha (' + (dl && dl.suggestedFilename()) + ')');
  const ruta = dl && await dl.path();
  const resp = JSON.parse(fs.readFileSync(ruta, 'utf8'));
  ok(resp.formato === 'genesis-respaldo' && resp.version === 1 && resp.perfil === 'Prueba Génesis', 'respaldo: formato, versión y perfil');
  ok(resp.huella_sha256 === huella(resp.documentos), 'respaldo: huella SHA-256 correcta (calculada aparte en Node)');
  const dbA = await todos(p);
  ok(resp.documentos.length === dbA.length - 1 || resp.documentos.length === dbA.length, `respaldo: incluye todos los documentos (${resp.documentos.length})`);
  ok(resp.conteos.sesiones === 1 && resp.conteos.rutinas === 1, 'respaldo: 1 sesión y 1 rutina');
  ok((await p.textContent('#gg-respaldo')).includes('Respaldo creado'), 'respaldo: confirma en pantalla');
  ok(dbA.some(d => d.ruta === 'config/respaldo'), 'respaldo: queda anotada la fecha del último respaldo');
  await p.click('nav [data-vista="hoy"]'); await espera(300);
  ok(!(await p.isVisible('text=Aún no tienes respaldo')), 'Hoy: el aviso de respaldo desaparece');

  // 4. Recordatorio cuando el respaldo tiene más de 7 días
  await p.evaluate(async () => { const db = await GGDB.abrir(); const d = (await db.doc('config/respaldo').get()).data(); d.ultimo = new Date(Date.now() - 10 * 86400000).toISOString(); await db.doc('config/respaldo').set(d); });
  await p.reload(); await p.waitForSelector('text=Tu último respaldo tiene 10 días', { timeout: 8000 }).catch(() => {});
  ok(await p.isVisible('text=Tu último respaldo tiene 10 días'), 'Hoy: recuerda cuando el respaldo tiene más de 7 días');

  // 5. Restaurar en un celular vacío (otro contexto = otro celular)
  const B = await abrir(); const q = B.p;
  await q.click('nav [data-vista="historial"]');
  await q.waitForSelector('#gg-archivo-respaldo');
  await q.setInputFiles('#gg-archivo-respaldo', ruta);
  await q.waitForFunction(() => /Sesiones:\s*1/.test((document.getElementById('gg-respaldo') || {}).textContent || ''), null, { timeout: 10000 }).catch(() => {});
  const prev = await q.textContent('#gg-respaldo');
  ok(prev.includes('Respaldo válido') && prev.includes('Prueba Génesis') && /Sesiones:\s*1/.test(prev), 'restaurar: vista previa con perfil y 1 sesión');
  await q.click('[data-gg-restaurar]'); await q.waitForSelector('[data-gg-restaurar-si]');
  await Promise.all([q.waitForEvent('load'), q.click('[data-gg-restaurar-si]')]);
  await q.waitForSelector('nav.tabs');
  const dbB = await todos(q);
  ok(huella(dbB) === resp.huella_sha256, 'restaurar: la base queda idéntica al respaldo (misma huella)');
  await q.click('nav [data-vista="historial"]'); await q.waitForSelector('[data-abrir]'); await q.click('[data-abrir]'); await q.waitForSelector('#c-0-0');
  ok((await q.inputValue('#c-0-0')) === '17,5' && (await q.inputValue('#r-0-0')) === '11', 'restaurar: la serie 17,5 kg × 11 aparece en el otro celular');

  // 6. Restaurar sobre datos existentes: exige respaldar primero
  const C = await abrir(); const r = C.p;   // celular con datos propios
  await r.click('nav [data-vista="importar"]'); await r.fill('#json-txt', ejemplo); await r.click('[data-validar]'); await r.click('[data-activar]'); await r.waitForSelector('text=Rutina activada');
  await r.click('nav [data-vista="historial"]'); await r.waitForSelector('#gg-archivo-respaldo');
  await r.setInputFiles('#gg-archivo-respaldo', ruta); await r.waitForSelector('text=Respaldo válido');
  ok((await r.textContent('#gg-respaldo')).includes('Antes de reemplazar, crea un respaldo') && !(await r.isVisible('[data-gg-restaurar]')), 'restaurar sobre datos: bloquea hasta respaldar lo actual');
  await Promise.all([r.waitForEvent('download').catch(() => null), r.click('[data-gg-respaldar]')]);
  await r.waitForSelector('[data-gg-restaurar]', { timeout: 5000 }).catch(() => {});
  ok(await r.isVisible('[data-gg-restaurar]'), 'restaurar sobre datos: tras respaldar, se habilita');

  // 7. Archivos inválidos: no se restaura nada
  const antes = huella(await todos(r));
  const tmp = path.join(path.dirname(ruta), 'adulterado.json');
  const adult = JSON.parse(JSON.stringify(resp)); adult.documentos.find(d => d.ruta.startsWith('sesiones/')).data.ejercicios[0].series[0].carga_real_kg = 99;
  fs.writeFileSync(tmp, JSON.stringify(adult));
  await r.setInputFiles('#gg-archivo-respaldo', tmp); await r.waitForSelector('text=La huella del respaldo no coincide');
  ok(!(await r.isVisible('[data-gg-restaurar]')), 'respaldo adulterado: se rechaza por huella');
  await r.setInputFiles('#gg-archivo-respaldo', { name: 'rutina.json', mimeType: 'application/json', buffer: Buffer.from(ejemplo) });
  await r.waitForSelector('text=no es un respaldo de Génesis Gym');
  ok(true, 'rutina elegida por error: avisa que no es un respaldo');
  await r.setInputFiles('#gg-archivo-respaldo', { name: 'vacio.json', mimeType: 'application/json', buffer: Buffer.alloc(0) });
  await r.waitForSelector('text=llegó vacío');
  ok(true, 'archivo vacío: avisa que llegó vacío');
  ok(huella(await todos(r)) === antes, 'archivos inválidos: la base no cambió');

  // 8. Rutina de otra persona
  const otra = JSON.parse(ejemplo); otra.rutina.id = 'fuerza-prueba';
  await r.evaluate(async o => { const db = await GGDB.abrir(); await db.doc('config/rutina_activa').set({ rutina: o, importada_en: 'x' }); }, otra);
  await r.reload(); await r.waitForSelector('nav.tabs');
  const ajena = JSON.parse(ejemplo); ajena.rutina.id = 'fuerza-otra-persona';
  await r.click('nav [data-vista="importar"]'); await r.fill('#json-txt', JSON.stringify(ajena)); await r.click('[data-validar]');
  await r.waitForSelector('text=Rutina válida');
  ok((await r.textContent('main')).includes('¿Es de otra persona?'), 'importar rutina con otro id: avisa "¿Es de otra persona?"');

  ok(E.externas.length === 0, 'ningún pedido a internet (fuera de las bibliotecas de Google al abrir Importar)' + (E.externas.length ? ': ' + [...new Set(E.externas)].join(', ') : ''));
  ok(errores.length === 0, 'sin errores de JavaScript' + (errores.length ? ': ' + errores.join(' | ') : ''));
  await E.cerrar(); process.exit(resumen());
})().catch(e => { console.error(e); process.exit(2); });
