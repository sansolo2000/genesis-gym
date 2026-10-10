/* Sirve una COPIA de genesis-gym en http://localhost:<puerto>/genesis-gym/ (como GitHub Pages) y abre Chromium. */
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path'), os = require('os');
const TIPOS = { '.html':'text/html; charset=utf-8', '.js':'text/javascript; charset=utf-8', '.css':'text/css', '.webmanifest':'application/manifest+json', '.png':'image/png', '.json':'application/json', '.woff2':'font/woff2', '.txt':'text/plain; charset=utf-8', '.svg':'image/svg+xml', '.jpg':'image/jpeg' };
async function preparar(origen) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'gg2-'));
  const raiz = path.join(tmp, 'genesis-gym');
  fs.cpSync(path.resolve(origen), raiz, { recursive: true, filter: s => !s.includes(path.sep + '.git') });
  const externas = [], google = [], servidos = [];  // servidos: cada archivo pedido al servidor (incluye los pedidos del service worker)
  const GOOGLE = ['https://accounts.google.com/gsi/client', 'https://apis.google.com/js/api.js'];
  const srv = http.createServer((q, r) => {
    const p = decodeURIComponent(q.url.split('?')[0]);
    servidos.push(p);
    if (!p.startsWith('/genesis-gym/')) { r.writeHead(404); return r.end(); }
    let f = path.join(tmp, p); if (p.endsWith('/')) f = path.join(f, 'index.html');
    fs.readFile(f, (e, d) => { if (e) { r.writeHead(404); return r.end(); } r.writeHead(200, { 'Content-Type': TIPOS[path.extname(f)] || 'application/octet-stream', 'Cache-Control': 'max-age=600' }); r.end(d); });
  });
  await new Promise(res => srv.listen(0, res));
  const url = `http://localhost:${srv.address().port}/genesis-gym/`;
  const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' }).catch(() => chromium.launch());
  const nuevoContexto = async (op = {}) => {
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, acceptDownloads: true, ...op });
    // Registra cualquier pedido fuera de localhost: la app no debe depender de internet.
    // Las bibliotecas de Google (solo al abrir Importar, para "Buscar en Drive") se anotan aparte, en google.
    await ctx.route(u => !u.href.startsWith(url.slice(0, url.indexOf('/genesis-gym/'))), r => { const u = r.request().url(); (GOOGLE.includes(u) ? google : externas).push(u); r.abort(); });
    return ctx;
  };
  const cerrar = async () => { await browser.close(); srv.close(); fs.rmSync(tmp, { recursive: true, force: true }); };
  return { url, raiz, nuevoContexto, cerrar, externas, google, servidos };
}
let fallas = 0;
const ok = (c, m) => { console.log((c ? 'PASA ' : 'FALLA') + ' | ' + m); if (!c) fallas++; };
const resumen = () => { console.log(fallas ? `\n${fallas} prueba(s) fallan` : '\nTodas las pruebas pasan'); return fallas ? 1 : 0; };
module.exports = { preparar, ok, resumen };
