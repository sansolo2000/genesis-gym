/* Service worker de Génesis Gym 2.0.
 * Estrategia: todo el "cascarón" de la app se guarda al instalar; después se sirve desde la caché
 * (funciona sin internet). Una versión nueva queda "en espera" hasta que el usuario toca "Actualizar". */
importScripts('version.js');
const CACHE = 'genesis-gym-2-v' + self.GG_VERSION;
const ARCHIVOS = [
  './',
  'index.html',
  'version.js',
  'validador-rutina.js',
  'db.js',
  'plataforma.js',
  'respaldo.js',
  'imagenes.js',
  'imagenes/catalogo.json',
  'imagenes/curl-biceps-polea-1.png',
  'imagenes/curl-biceps-polea-2.png',
  'imagenes/curl-femoral-maquina-1.png',
  'imagenes/curl-femoral-maquina-2.png',
  'imagenes/dead-bug.svg',
  'imagenes/extension-triceps-polea-1.png',
  'imagenes/extension-triceps-polea-2.png',
  'imagenes/face-pull-polea.svg',
  'imagenes/jalon-agarre-neutro-1.png',
  'imagenes/jalon-agarre-neutro-2.png',
  'imagenes/pallof-press.svg',
  'imagenes/peso-muerto-rumano-mancuernas.svg',
  'imagenes/prensa-piernas-1.png',
  'imagenes/prensa-piernas-2.png',
  'imagenes/press-inclinado-maquina.svg',
  'imagenes/press-maquina-agarre-neutro.svg',
  'imagenes/puente-gluteo.svg',
  'imagenes/remo-mancuerna-una-mano.svg',
  'imagenes/remo-maquina-agarre-neutro-1.png',
  'imagenes/remo-maquina-agarre-neutro-2.png',
  'imagenes/zancada-caminando-1.png',
  'imagenes/zancada-caminando-2.png',
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

self.addEventListener('install', ev => {
  // cache: 'reload' evita tomar copias viejas de la caché HTTP de GitHub Pages.
  ev.waitUntil(caches.open(CACHE).then(c => c.addAll(ARCHIVOS.map(u => new Request(u, { cache: 'reload' })))));
});

self.addEventListener('activate', ev => {
  ev.waitUntil(
    caches.keys()
      .then(ks => Promise.all(ks.filter(k => k.startsWith('genesis-gym-2-') && k !== CACHE).map(k => caches.delete(k))))
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
  ev.respondWith(caches.match(req, { cacheName: CACHE, ignoreSearch: true }).then(r => r || fetch(req)));
});
