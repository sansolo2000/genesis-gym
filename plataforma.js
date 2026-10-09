/* Génesis Gym 2.0 — capa de plataforma.
 *
 * Las pantallas de la 1.0 piden sus servicios con window.claude.use('db' | 'user' | 'downloads').
 * Aquí se entregan los equivalentes locales, para no tocar la lógica de la 1.0:
 *   db        → base IndexedDB del celular (db.js)
 *   user      → siempre dueño: cada celular es de una sola persona
 *   downloads → menú Compartir de Android (para elegir Drive, correo, etc.) o descarga normal
 * Además: registra el service worker, avisa si hay una versión nueva y muestra la barra de "versión de prueba".
 */
(function () {
  'use strict';
  /* Mientras sea true, la app avisa que los entrenamientos reales se registran en la 1.0 (hasta el día de corte). */
  const MODO_PRUEBA = true;

  function errCodigo(codigo) { const e = new Error(codigo); e.code = codigo; return e; }

  const downloads = {
    /* save({ filename, data, mime? }) — comparte o descarga un archivo de texto. */
    async save({ filename, data, mime }) {
      const tipo = mime || (/\.csv$/i.test(filename) ? 'text/csv' : /\.json$/i.test(filename) ? 'application/json' : 'text/plain');
      const blob = new Blob([data], { type: tipo + ';charset=utf-8' });
      let archivo = null;
      try { archivo = new File([blob], filename, { type: tipo }); } catch (_) {}
      if (archivo && navigator.canShare && navigator.canShare({ files: [archivo] })) {
        try { await navigator.share({ files: [archivo], title: filename }); return { via: 'compartir' }; }
        catch (e) {
          if (e && e.name === 'AbortError') throw errCodigo('declined');
          // otro error del menú Compartir: se intenta la descarga normal
        }
      }
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = filename; a.rel = 'noopener'; a.style.display = 'none';
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 30000);
      return { via: 'descarga' };
    }
  };

  self.claude = {
    use: async nombre => {
      if (nombre === 'db') return self.GGDB.abrir();
      if (nombre === 'user') return { isOwner: async () => true };
      if (nombre === 'downloads') return downloads;
      return null;
    }
  };

  /* ---------- barra superior: versión de prueba + versión nueva ---------- */
  const css = document.createElement('style');
  css.textContent = `
  #gg-barra{max-width:640px;margin:12px auto 0;display:flex;flex-direction:column;gap:8px}
  #gg-barra .gg-nota{border-radius:10px;padding:10px 14px;font-size:.9rem;display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap}
  #gg-barra .gg-prueba{background:var(--warn-soft);color:var(--warn)}
  #gg-barra .gg-version{background:var(--accent-soft);color:var(--ink)}
  #gg-barra button{min-height:44px;padding:0 16px;border-radius:10px;border:1px solid var(--accent);background:var(--accent);color:var(--accent-ink);font:inherit;font-weight:600;cursor:pointer}
  #gg-pie{max-width:640px;margin:8px auto 0;font-size:.78rem;color:var(--muted);text-align:center}`;
  document.head.appendChild(css);

  function montarBarra() {
    const main = document.getElementById('app');
    if (!main || document.getElementById('gg-barra')) return;
    const barra = document.createElement('div');
    barra.id = 'gg-barra';
    barra.innerHTML =
      (MODO_PRUEBA ? '<div class="gg-nota gg-prueba" role="note"><span><strong>Versión de prueba.</strong> Tus entrenamientos reales se siguen registrando en Génesis Gym 1.0 hasta el día de corte.</span></div>' : '') +
      '<div class="gg-nota gg-version" id="gg-aviso-version" hidden><span>Hay una versión nueva de la app.</span><button type="button" id="gg-actualizar">Actualizar</button></div>';
    main.parentNode.insertBefore(barra, main);
    const pie = document.createElement('p');
    pie.id = 'gg-pie';
    pie.textContent = 'Génesis Gym 2.0 · versión ' + self.GG_VERSION + ' · datos guardados solo en este celular';
    main.parentNode.insertBefore(pie, main.nextSibling);
    contarImagenes();
  }

  /* Pie: cuántas imágenes de ejercicios quedaron guardadas para usar sin internet. */
  async function contarImagenes() {
    try {
      if (!self.caches) return;
      const cat = await (await fetch('imagenes/catalogo.json')).json();
      const srcs = new Set(Object.values(cat.imagenes || {}).flatMap(m => m.srcs || []));
      const cache = await caches.open('genesis-gym-2-imagenes-v' + (cat.version_imagenes || cat.version));
      const base = new URL('./', location.href).pathname;
      const ya = new Set((await cache.keys()).map(k => new URL(k.url).pathname.slice(base.length)));
      const n = [...srcs].filter(s => ya.has(s)).length;
      const pie = document.getElementById('gg-pie');
      if (pie) pie.textContent = 'Génesis Gym 2.0 · versión ' + self.GG_VERSION + ' · datos guardados solo en este celular · imágenes sin internet: ' + n + ' de ' + srcs.size;
    } catch (_) {}
  }
  if (navigator.serviceWorker) navigator.serviceWorker.addEventListener('controllerchange', () => setTimeout(contarImagenes, 500));
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', montarBarra); else montarBarra();

  /* ---------- selector de archivos en Android ----------
   * Al abrir el selector, la app pasa a segundo plano; al volver, la pantalla de la 1.0 se redibuja y
   * reemplaza el <input type=file>. El archivo elegido llega al input viejo, ya fuera de la página, y su
   * evento "change" no alcanza a los manejadores. Aquí se vuelve a poner ese input en la página un
   * instante y se repite el evento, para que el archivo se procese igual. */
  document.addEventListener('click', ev => {
    const inp = ev.target && ev.target.closest && ev.target.closest('input[type="file"]');
    if (!inp || !inp.id) return;
    inp.addEventListener('change', () => {
      if (inp.isConnected || !inp.files || !inp.files.length) return;
      inp.style.display = 'none';
      document.body.appendChild(inp);
      inp.dispatchEvent(new Event('change', { bubbles: true }));
      setTimeout(() => inp.remove(), 0);
    }, { once: true });
  }, true);

  /* ---------- almacenamiento persistente ---------- */
  (async () => {
    try {
      if (navigator.storage && navigator.storage.persisted && !(await navigator.storage.persisted()) && navigator.storage.persist) {
        await navigator.storage.persist();
      }
    } catch (_) {}
  })();

  /* ---------- service worker y versión nueva ---------- */
  if ('serviceWorker' in navigator) {
    const mostrarAviso = reg => {
      montarBarra();
      const aviso = document.getElementById('gg-aviso-version');
      if (!aviso) return;
      aviso.hidden = false;
      document.getElementById('gg-actualizar').onclick = () => { if (reg.waiting) reg.waiting.postMessage('ACTUALIZAR'); };
    };
    // Recargar SOLO cuando el usuario tocó "Actualizar". En la primera instalación el service worker
    // también toma el control (controllerchange) y recargar ahí borraría lo que se esté escribiendo.
    let pidioActualizar = false, recargando = false;
    document.addEventListener('click', ev => { if (ev.target.closest('#gg-actualizar')) pidioActualizar = true; }, true);
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (!pidioActualizar || recargando) return; recargando = true; location.reload();
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
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && navigator.onLine) reg.update().catch(() => {});
      });
    }).catch(() => {});
  }
})();
