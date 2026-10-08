/* Service worker de Génesis Gym 2.0.
 * Estrategia: todo el "cascarón" de la app se guarda al instalar; después se sirve desde la caché
 * (funciona sin internet). Una versión nueva queda "en espera" hasta que el usuario toca "Actualizar".
 * Las imágenes de los ejercicios van en una caché aparte, que depende de la versión del catálogo y no de
 * la versión de la app: al actualizar la app no se vuelven a bajar. Se guardan "lo que se pueda": si una
 * imagen falla, la app se instala igual y esa imagen se guarda la próxima vez que se vea con internet. */
importScripts('version.js');
const CACHE = 'genesis-gym-2-v' + self.GG_VERSION;
const PREFIJO_IMG = 'genesis-gym-2-imagenes-v';
const ARCHIVOS = [
  './',
  'index.html',
  'version.js',
  'validador-rutina.js',
  'db.js',
  'plataforma.js',
  'respaldo.js',
  'imagenes.js',
  'google-config.js',
  'drive.js',
  'imagenes/catalogo.json',
  'imagenes/CREDITOS.md',
  'manifest.webmanifest',
  'fuentes/fuentes.css',
  'fuentes/barlow-latin-400-normal.woff2',
  'fuentes/barlow-latin-500-normal.woff2',
  'fuentes/barlow-latin-600-normal.woff2',
  'fuentes/barlow-condensed-latin-600-normal.woff2',
  'fuentes/barlow-condensed-latin-700-normal.woff2',
  'iconos/icono-192.png',
  'iconos/icono-512.png',
  'iconos/icono-maskable-512.png',
  'iconos/apple-touch-icon.png'
];

/* Lee el catálogo guardado en la caché del cascarón y devuelve el nombre de la caché de imágenes y sus archivos. */
async function catalogo() {
  const r = await caches.match('imagenes/catalogo.json', { cacheName: CACHE });
  if (!r) return null;
  const cat = await r.json();
  const srcs = [...new Set(Object.values(cat.imagenes || {}).flatMap(m => m.srcs || []))];
  return { nombre: PREFIJO_IMG + cat.version, srcs };
}

/* Baja las imágenes que falten, de a varias a la vez. Nunca falla: devuelve cuántas quedaron guardadas. */
async function guardarImagenes() {
  const c = await catalogo();
  if (!c) return;
  const cache = await caches.open(c.nombre);
  const ya = new Set((await cache.keys()).map(k => new URL(k.url).pathname));
  const base = new URL('./', self.location).pathname;
  const faltan = c.srcs.filter(s => !ya.has(base + s));
  let i = 0;
  const trabajador = async () => {
    while (i < faltan.length) {
      const s = faltan[i++];
      try { const r = await fetch(new Request(s, { cache: 'reload' })); if (r.ok) await cache.put(s, r); } catch (_) {}
    }
  };
  await Promise.all(Array.from({ length: 6 }, trabajador));
}

self.addEventListener('install', ev => {
  // cache: 'reload' evita tomar copias viejas de la caché HTTP de GitHub Pages.
  ev.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(ARCHIVOS.map(u => new Request(u, { cache: 'reload' }))))
      .then(() => guardarImagenes().catch(() => {}))
  );
});

self.addEventListener('activate', ev => {
  ev.waitUntil(
    catalogo().catch(() => null)
      .then(c => caches.keys().then(ks => Promise.all(ks
        .filter(k => k.startsWith('genesis-gym-2-') && k !== CACHE && !(c && k === c.nombre))
        .map(k => caches.delete(k)))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('message', ev => {
  if (ev.data === 'ACTUALIZAR') self.skipWaiting();
});

self.addEventListener('fetch', ev => {
  const req = ev.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return; // nada externo
  if (req.mode === 'navigate') {
    ev.respondWith(caches.match('index.html', { cacheName: CACHE }).then(r => r || fetch(req)));
    return;
  }
  ev.respondWith((async () => {
    const r = await caches.match(req, { cacheName: CACHE, ignoreSearch: true });
    if (r) return r;
    const esImagen = url.pathname.includes('/imagenes/');
    const c = esImagen ? await catalogo().catch(() => null) : null;
    if (c) { const ri = await caches.match(req, { cacheName: c.nombre, ignoreSearch: true }); if (ri) return ri; }
    const red = await fetch(req);
    if (c && red.ok) { const copia = red.clone(); caches.open(c.nombre).then(k => k.put(url.pathname, copia)).catch(() => {}); }
    return red;
  })());
});
