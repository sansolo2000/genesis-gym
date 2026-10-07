/* Génesis Gym 2.0 — paso 1: esqueleto instalable.
 * Muestra el estado de instalación, caché sin conexión y almacenamiento; avisa si hay una versión nueva. */
(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const marca = (el, ok, si, no) => { el.textContent = ok ? si : no; el.className = ok ? 'si' : 'no'; };

  $('version').textContent = self.GG_VERSION;

  // ¿Se abrió como app instalada?
  const instalada = window.matchMedia('(display-mode: standalone)').matches;
  marca($('e-instalada'), instalada, 'sí', 'no (navegador)');

  // Conexión
  const red = () => marca($('e-red'), navigator.onLine, 'sí', 'sin conexión');
  red(); addEventListener('online', red); addEventListener('offline', red);

  // Almacenamiento persistente: se pide; Chrome decide solo (sin preguntar al usuario).
  (async () => {
    const el = $('e-persist');
    if (!(navigator.storage && navigator.storage.persisted)) { marca(el, false, '', 'no disponible'); return; }
    let ok = await navigator.storage.persisted();
    if (!ok && navigator.storage.persist) { try { ok = await navigator.storage.persist(); } catch (e) {} }
    marca(el, ok, 'sí', 'todavía no');
  })();

  // Service worker y aviso de versión nueva
  const elOff = $('e-offline');
  if (!('serviceWorker' in navigator)) { marca(elOff, false, '', 'no disponible'); return; }

  const mostrarAviso = reg => {
    const aviso = $('aviso-version');
    aviso.hidden = false;
    $('btn-actualizar').onclick = () => { if (reg.waiting) reg.waiting.postMessage('ACTUALIZAR'); };
  };

  let recargando = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (recargando) return; recargando = true; location.reload();
  });

  navigator.serviceWorker.register('sw.js', { updateViaCache: 'none' }).then(reg => {
    if (reg.waiting && navigator.serviceWorker.controller) mostrarAviso(reg);
    reg.addEventListener('updatefound', () => {
      const nuevo = reg.installing;
      if (!nuevo) return;
      nuevo.addEventListener('statechange', () => {
        if (nuevo.state === 'installed' && navigator.serviceWorker.controller) mostrarAviso(reg);
      });
    });
    // Busca versiones nuevas al volver a la app (solo si hay red).
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && navigator.onLine) reg.update().catch(() => {});
    });
  }).catch(() => marca(elOff, false, '', 'error al activar'));

  navigator.serviceWorker.ready.then(() => marca(elOff, true, 'sí', ''));
})();
