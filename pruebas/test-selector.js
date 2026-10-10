/* Reproduce el uso real en Android: al abrir el selector de archivos la app pasa a segundo plano y,
 * al volver, la pantalla se redibuja. El archivo elegido debe llegar igual (rutina y respaldo).
 * Uso: node test-selector.js <carpeta genesis-gym> <carpeta genesis-app 1.0> */
const fs = require('fs'), path = require('path');
const { preparar, ok, resumen } = require('./servidor');
const V2 = path.resolve(process.argv[2] || '../genesis-gym'), V1 = path.resolve(process.argv[3]);
(async () => {
  const E = await preparar(V2); const errores = [];
  const ctx = await E.nuevoContexto(); const p = await ctx.newPage(); p.on('pageerror', e => errores.push(e.message));
  await p.goto(E.url); await p.waitForSelector('nav.tabs');
  const volver = () => p.evaluate(() => { Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true }); document.dispatchEvent(new Event('visibilitychange')); });
  const ejemplo = path.join(V1, 'ejemplo-rutina.json');
  // Rutina
  await p.click('nav [data-vista="importar"]'); await p.waitForSelector('#archivo');
  const antes = await p.evaluateHandle(() => document.getElementById('archivo'));
  const [fc] = await Promise.all([p.waitForEvent('filechooser'), p.click('#archivo')]);
  await volver(); await p.waitForTimeout(500);
  ok(await p.evaluate(a => !a.isConnected, antes), 'escenario real: al volver del selector, la pantalla reemplazó el botón');
  await fc.setFiles(ejemplo);
  await p.waitForSelector('text=Rutina válida', { timeout: 5000 }).catch(() => {});
  ok(await p.isVisible('text=Rutina válida'), 'rutina: el archivo elegido llega aunque la pantalla se haya redibujado');
  // Respaldo (Historial)
  await p.click('[data-activar]').catch(() => {}); await p.waitForTimeout(300);
  await p.click('nav [data-vista="historial"]'); await p.waitForSelector('#gg-archivo-respaldo');
  const [fc2] = await Promise.all([p.waitForEvent('filechooser'), p.click('#gg-archivo-respaldo')]);
  await volver(); await p.waitForTimeout(500);
  await fc2.setFiles({ name: 'no-respaldo.json', mimeType: 'application/json', buffer: fs.readFileSync(ejemplo) });
  await p.waitForSelector('text=no es un respaldo de Génesis Gym', { timeout: 5000 }).catch(() => {});
  ok(await p.isVisible('text=no es un respaldo de Génesis Gym'), 'respaldo: el archivo elegido llega aunque la pantalla se haya redibujado');
  ok(errores.length === 0, 'sin errores de JavaScript' + (errores.length ? ': ' + errores.join(' | ') : ''));
  await E.cerrar(); process.exit(resumen());
})().catch(e => { console.error(e); process.exit(2); });
