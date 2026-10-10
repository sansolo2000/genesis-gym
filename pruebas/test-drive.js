/* Importar la rutina desde Google Drive (permiso mínimo drive.file).
 * Google se simula: las bibliotecas de Google y la API de Drive se reemplazan por dobles de prueba,
 * así se prueba el flujo de la app sin cuentas reales. La prueba en Google real se hace en el celular.
 * Uso: node test-drive.js <carpeta genesis-gym> <carpeta genesis-app de la 1.0, solo lectura> */
const fs = require('fs'), path = require('path');
const { preparar, ok, resumen } = require('./servidor');
const V2 = path.resolve(process.argv[2] || '../genesis-gym');
const V1 = path.resolve(process.argv[3] || '../../Ejercicios/genesis-app');
// Se usa el ejemplo de la 1.0 con otro rutina.id: la app trata distinto a la rutina de ejemplo.
const RUTINA = (() => { const o = JSON.parse(fs.readFileSync(path.join(V1, 'ejemplo-rutina.json'), 'utf8')); o.rutina.id = 'prueba-drive'; return JSON.stringify(o, null, 2); })();

const { GSI, GAPI } = require('./dobles-google');

(async () => {
  const E = await preparar(V2); const errores = [];
  const espera = ms => new Promise(r => setTimeout(r, ms));

  // A. Sin configurar (por ejemplo, una copia sin los datos de Google): botón desactivado y cero pedidos a Google
  const configReal = fs.readFileSync(path.join(E.raiz, 'google-config.js'), 'utf8');
  fs.writeFileSync(path.join(E.raiz, 'google-config.js'), "self.GG_GOOGLE = { clientId: '', apiKey: '', appId: '' };\n");
  let ctx = await E.nuevoContexto(); let p = await ctx.newPage(); p.on('pageerror', e => errores.push(e.message));
  await p.goto(E.url); await p.waitForSelector('nav.tabs'); await p.waitForFunction(() => navigator.serviceWorker.controller, null, { timeout: 10000 }).catch(() => {});
  await p.click('nav [data-vista="importar"]'); await p.waitForSelector('[data-drive-off]');
  ok(await p.isDisabled('[data-drive-off]'), 'sin configurar: "Buscar en Drive" aparece desactivado');
  await espera(500);
  ok(E.externas.length === 0 && E.google.length === 0, `sin configurar: ningún pedido a Google ni a internet (${E.google.length + E.externas.length})`);
  await ctx.close();

  // B. Configurado, con Google simulado
  ok(/apps\.googleusercontent\.com/.test(configReal) && /apiKey: 'AIza/.test(configReal) && /appId: '\d+'/.test(configReal) && /carpetaRutinas: '1U6dOaWpjtAuMBKyzDtHkJzyUrBXSiCFa'/.test(configReal), 'la app publicada trae ID de cliente, clave y número de proyecto');
  fs.writeFileSync(path.join(E.raiz, 'google-config.js'), "self.GG_GOOGLE = { clientId: 'cliente-prueba.apps.googleusercontent.com', apiKey: 'clave-prueba', appId: '123456', carpetaRutinas: 'CARPETA-RUTINAS' };\n");
  ctx = await E.nuevoContexto(); p = await ctx.newPage(); p.on('pageerror', e => errores.push(e.message));
  const google = []; let drive = { status: 200, body: RUTINA }, auth = [], urls = [];
  await ctx.route('https://accounts.google.com/gsi/client', r => { google.push('gsi'); r.fulfill({ contentType: 'text/javascript', body: GSI }); });
  await ctx.route('https://apis.google.com/js/api.js', r => { google.push('gapi'); r.fulfill({ contentType: 'text/javascript', body: GAPI }); });
  await ctx.route(u => u.href.startsWith('https://www.googleapis.com/drive/v3/files/'), r => {
    urls.push(r.request().url()); auth.push(r.request().headers()['authorization']);
    if (r.request().method() === 'OPTIONS') return r.fulfill({ status: 204, headers: { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'authorization' } });
    r.fulfill({ status: drive.status, body: drive.body, headers: { 'access-control-allow-origin': '*', 'content-type': 'application/json' } });
  });
  await p.goto(E.url); await p.waitForSelector('nav.tabs'); await p.waitForFunction(() => navigator.serviceWorker.controller, null, { timeout: 10000 }).catch(() => {}); await espera(800);
  ok(google.length === 0, 'configurado: no se conecta a Google mientras no se abre Importar');
  await p.click('nav [data-vista="importar"]'); await p.waitForSelector('[data-drive]');
  await p.waitForSelector('[data-drive]:not([disabled])', { timeout: 15000 }).catch(() => {});
  ok([...new Set(google)].sort().join() === 'gapi,gsi', 'al abrir Importar precarga las bibliotecas de Google (para no bloquear la ventana de autorización)');

  await p.click('[data-drive]'); await p.waitForSelector('text=Rutina válida', { timeout: 8000 }).catch(() => {});
  ok(await p.isVisible('text=Rutina válida'), 'elige en Drive → la rutina se valida igual que un archivo');
  ok(urls.length && urls[urls.length - 1].endsWith('/ID1?alt=media') && auth[auth.length - 1] === 'Bearer tok-prueba', 'baja el archivo elegido de Drive con el token de acceso');
  const pk = await p.evaluate(() => window.__picker);
  ok(pk && pk.token === 'tok-prueba' && pk.clave === 'clave-prueba' && pk.app === '123456', 'la ventana de Drive recibe token, clave y número de proyecto');
  ok(pk && pk.vistas && pk.vistas.join() === 'CARPETA-RUTINAS,mi-unidad', 'la ventana de Drive abre en la carpeta de rutinas y también deja buscar en Mi unidad');
  ok((await p.inputValue('#json-txt')).trim() === RUTINA.trim(), 'el contenido de Drive queda en el cuadro de texto, sin cambios');
  await p.click('[data-activar]'); await p.waitForSelector('text=Rutina activada', { timeout: 8000 }).catch(() => {});
  ok(await p.isVisible('text=Rutina activada'), 'la rutina traída desde Drive se activa');
  // La misma rutina otra vez: aviso informativo, no rechazo (Coordinador #24)
  await p.click('nav [data-vista="importar"]'); await p.waitForSelector('[data-drive]:not([disabled])', { timeout: 15000 }).catch(() => {});
  await p.click('[data-drive]'); await p.waitForSelector('text=Esta rutina ya está activa', { timeout: 8000 }).catch(() => {});
  const tYa = await p.textContent('main');
  ok(tYa.includes('Esta rutina ya está activa') && tYa.includes('No hay nada que importar') && !tYa.includes('Rutina rechazada') && !tYa.includes('Devuelve esta lista') && !(await p.isVisible('.note.err')), 'la misma rutina otra vez: aviso informativo, sin "Rutina rechazada" ni "Devuelve esta lista a Entrenamiento"');
  ok(!(await p.isVisible('[data-activar]')), 'la misma rutina otra vez: no ofrece activarla de nuevo');

  // C. Segunda vez: reutiliza el token mientras no vence
  await p.click('nav [data-vista="importar"]'); await p.waitForSelector('[data-drive]');
  await p.evaluate(() => { window.__respPicker = { action: 'cancel' }; });
  await p.click('[data-drive]'); await espera(500);
  ok(await p.evaluate(() => window.__pedidosToken) === 1, 'no vuelve a pedir autorización mientras el token no vence');
  ok(!(await p.isVisible('.note.err')), 'cerrar la ventana de Drive sin elegir no muestra error');

  // D. Errores claros
  await p.evaluate(() => { window.__respPicker = { action: 'picked', docs: [{ id: 'ID2', name: 'otra.json', mimeType: 'application/json' }] }; });
  drive = { status: 404, body: 'no' };
  await p.click('[data-drive]'); await p.waitForSelector('.note.err', { timeout: 5000 }).catch(() => {});
  ok((await p.textContent('main')).includes('No se pudo traer el archivo desde Drive') && (await p.textContent('main')).includes('404'), 'si Drive falla, lo explica y no culpa a la rutina');
  drive = { status: 200, body: '  ' };
  await p.click('[data-drive]'); await espera(500);
  ok((await p.textContent('main')).includes('está vacío en Drive'), 'archivo vacío en Drive: lo avisa');
  drive = { status: 200, body: RUTINA };
  await p.evaluate(() => { window.__respPicker = { action: 'picked', docs: [{ id: 'DOC3', name: 'rutina (Docs)', mimeType: 'application/vnd.google-apps.document' }] }; });
  await p.click('[data-drive]'); await espera(600);
  ok(urls[urls.length - 1].endsWith('/DOC3/export?mimeType=text%2Fplain'), 'si la rutina quedó como documento de Google, la exporta como texto');
  await ctx.setOffline(true);
  await p.click('[data-drive]'); await espera(300);
  ok((await p.textContent('main')).includes('necesitas internet'), 'sin internet: explica que Drive necesita conexión');
  await ctx.setOffline(false);

  // E. Autorización rechazada o ventana cerrada
  await ctx.close();
  ctx = await E.nuevoContexto(); p = await ctx.newPage(); p.on('pageerror', e => errores.push(e.message));
  await ctx.route('https://accounts.google.com/gsi/client', r => r.fulfill({ contentType: 'text/javascript', body: GSI }));
  await ctx.route('https://apis.google.com/js/api.js', r => r.fulfill({ contentType: 'text/javascript', body: GAPI }));
  await p.goto(E.url); await p.waitForSelector('nav.tabs'); await p.waitForFunction(() => navigator.serviceWorker.controller, null, { timeout: 10000 }).catch(() => {});
  await p.click('nav [data-vista="importar"]'); await p.waitForSelector('[data-drive]');
  await p.waitForSelector('[data-drive]:not([disabled])', { timeout: 15000 }).catch(() => {});
  await p.evaluate(() => { window.__respToken = { cerrada: true }; });
  await p.click('[data-drive]'); await espera(400);
  ok(!(await p.isVisible('.note.err')) && await p.isEnabled('[data-drive]'), 'si cierra la ventana de autorización, no hay error y el botón sigue disponible');
  await p.evaluate(() => { window.__respToken = { error: 'access_denied' }; });
  await p.click('[data-drive]'); await espera(400);
  ok(!(await p.isVisible('.note.err')), 'si rechaza el permiso, no hay error rojo');

  // F. Si Google no carga, el botón no queda bloqueado y explica qué pasa
  await ctx.close();
  ctx = await E.nuevoContexto(); p = await ctx.newPage(); p.on('pageerror', e => errores.push(e.message));
  let lento = true;
  await ctx.route('https://accounts.google.com/gsi/client', async r => { if (lento) return r.abort(); r.fulfill({ contentType: 'text/javascript', body: GSI }); });
  await ctx.route('https://apis.google.com/js/api.js', r => r.fulfill({ contentType: 'text/javascript', body: GAPI }));
  await p.goto(E.url); await p.waitForSelector('nav.tabs'); await p.waitForFunction(() => navigator.serviceWorker.controller, null, { timeout: 10000 }).catch(() => {});
  await p.click('nav [data-vista="importar"]'); await p.waitForSelector('[data-drive]:not([disabled])', { timeout: 15000 }).catch(() => {});
  await p.click('[data-drive]'); await p.waitForSelector('.note.err', { timeout: 5000 }).catch(() => {});
  ok((await p.textContent('main')).includes('No se pudo conectar con Google'), 'si Google no carga, lo explica y ofrece elegir el archivo del celular');
  lento = false;
  await p.click('[data-drive]'); await p.waitForFunction(() => GGDrive.listo(), null, { timeout: 8000 }).catch(() => {});
  ok(await p.evaluate(() => GGDrive.listo()), 'al volver a tocar con conexión, Google carga');

  // G. El token nunca se guarda en el celular
  const guardado = await p.evaluate(async () => JSON.stringify(await GGDB.todos()) + JSON.stringify(localStorage) + JSON.stringify(sessionStorage));
  ok(!guardado.includes('tok-prueba'), 'el token de Google no se guarda en la base ni en el navegador');
  ok(errores.length === 0, 'sin errores de JavaScript' + (errores.length ? ': ' + errores.join(' | ') : ''));
  await E.cerrar(); process.exit(resumen());
})();
