/* Módulo Comidas (Alimentación): importar el programa, ver el día, marcar cada comida, foto cuando no se comió lo indicado
 * y "Enviar a Alimentación" (sube a Drive un JSON por comida y la foto). Google y Drive se simulan.
 * Uso: node test-comidas.js <carpeta genesis-gym> <programa-alimentacion.json de prueba> */
const fs = require('fs'), path = require('path'), zlib = require('zlib');
const { preparar, ok, resumen } = require('./servidor');
const { GSI, GAPI } = require('./dobles-google');
const V2 = path.resolve(process.argv[2] || '../genesis-gym');
const PROG = path.resolve(process.argv[3] || path.join(__dirname, 'datos-ejemplo', 'programa-alimentacion-ejemplo.json'));
const espera = ms => new Promise(r => setTimeout(r, ms));

/* Un PNG real de 40x30 (rojo), escrito a mano para no depender de archivos externos. */
function png(w, h) {
  const crc = b => { let c, t = []; for (let n = 0; n < 256; n++) { c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } let x = 0xffffffff; for (const v of b) x = t[(x ^ v) & 0xff] ^ (x >>> 8); return (x ^ 0xffffffff) >>> 0; };
  const chunk = (tipo, datos) => { const l = Buffer.alloc(4); l.writeUInt32BE(datos.length); const td = Buffer.concat([Buffer.from(tipo), datos]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  const filas = []; for (let y = 0; y < h; y++) { filas.push(Buffer.from([0])); filas.push(Buffer.alloc(w * 3, Buffer.from([220, 40, 40]))); }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(Buffer.concat(filas))), chunk('IEND', Buffer.alloc(0))]);
}

(async () => {
  const E = await preparar(V2); const errores = [];
  const prog = JSON.parse(fs.readFileSync(PROG, 'utf8'));
  const tmp = fs.mkdtempSync(path.join(require('os').tmpdir(), 'gg2c-'));
  const fProg = path.join(tmp, 'programa.json'); fs.writeFileSync(fProg, JSON.stringify(prog));
  const malo = JSON.parse(JSON.stringify(prog)); malo.dias[0].comidas[0].preparacion = 'no-existe'; malo.dias[1].fecha = malo.dias[0].fecha;
  const fMalo = path.join(tmp, 'malo.json'); fs.writeFileSync(fMalo, JSON.stringify(malo));
  const fFoto = path.join(tmp, 'plato.png'); fs.writeFileSync(fFoto, png(40, 30));
  const D = prog.dias[0].fecha, pid = prog.perfiles[0].id;

  const ctx = await E.nuevoContexto(); const p = await ctx.newPage(); p.on('pageerror', e => errores.push(e.message));
  // Google simulado: bibliotecas, carpeta y subidas
  const subidas = []; let carpetas = 0, fallaSubida = false;
  await ctx.route('https://accounts.google.com/gsi/client', r => r.fulfill({ contentType: 'text/javascript', body: GSI }));
  await ctx.route('https://apis.google.com/js/api.js', r => r.fulfill({ contentType: 'text/javascript', body: GAPI }));
  const cors = { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'authorization,content-type', 'content-type': 'application/json' };
  await ctx.route(u => u.href.startsWith('https://www.googleapis.com/'), async r => {
    const q = r.request();
    if (q.method() === 'OPTIONS') return r.fulfill({ status: 204, headers: cors });
    if (q.headers()['authorization'] !== 'Bearer tok-prueba') return r.fulfill({ status: 401, headers: cors, body: '{}' });
    const u = q.url();
    if (u.includes('/upload/drive/v3/files')) {
      if (fallaSubida) return r.fulfill({ status: 500, headers: cors, body: '{}' });
      const cuerpo = q.postDataBuffer() || Buffer.alloc(0), txt = cuerpo.toString('latin1');
      const meta = JSON.parse(txt.match(/\{"name"[^\n]*?\}(?=\r?\n)/)[0]);
      const json = meta.mimeType === 'application/json' ? JSON.parse(Buffer.from(txt.slice(txt.lastIndexOf('application/json')).split(/\r?\n\r?\n/)[1].split(/\r?\n--/)[0], 'latin1').toString('utf8')) : null;
      subidas.push({ meta, json, bytes: cuerpo.length });
      return r.fulfill({ status: 200, headers: cors, body: JSON.stringify({ id: 'F' + subidas.length, name: meta.name }) });
    }
    if (u.includes('/drive/v3/files?q=')) return r.fulfill({ status: 200, headers: cors, body: JSON.stringify({ files: carpetas ? [{ id: 'CARPETA', name: 'Genesis Gym - registro comidas' }] : [] }) });
    if (u.startsWith('https://www.googleapis.com/drive/v3/files') && q.method() === 'POST') { carpetas++; return r.fulfill({ status: 200, headers: cors, body: JSON.stringify({ id: 'CARPETA' }) }); }
    r.fulfill({ status: 404, headers: cors, body: '{}' });
  });

  await p.goto(E.url); await p.waitForSelector('nav.tabs');
  await p.waitForFunction(() => navigator.serviceWorker.controller, null, { timeout: 10000 }).catch(() => {});
  ok(await p.locator('nav.tabs button').count() === 5 && await p.isVisible('nav [data-vista="comidas"]'), 'barra inferior con 5 pestañas, incluida Comidas');

  // A. Sin programa
  await p.click('nav [data-vista="comidas"]'); await p.waitForSelector('text=Aún no hay programa');
  ok(await p.isVisible('#com-archivo') && await p.isVisible('[data-drive-programa]'), 'sin programa: ofrece importar desde archivo y desde Drive');

  // B. Programa con errores
  await p.setInputFiles('#com-archivo', fMalo); await p.waitForSelector('text=Programa rechazado');
  const tMalo = await p.textContent('main');
  ok(tMalo.includes('"no-existe" no existe en preparaciones') && tMalo.includes('está repetida'), 'programa con errores: se rechaza completo y lista los errores');

  // C. Programa válido
  await p.setInputFiles('#com-archivo', fProg); await p.waitForSelector('text=Programa válido');
  await p.click('[data-com-activar]'); await p.waitForSelector('text=Programa activado');
  ok(await p.evaluate(async () => !!(await (await GGDB.abrir()).doc('alimentacion/programa_activo').get()).exists), 'el programa queda guardado en el celular');
  await p.evaluate(d => { GGComidas._estado.dia = d; GGRepintarComidas(); }, D); await p.waitForSelector('#com-cena');
  const t1 = await p.textContent('main');
  ok(await p.locator('section[id^="com-"]').count() === 5, 'el día muestra las 5 comidas');
  const d0 = prog.dias[0], prepAlm = prog.preparaciones[d0.comidas.find(c => c.tipo === 'almuerzo').preparacion];
  ok(t1.includes(prepAlm.nombre) && t1.includes(d0.comidas.find(c => c.tipo === 'almuerzo').hora) && t1.includes(prepAlm.porciones[pid][0].alimento), 'cada comida muestra hora, preparación y porciones del programa');
  ok(prog.restricciones.every(r => t1.includes(r)), 'muestra las restricciones del programa');
  if ((d0.tareas || []).length) ok(t1.includes(d0.tareas[0].texto), 'muestra las tareas del día');
  ok(t1.includes(`${d0.total_estimado_kcal[pid]} kcal`) && t1.includes('0 de 5'), 'resumen del día: kcal del plan y 0 de 5 marcadas');

  // D. Marcar "Comí lo indicado"
  await p.click('[data-com-estado="desayuno|indicado"]'); await p.waitForSelector('text=1 de 5');
  const kDes = prog.preparaciones[d0.comidas.find(c => c.tipo === 'desayuno').preparacion].aporte_estimado[pid].kcal;
  ok((await p.textContent('main')).includes(`suma ≈ ${kDes} kcal`), `"Comí lo indicado" suma las kcal estimadas de esa comida (${kDes})`);
  const regDes = await p.evaluate(async k => (await (await GGDB.abrir()).doc('comidas/' + k).get()).data(), D + '_desayuno');
  ok(regDes && regDes.estado === 'indicado', 'el estado queda guardado en el celular');

  // E. "Comí otra cosa": pide foto y nota
  await p.click('[data-com-estado="almuerzo|otro"]'); await p.waitForSelector('#com-almuerzo >> text=Falta la foto');
  ok(await p.isVisible('#com-almuerzo [data-com-elegir-foto="almuerzo"]'), '"Comí otra cosa" pide una foto');
  ok(await p.evaluate(() => { const i = document.getElementById('com-foto-almuerzo'); return i && !i.hasAttribute('capture') && i.accept === 'image/*'; }), 'el selector de foto no fuerza la cámara: Android ofrece cámara o galería');
  await p.click('[data-com-estado="colacion_pm|no-comi"]'); await espera(300);
  ok(!(await p.isVisible('#com-colacion_pm >> text=Falta la foto')), '"No comí" no pide foto');
  await p.click('[data-com-enviar]'); await p.waitForSelector('text=Falta la foto de: Almuerzo');
  ok(subidas.length === 0, 'sin la foto, no envía nada y explica qué falta');
  // Tocar el botón debe abrir el selector del celular (0.7.4: en el S25 el toque no hacía nada)
  const [elegir] = await Promise.all([p.waitForEvent('filechooser', { timeout: 5000 }).catch(() => null), p.click('[data-com-elegir-foto="almuerzo"]')]);
  ok(!!elegir, 'tocar "Tomar o elegir foto" abre el selector del celular');
  // Mientras el selector está abierto, la pantalla se redibuja (como al volver de la cámara en Android): la foto igual llega
  await p.evaluate(() => self.GGRepintarComidas && self.GGRepintarComidas());
  if (elegir) await elegir.setFiles(fFoto); else await p.setInputFiles('#com-foto-almuerzo', fFoto);
  await p.waitForSelector('#com-almuerzo img', { timeout: 8000 }).catch(() => {});
  ok(await p.isVisible('#com-almuerzo img'), 'la foto elegida aparece aunque la pantalla se haya redibujado');
  const foto = await p.evaluate(async k => (await (await GGDB.abrir()).doc('fotos/' + k).get()).data(), D + '_almuerzo');
  ok(foto && /^data:image\/jpeg;base64,/.test(foto.dataUrl), 'la foto queda guardada en el celular, como JPEG reducido');
  await p.fill('#com-nota-almuerzo', 'Media porción de arroz y pollo, más una ensalada'); await p.click('h1'); await espera(400);
  ok((await p.evaluate(async k => (await (await GGDB.abrir()).doc('comidas/' + k).get()).data().nota, D + '_almuerzo')) === 'Media porción de arroz y pollo, más una ensalada', 'la nota queda guardada');

  // F. Enviar a Alimentación
  ok((await p.textContent('[data-com-enviar]')).includes('(3)'), 'hay 3 comidas por enviar');
  await p.click('[data-com-enviar]'); await p.waitForSelector('text=Enviado a Alimentación', { timeout: 10000 }).catch(() => {});
  ok(carpetas === 1, 'crea la carpeta "Genesis Gym - registro comidas" (una sola vez)');
  const nombres = subidas.map(s => s.meta.name).sort();
  ok(nombres.join() === [`comida-${pid}-${D}-almuerzo.jpg`, `comida-${pid}-${D}-almuerzo.json`, `comida-${pid}-${D}-colacion_pm.json`, `comida-${pid}-${D}-desayuno.json`].sort().join(), 'sube un JSON por comida marcada y la foto: ' + nombres.join(', '));
  ok(subidas.every(s => s.meta.parents && s.meta.parents[0] === 'CARPETA'), 'todo queda en esa carpeta');
  const jAlm = subidas.find(s => s.meta.name.endsWith('almuerzo.json')).json;
  ok(jAlm && jAlm.formato === 'registro-comida' && jAlm.estado === 'otro' && jAlm.foto === `comida-${pid}-${D}-almuerzo.jpg` && jAlm.nota.startsWith('Media porción') && jAlm.indicado.nombre === prepAlm.nombre && jAlm.indicado.aporte_estimado.kcal === prepAlm.aporte_estimado[pid].kcal, 'el JSON trae lo indicado (con kcal), lo que pasó, la nota y el nombre de la foto');
  ok(subidas.find(s => s.meta.name.endsWith('.jpg')).bytes > 500, 'la foto se sube completa');
  ok((await p.textContent('main')).includes('Todo lo marcado ya fue enviado') && await p.locator('#com-almuerzo >> text=Enviado').count() === 1, 'después de enviar: nada pendiente y cada comida dice "Enviado"');

  // G. Cambiar algo ya enviado: se vuelve a enviar como versión nueva, sin sobrescribir
  await p.click('[data-com-estado="colacion_pm|indicado"]'); await p.waitForSelector('text=Enviar a Alimentación (1)');
  await p.click('[data-com-enviar]'); await p.waitForSelector('text=Enviado a Alimentación: 1 comida', { timeout: 10000 }).catch(() => {});
  ok(/comida-.+-colacion_pm-v\d+\.json$/.test(subidas[subidas.length - 1].meta.name) && carpetas === 1, 'un cambio después de enviar sube un archivo nuevo (-vN), reutilizando la carpeta');

  // H. Sin internet o con error de Drive: no se pierde nada
  await p.click('[data-com-estado="cena|indicado"]'); await p.waitForSelector('text=Enviar a Alimentación (1)');
  fallaSubida = true; await p.click('[data-com-enviar]'); await p.waitForSelector('text=No se pudo enviar a Drive', { timeout: 10000 }).catch(() => {});
  ok((await p.textContent('main')).includes('Lo marcado queda guardado') && (await p.textContent('[data-com-enviar]')).includes('(1)'), 'si Drive falla, lo explica y la comida sigue pendiente');
  fallaSubida = false;
  await ctx.setOffline(true); await p.click('[data-com-enviar]'); await espera(500);
  ok((await p.textContent('main')).includes('sin internet'), 'sin internet: lo explica');
  await ctx.setOffline(false);

  // I. Navegar días y persistencia
  await p.click('[data-com-dia="1"]'); await espera(200);
  ok((await p.textContent('main')).includes('0 de 5') && await p.isDisabled('[data-com-dia="-1"]') === false, 'el día siguiente empieza sin marcar');
  await p.reload(); await p.waitForSelector('nav.tabs'); await p.click('nav [data-vista="comidas"]');
  await p.waitForFunction(() => GGComidas._estado.cargado, null, { timeout: 8000 });
  await p.evaluate(d => { GGComidas._estado.dia = d; GGRepintarComidas(); }, D); await p.waitForSelector('#com-almuerzo img');
  ok((await p.textContent('main')).includes('4 de 5'), 'al volver a abrir la app, las marcas y la foto siguen ahí');

  // I2. Intercambiar comidas (otro día y mismo día)
  const D1 = prog.dias[1].fecha, D2 = prog.dias[2].fecha;
  const nom = (f, t) => prog.preparaciones[prog.dias.find(x => x.fecha === f).comidas.find(c => c.tipo === t).preparacion].nombre;
  const kc = (f, t) => prog.preparaciones[prog.dias.find(x => x.fecha === f).comidas.find(c => c.tipo === t).preparacion].aporte_estimado[pid].kcal;
  await p.evaluate(d => { GGComidas._estado.dia = d; GGRepintarComidas(); }, D1); await p.waitForSelector('#com-desayuno');
  await p.click('[data-com-intercambiar="' + D1 + '_desayuno"]'); await p.waitForSelector('[data-com-con]');
  ok(await p.locator('[data-com-con^="' + D1 + '_desayuno|"]').count() === prog.dias.reduce((a, d) => a + d.comidas.length, 0) - 1, 'al intercambiar, ofrece todas las demás comidas del programa');
  await p.click('[data-com-con="' + D1 + '_desayuno|' + D2 + '_desayuno"]'); await p.waitForSelector('#com-desayuno >> text=Intercambiada con');
  ok((await p.textContent('#com-desayuno')).includes(nom(D2, 'desayuno')) && (await p.textContent('#com-desayuno')).includes(nom(D1, 'desayuno')), 'el desayuno muestra el plato del otro día y dice cuál iba en el plan original');
  ok((await p.textContent('#com-desayuno')).includes(prog.dias[1].comidas.find(c => c.tipo === 'desayuno').hora), 'la hora de la comida no cambia');
  await p.click('[data-com-dia="1"]'); await p.waitForSelector('#com-desayuno');
  ok((await p.textContent('#com-desayuno')).includes(nom(D1, 'desayuno')) && (await p.textContent('#com-desayuno')).includes('Intercambiada con'), 'el otro día muestra el plato intercambiado de vuelta');
  await p.click('[data-com-dia="-1"]'); await p.waitForSelector('#com-desayuno');
  const ic = await p.evaluate(async () => (await (await GGDB.abrir()).doc('alimentacion/intercambios').get()).data());
  ok(ic && Object.keys(ic.cambios).sort().join() === [D1 + '_desayuno', D2 + '_desayuno'].sort().join(), 'el intercambio queda guardado en el celular');
  await p.click('[data-com-estado="desayuno|indicado"]'); await p.waitForSelector('text=1 de 5');
  const kSum = kc(D2, 'desayuno');
  ok((await p.textContent('main')).includes(`suma ≈ ${kSum} kcal`) && (await p.textContent('main')).includes('con intercambios'), 'las kcal del día usan el plato intercambiado');
  const nAntes = subidas.length;
  await p.click('[data-com-enviar]'); await p.waitForSelector('text=Enviado a Alimentación', { timeout: 10000 }).catch(() => {});
  const jI = subidas.slice(nAntes).find(x => x.meta.name.includes(D1 + '-desayuno'));
  ok(jI && jI.json.indicado.nombre === nom(D2, 'desayuno') && jI.json.intercambio && jI.json.intercambio.con.fecha === D2 && jI.json.intercambio.con.tipo === 'desayuno', 'el JSON para Alimentación dice qué se comió y con qué comida se intercambió');
  // mismo día: almuerzo ↔ cena
  await p.click('[data-com-intercambiar="' + D1 + '_almuerzo"]'); await p.click('[data-com-con="' + D1 + '_almuerzo|' + D1 + '_cena"]'); await p.waitForSelector('#com-cena >> text=Intercambiada con');
  ok((await p.textContent('#com-almuerzo')).includes(nom(D1, 'cena')) && (await p.textContent('#com-cena')).includes(nom(D1, 'almuerzo')), 'almuerzo y cena del mismo día quedan intercambiados');
  await p.click('#com-cena [data-com-deshacer]'); await p.waitForFunction(() => !document.querySelector('#com-cena .note.info'));
  ok((await p.textContent('#com-almuerzo')).includes(nom(D1, 'almuerzo')) && (await p.textContent('#com-cena')).includes(nom(D1, 'cena')), '"Deshacer intercambio" deja ambas como en el plan');
  await p.click('#com-desayuno [data-com-deshacer]'); await p.waitForFunction(() => !document.querySelector('#com-desayuno .note.info'));
  ok((await p.textContent('main')).includes('Enviar a Alimentación (1)'), 'deshacer un intercambio de una comida ya enviada la deja por enviar de nuevo');

  // I3. Todas las combinaciones de tipos (incluidas las colaciones, cuyo tipo lleva guion bajo) se pueden intercambiar y deshacer
  const TIPOS = ['desayuno', 'colacion_am', 'almuerzo', 'colacion_pm', 'cena'];
  await p.evaluate(d => { GGComidas._estado.dia = d; GGRepintarComidas(); }, D1); await p.waitForSelector('#com-cena');
  const fallos = [];
  for (const ta of TIPOS) for (const tb of TIPOS) {
    for (const [fb, mismo] of [[D1, true], [D2, false]]) {
      if (mismo && ta === tb) continue;
      if (nom(D1, ta) === nom(fb, tb)) continue;  // mismo plato: no hay nada que intercambiar
      await p.click('[data-com-intercambiar="' + D1 + '_' + ta + '"]');
      await p.click('[data-com-con="' + D1 + '_' + ta + '|' + fb + '_' + tb + '"]');
      const okSwap = await p.waitForFunction(([t, n]) => { const el = document.querySelector('#com-' + t + ' h2'); return el && el.textContent === n; }, [ta, nom(fb, tb)], { timeout: 3000 }).then(() => true, () => false);
      if (!okSwap) fallos.push(`${ta}↔${tb}${mismo ? '' : ' (otro día)'}`);
      const btn = p.locator('#com-' + ta + ' [data-com-deshacer]');
      if (await btn.count()) { await btn.click(); await p.waitForFunction(t => !document.querySelector('#com-' + t + ' .note.info'), ta, { timeout: 3000 }).catch(() => {}); }
    }
  }
  ok(fallos.length === 0, 'se pueden intercambiar todas las combinaciones de comidas, del mismo día y de otro día' + (fallos.length ? ': fallan ' + fallos.join(', ') : ''));
  ok(Object.keys(await p.evaluate(async () => (await (await GGDB.abrir()).doc('alimentacion/intercambios').get()).data().cambios)).length === 0, 'después de deshacer todo, no queda ningún intercambio');
  await p.click('[data-com-intercambiar="' + D1 + '_colacion_am"]'); await p.click('[data-com-con="' + D1 + '_colacion_am|' + D1 + '_colacion_pm"]');
  await p.waitForSelector('#com-colacion_am >> text=Intercambiada con: Colación PM');
  ok(true, 'la nota de intercambio nombra bien la colación ("Colación PM")');
  await p.click('#com-colacion_am [data-com-deshacer]'); await p.waitForFunction(() => !document.querySelector('#com-colacion_am .note.info'));

  // J. El mismo programa otra vez: aviso informativo
  await p.click('[data-com-ver-import]'); await p.setInputFiles('#com-archivo', fProg); await p.waitForSelector('text=Este programa ya está activo');
  ok(!(await p.isVisible('text=Programa rechazado')), 'el mismo programa otra vez: aviso informativo, no rechazo');

  // K. Las comidas entran en el respaldo
  const docs = await p.evaluate(async () => (await GGDB.todos()).map(d => d.ruta));
  ok(docs.some(r => r.startsWith('comidas/')) && docs.some(r => r.startsWith('fotos/')) && docs.includes('alimentacion/programa_activo'), 'comidas, fotos y programa están en la base local (y por eso en el respaldo)');

  ok(E.externas.length === 0, 'ningún otro pedido a internet' + (E.externas.length ? ': ' + [...new Set(E.externas)].join(', ') : ''));
  ok(errores.length === 0, 'sin errores de JavaScript' + (errores.length ? ': ' + errores.join(' | ') : ''));
  await E.cerrar(); fs.rmSync(tmp, { recursive: true, force: true }); process.exit(resumen());
})();
