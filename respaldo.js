/* Génesis Gym 2.0 — perfil, respaldo y restauración.
 *
 * Los datos viven solo en este celular. Este módulo:
 *  - pide, una sola vez, de quién es el celular (perfil local);
 *  - crea un respaldo completo (archivo .json) que se comparte a Drive, correo, etc.;
 *  - restaura un respaldo reemplazando TODA la base, con vista previa y después de respaldar lo actual;
 *  - recuerda en "Hoy" cuando el último respaldo tiene más de 7 días.
 * Se dibuja en dos huecos que deja la pantalla de la 1.0: #gg-aviso-hoy (Hoy) y #gg-respaldo (Historial).
 */
(function () {
  'use strict';
  const FORMATO = 'genesis-respaldo';
  const VERSION = 1;
  // 0.7.5: también las colecciones de Comidas. Antes, un respaldo hecho después de usar Comidas se creaba bien
  // pero no se podía restaurar ("colección desconocida"). El formato del archivo no cambia (sigue en v1).
  const COLECCIONES = ['config', 'rutinas', 'sesiones', 'imagenes', 'alimentacion', 'programas_alimentacion', 'comidas', 'fotos'];
  const DIAS_AVISO = 7;
  const TZ = 'America/Santiago';

  const R = {
    perfil: undefined,        // undefined = cargando, null = sin perfil
    ultimo: undefined,        // ISO del último respaldo
    respaldadoAhora: false,   // se hizo un respaldo en esta apertura de la app
    estado: '',               // mensaje del último intento de respaldo
    estadoError: false,
    restaurar: null,          // { archivo, error, doc, resumen, confirmar, trabajando }
    borradorNombre: ''        // lo escrito en el perfil: sobrevive a que la pantalla se redibuje
  };

  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const hoyISO = () => new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  const fechaHora = iso => new Date(iso).toLocaleString('es-CL', { timeZone: TZ, day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  const fechaCorta = f => new Date(f + 'T12:00:00Z').toLocaleDateString('es-CL', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
  const slug = s => String(s || 'sin-perfil').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'sin-perfil';

  async function sha256(texto) {
    const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(texto));
    return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
  }
  /* Huella: SHA-256 del JSON de los documentos ordenados por ruta. La migración usa la misma regla. */
  const ordenar = docs => [...docs].sort((a, b) => a.ruta < b.ruta ? -1 : a.ruta > b.ruta ? 1 : 0);
  const huellaDe = docs => sha256(JSON.stringify(ordenar(docs).map(d => ({ ruta: d.ruta, data: d.data }))));
  function conteos(docs) {
    const c = {}; for (const col of COLECCIONES) c[col] = 0;
    for (const d of docs) { const col = d.ruta.split('/')[0]; c[col] = (c[col] || 0) + 1; }
    return c;
  }
  function rangoSesiones(docs) {
    const f = docs.filter(d => d.ruta.startsWith('sesiones/')).map(d => d.data && d.data.fecha).filter(Boolean).sort();
    return f.length ? { desde: f[0], hasta: f[f.length - 1] } : null;
  }

  async function cargar() {
    try {
      const db = await self.GGDB.abrir();
      const p = await db.doc('config/perfil').get();
      R.perfil = p.exists ? p.data() : null;
      const r = await db.doc('config/respaldo').get();
      R.ultimo = r.exists ? r.data().ultimo : null;
    } catch (e) { R.perfil = R.perfil === undefined ? null : R.perfil; }
    pintar();
  }

  /* ---------- crear respaldo ---------- */
  async function crearRespaldo() {
    R.estado = 'Preparando el respaldo…'; R.estadoError = false; pintar();
    try {
      const docs = ordenar(await self.GGDB.todos());
      const ahora = new Date().toISOString();
      const archivo = {
        formato: FORMATO, version: VERSION,
        app: 'genesis-gym-2', version_app: self.GG_VERSION,
        exportado_en: ahora,
        perfil: R.perfil ? R.perfil.nombre : null,
        conteos: conteos(docs),
        huella_sha256: await huellaDe(docs),
        documentos: docs
      };
      const nombre = `genesis-respaldo-${slug(R.perfil && R.perfil.nombre)}-${hoyISO()}.json`;
      const dl = await self.claude.use('downloads');
      await dl.save({ filename: nombre, data: JSON.stringify(archivo, null, 1), mime: 'application/json' });
      const db = await self.GGDB.abrir();
      await db.doc('config/respaldo').set({ ultimo: ahora, archivo: nombre, documentos: docs.length });
      R.ultimo = ahora; R.respaldadoAhora = true;
      R.estado = `Respaldo creado: ${nombre}. Guárdalo fuera del celular (por ejemplo, en Drive).`; R.estadoError = false;
    } catch (e) {
      R.estadoError = true;
      R.estado = e && e.code === 'declined' ? 'Respaldo cancelado: no se guardó ningún archivo.' : 'No se pudo crear el respaldo. Vuelve a intentarlo.';
    }
    pintar();
  }

  /* ---------- restaurar ---------- */
  async function leerRespaldo(f) {
    R.restaurar = { archivo: f.name, trabajando: true }; pintar();
    let texto = '';
    try { texto = await f.text(); } catch (_) {}
    const mal = m => { R.restaurar = { archivo: f.name, error: m }; pintar(); };
    if (!texto.trim()) return mal(`El archivo "${f.name}" llegó vacío al celular (${f.size} bytes). Guárdalo primero en el celular (Descargas) y elígelo desde Mis archivos.`);
    let doc;
    try { doc = JSON.parse(texto); } catch (e) { return mal('El archivo no es un respaldo válido (no es JSON).'); }
    if (!doc || doc.formato !== FORMATO) return mal('El archivo no es un respaldo de Génesis Gym (falta "formato": "genesis-respaldo"). ¿Elegiste una rutina en vez de un respaldo?');
    if (doc.version !== VERSION) return mal(`Versión de respaldo no soportada: ${doc.version}.`);
    if (!Array.isArray(doc.documentos)) return mal('El respaldo no trae la lista de documentos.');
    const RUTA = /^[A-Za-z0-9_\-.~:@+]{1,200}\/[A-Za-z0-9_\-.~:@+]{1,200}$/;
    for (const d of doc.documentos) {
      if (!d || typeof d.ruta !== 'string' || !RUTA.test(d.ruta) || !d.data || typeof d.data !== 'object' || Array.isArray(d.data)) return mal('El respaldo tiene un documento con formato inválido.');
      if (!COLECCIONES.includes(d.ruta.split('/')[0])) return mal(`El respaldo trae una colección desconocida: ${d.ruta.split('/')[0]}.`);
    }
    if (new Set(doc.documentos.map(d => d.ruta)).size !== doc.documentos.length) return mal('El respaldo tiene documentos repetidos.');
    const h = await huellaDe(doc.documentos);
    if (h !== doc.huella_sha256) return mal('La huella del respaldo no coincide: el archivo está incompleto o fue modificado. No se restauró nada.');
    const actuales = await self.GGDB.todos();
    R.restaurar = {
      archivo: f.name, doc,
      resumen: { conteos: conteos(doc.documentos), rango: rangoSesiones(doc.documentos), perfil: doc.perfil, exportado_en: doc.exportado_en, version_app: doc.version_app, app: doc.app },
      actuales: conteos(actuales)
    };
    pintar();
  }

  async function aplicarRestauracion() {
    const r = R.restaurar; if (!r || !r.doc) return;
    r.trabajando = true; pintar();
    try {
      await self.GGDB.reemplazarTodo(r.doc.documentos);
      const comprobados = await self.GGDB.todos();
      if (await huellaDe(comprobados) !== r.doc.huella_sha256) throw new Error('verificación');
      location.reload();
    } catch (e) {
      R.restaurar = { archivo: r.archivo, error: 'No se pudo restaurar. Tus datos anteriores no se modificaron si el error fue al escribir; si dice "verificación", vuelve a restaurar desde el mismo archivo.' };
      pintar();
    }
  }

  /* ---------- perfil ---------- */
  async function guardarPerfil(nombre) {
    nombre = String(nombre || '').trim();
    if (!nombre) return;
    const db = await self.GGDB.abrir();
    const p = { nombre, creado: new Date().toISOString() };
    await db.doc('config/perfil').set(p);
    R.perfil = p; pintar();
  }

  /* ---------- pintar en los huecos de la pantalla ---------- */
  function htmlAvisoHoy() {
    if (R.perfil === undefined) return '';
    if (R.perfil === null) {
      return `<section class="card"><h2>¿De quién es este celular?</h2>
        <p class="muted">Cada celular guarda los datos de una sola persona. El nombre aparece en los respaldos.</p>
        <div class="campo"><label for="gg-perfil-nombre">Nombre</label><input id="gg-perfil-nombre" autocomplete="given-name" maxlength="40" value="${esc(R.borradorNombre)}" style="width:100%;min-height:48px;border:1px solid var(--line);border-radius:10px;background:var(--bg);padding:0 12px;font-size:1.1rem"></div>
        <button class="btn primary" data-gg-perfil>Guardar</button></section>`;
    }
    const dias = R.ultimo ? Math.floor((Date.now() - new Date(R.ultimo).getTime()) / 86400000) : null;
    if (dias === null) return `<div class="note warn"><strong>Aún no tienes respaldo.</strong> Tus datos están solo en este celular. Crea uno en <button class="link" data-ir="historial">Historial → Respaldo</button>.</div>`;
    if (dias > DIAS_AVISO) return `<div class="note warn"><strong>Tu último respaldo tiene ${dias} días.</strong> Crea uno nuevo en <button class="link" data-ir="historial">Historial → Respaldo</button>.</div>`;
    return '';
  }

  function htmlTarjeta() {
    let h = `<h2>Respaldo de todos tus datos</h2>
      <p class="muted">Tus datos viven solo en este celular. Crea un respaldo cada semana (por ejemplo, el viernes junto con el CSV) y guárdalo en Drive.</p>
      <p>${R.ultimo ? `Último respaldo: <strong>${esc(fechaHora(R.ultimo))}</strong>` : '<strong>Todavía no hay respaldos.</strong>'}${R.perfil ? ` · perfil: ${esc(R.perfil.nombre)}` : ''}</p>
      <button class="btn primary" data-gg-respaldar>Crear respaldo</button>
      ${R.estado ? `<div class="note ${R.estadoError ? 'err' : 'ok'}">${esc(R.estado)}</div>` : ''}
      <hr style="border:0;border-top:1px solid var(--line);width:100%">
      <h3>Restaurar desde un respaldo</h3>
      <p class="muted">Reemplaza <strong>todos</strong> los datos de este celular por los del archivo.</p>
      <div class="campo"><label for="gg-archivo-respaldo">Archivo de respaldo (.json)</label><input id="gg-archivo-respaldo" type="file" style="font-size:1rem;padding:10px"></div>`;
    const r = R.restaurar;
    if (r && r.trabajando && !r.doc) h += `<p class="muted">Leyendo "${esc(r.archivo)}"…</p>`;
    if (r && r.error) h += `<div class="note err">${esc(r.error)}</div>`;
    if (r && r.doc) {
      const s = r.resumen, c = s.conteos, a = r.actuales;
      const hayDatos = (a.sesiones || 0) > 0 || (a.rutinas || 0) > 0 || (a.comidas || 0) > 0;
      const bloqueado = hayDatos && !R.respaldadoAhora;
      h += `<div class="note info"><strong>Respaldo válido</strong> (huella verificada).<br>
        ${s.perfil ? `Perfil: ${esc(s.perfil)} · ` : ''}creado el ${esc(fechaHora(s.exportado_en))}${s.app ? ` · origen: ${esc(s.app)}` : ''}<br>
        Sesiones: <strong>${c.sesiones}</strong>${s.rango ? ` (del ${esc(fechaCorta(s.rango.desde))} al ${esc(fechaCorta(s.rango.hasta))})` : ''} · rutinas: ${c.rutinas} · imágenes: ${c.imagenes} · configuración: ${c.config}${c.comidas || c.fotos ? ` · comidas: ${c.comidas || 0} · fotos de comidas: ${c.fotos || 0}` : ''}</div>
        <p>Hoy este celular tiene <strong>${a.sesiones || 0}</strong> ${(a.sesiones || 0) === 1 ? 'sesión' : 'sesiones'} y <strong>${a.rutinas || 0}</strong> ${(a.rutinas || 0) === 1 ? 'rutina' : 'rutinas'}. Se reemplazarán por los datos del respaldo.</p>`;
      if (bloqueado) h += `<div class="note warn">Antes de reemplazar, crea un respaldo de lo que hay ahora (botón "Crear respaldo" de arriba). Así no pierdes nada si te equivocas de archivo.</div>`;
      else if (!r.confirmar) h += `<button class="btn danger" data-gg-restaurar ${r.trabajando ? 'disabled' : ''}>Reemplazar todo con este respaldo</button>`;
      else h += `<div class="confirm"><span>¿Seguro? Esto no se puede deshacer, salvo restaurando otro respaldo.</span>
        <button class="btn danger" data-gg-restaurar-si ${r.trabajando ? 'disabled' : ''}>${r.trabajando ? 'Restaurando…' : 'Sí, reemplazar'}</button><button class="btn" data-gg-restaurar-no>Cancelar</button></div>`;
    }
    return h;
  }

  function pintar() {
    const a = document.getElementById('gg-aviso-hoy');
    if (a) { const html = htmlAvisoHoy(); if (a.dataset.html !== html) { a.innerHTML = html; a.dataset.html = html; } }
    const t = document.getElementById('gg-respaldo');
    if (t) { const html = htmlTarjeta(); if (t.dataset.html !== html) { t.innerHTML = html; t.dataset.html = html; } }
  }

  /* Las pantallas de la 1.0 se redibujan completas; cuando aparece un hueco nuevo, se rellena. */
  new MutationObserver(() => {
    const a = document.getElementById('gg-aviso-hoy'), t = document.getElementById('gg-respaldo');
    if ((a && !a.dataset.html && a.dataset.html !== '') || (t && !t.dataset.html)) pintar();
  }).observe(document.documentElement, { childList: true, subtree: true });

  document.addEventListener('click', ev => {
    const t = ev.target.closest('[data-gg-respaldar],[data-gg-restaurar],[data-gg-restaurar-si],[data-gg-restaurar-no],[data-gg-perfil]');
    if (!t) return;
    if (t.hasAttribute('data-gg-respaldar')) return void crearRespaldo();
    if (t.hasAttribute('data-gg-perfil')) { const i = document.getElementById('gg-perfil-nombre'); return void guardarPerfil((i && i.value) || R.borradorNombre); }
    if (t.hasAttribute('data-gg-restaurar') && R.restaurar) { R.restaurar.confirmar = true; return pintar(); }
    if (t.hasAttribute('data-gg-restaurar-no') && R.restaurar) { R.restaurar.confirmar = false; return pintar(); }
    if (t.hasAttribute('data-gg-restaurar-si')) return void aplicarRestauracion();
  });
  document.addEventListener('input', ev => {
    if (ev.target.id === 'gg-perfil-nombre') { R.borradorNombre = ev.target.value; const a = document.getElementById('gg-aviso-hoy'); if (a) a.dataset.html = htmlAvisoHoy(); }
  });
  document.addEventListener('change', ev => {
    if (ev.target.id === 'gg-archivo-respaldo' && ev.target.files && ev.target.files[0]) leerRespaldo(ev.target.files[0]);
  });

  self.GGRespaldo = {
    avisoHoy: () => '<div id="gg-aviso-hoy"></div>',
    tarjeta: () => '<section id="gg-respaldo" class="card"></section>',
    huellaDe, FORMATO, VERSION
  };
  cargar();
})();
