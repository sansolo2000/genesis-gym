/* Génesis Gym 2.0 — Google Drive: importar archivos (rutina, programa de alimentación) y subir el registro de comidas.
 *
 * Permiso mínimo (drive.file): la app solo puede leer el archivo que la persona elige en la ventana de Google
 * y los archivos que la propia app crea (carpeta "Genesis Gym - registro comidas"). No ve el resto del Drive.
 * Solo se conecta a Google cuando hay configuración (google-config.js) y la persona está en Importar o en Comidas.
 * El token de acceso queda solo en memoria (dura ~1 hora) y nunca se guarda en el celular.
 */
(function () {
  'use strict';
  const C = self.GG_GOOGLE || {};
  const SCOPE = 'https://www.googleapis.com/auth/drive.file';
  const GSI = 'https://accounts.google.com/gsi/client';
  const GAPI = 'https://apis.google.com/js/api.js';
  const configurado = () => !!(C.clientId && C.apiKey && C.appId);
  let carga = null, cargado = false, token = null, vence = 0, ocupado = false;

  function script(src) {
    return new Promise((ok, mal) => {
      const s = document.createElement('script');
      s.src = src; s.async = true;
      s.onload = ok; s.onerror = () => { s.remove(); mal(new Error('sin conexión con Google')); };
      document.head.appendChild(s);
    });
  }
  /* Carga las bibliotecas de Google una sola vez. Se llama al mostrar Importar, para que el toque
   * del botón pueda abrir la ventana de autorización de inmediato (si no, el navegador la bloquea). */
  function cargar() {
    if (carga) return carga;
    carga = Promise.all([
      script(GSI),
      script(GAPI).then(() => new Promise((ok, mal) => gapi.load('picker', { callback: ok, onerror: () => mal(new Error('no cargó el selector de Drive')) })))
    ]).then(() => { cargado = true; });
    carga.catch(() => { carga = null; });
    return carga;
  }
  function precargar() { if (configurado() && navigator.onLine !== false) cargar().catch(() => {}); }

  function pedirToken() {
    if (token && Date.now() < vence) return Promise.resolve(token);
    return new Promise((ok, mal) => {
      const cliente = google.accounts.oauth2.initTokenClient({
        client_id: C.clientId, scope: SCOPE,
        callback: r => {
          if (!r || r.error) return mal(new Error(r && r.error === 'access_denied' ? 'cancelado' : 'Google no autorizó el acceso' + (r && r.error ? ` (${r.error})` : '')));
          token = r.access_token; vence = Date.now() + ((Number(r.expires_in) || 3600) - 60) * 1000; ok(token);
        },
        error_callback: e => mal(new Error(e && e.type === 'popup_closed' ? 'cancelado' : 'no se abrió la ventana de Google' + (e && e.type ? ` (${e.type})` : '')))
      });
      cliente.requestAccessToken();
    });
  }

  function elegir(tok, op) {
    return new Promise(ok => {
      const P = google.picker;
      // Primero la carpeta de rutinas (si está configurada); después "Mi unidad" completa, por si el archivo está en otra parte
      // o la persona no tiene acceso a esa carpeta (por ejemplo, en el celular de la esposa).
      const vistas = [];
      const carpeta = op.carpeta === undefined ? C.carpetaRutinas : op.carpeta;
      if (carpeta) vistas.push(new P.DocsView(P.ViewId.DOCS).setParent(String(carpeta)).setMode(P.DocsViewMode.LIST));
      vistas.push(new P.DocsView(P.ViewId.DOCS).setIncludeFolders(true).setMode(P.DocsViewMode.LIST));
      const b = new P.PickerBuilder();
      vistas.forEach(v => b.addView(v));
      b.setOAuthToken(tok).setDeveloperKey(C.apiKey).setAppId(String(C.appId))
        .setLocale('es').setTitle(op.titulo || 'Elige la rutina (.json)')
        .setCallback(d => {
          const a = d[P.Response.ACTION];
          if (a === P.Action.PICKED) ok(d[P.Response.DOCUMENTS][0]);
          else if (a === P.Action.CANCEL) ok(null);
        })
        .build().setVisible(true);
    });
  }

  async function descargar(doc, tok) {
    const P = google.picker, id = encodeURIComponent(doc[P.Document.ID]), mime = doc[P.Document.MIME_TYPE] || '';
    const url = mime.startsWith('application/vnd.google-apps.')
      ? `https://www.googleapis.com/drive/v3/files/${id}/export?mimeType=text%2Fplain`
      : `https://www.googleapis.com/drive/v3/files/${id}?alt=media`;
    const r = await fetch(url, { headers: { Authorization: 'Bearer ' + tok } });
    if (r.status === 401) { token = null; throw new Error('la autorización venció; vuelve a tocar "Buscar en Drive"'); }
    if (!r.ok) throw new Error(`Drive respondió ${r.status} al bajar "${doc[P.Document.NAME] || 'el archivo'}"`);
    return { nombre: doc[P.Document.NAME] || '', texto: await r.text() };
  }

  const BOTONES = { 'data-drive': 'Buscar en Drive', 'data-drive-programa': 'Buscar en Drive', 'data-com-enviar': null };
  function marcar(texto, attr) {
    for (const a of attr ? [attr] : Object.keys(BOTONES)) {
      const b = document.querySelector('[' + a + ']');
      if (!b) continue;
      if (!b.dataset.textoBase) b.dataset.textoBase = BOTONES[a] || b.textContent;
      b.disabled = !!texto; b.textContent = texto || b.dataset.textoBase;
    }
  }

  /* alTexto(texto, nombre) recibe el contenido del archivo elegido; alError(mensaje) explica qué pasó.
   * op (opcional): { carpeta, titulo, attr } — carpeta inicial de la ventana de Drive, su título y el botón que la abre. */
  async function importar(alTexto, alError, op) {
    op = op || {}; const attr = op.attr || 'data-drive';
    const marcarB = t => marcar(t, attr);
    if (ocupado) return;
    if (!configurado()) return alError('"Buscar en Drive" todavía no está configurado en esta app.');
    if (navigator.onLine === false) return alError('Para buscar en Drive necesitas internet. Conéctate, o elige el archivo guardado en el celular.');
    if (!cargado) { marcarB('Conectando con Google…'); return cargar().then(() => marcarB(''), () => { marcarB(''); alError('No se pudo conectar con Google. Revisa la conexión y vuelve a tocar "Buscar en Drive", o elige el archivo guardado en el celular.'); }); }
    ocupado = true;
    try {
      const tok = await pedirToken();          // dentro del toque: abre la ventana de Google
      marcarB('Abriendo Drive…');
      const doc = await elegir(tok, op);
      if (!doc) return;                       // la persona cerró la ventana sin elegir
      marcarB('Bajando el archivo…');
      const { nombre, texto } = await descargar(doc, tok);
      if (!String(texto).trim()) return alError(`El archivo "${nombre}" está vacío en Drive.`);
      alTexto(texto, nombre);
    } catch (e) {
      if (e && e.message === 'cancelado') return;
      alError('No se pudo traer el archivo desde Drive: ' + (e && e.message || e) + '. También puedes elegir el archivo guardado en el celular o pegar el contenido.');
    } finally {
      ocupado = false; marcarB('');
    }
  }

  function boton(op) {
    op = op || {}; const attr = op.attr || 'data-drive';
    if (!configurado()) return `<div class="campo"><button class="btn" disabled ${attr}-off>Buscar en Drive (falta configurar)</button></div>`;
    // Hasta que Google termine de cargar, el botón queda en espera: así el toque siempre puede abrir la ventana de autorización.
    // Si la primera carga falla (por ejemplo, la red estaba cambiando), se reintenta una vez antes de liberar el botón.
    if (!cargado) setTimeout(() => { if (navigator.onLine === false) return marcar(''); cargar().catch(() => new Promise(r => setTimeout(r, 1500)).then(cargar)).then(() => marcar(''), () => marcar('')); }, 0);
    const listo = cargado || navigator.onLine === false;
    return `<div class="campo"><button class="btn" ${attr} ${listo ? '' : 'disabled'}>${listo ? 'Buscar en Drive' : 'Conectando con Google…'}</button><p class="muted" style="margin:6px 0 0">Necesita internet. La app solo puede leer el archivo que elijas.</p></div>`;
  }


  /* ---------- subir el registro de comidas ---------- */
  const API = 'https://www.googleapis.com/drive/v3/files', SUBIDA = 'https://www.googleapis.com/upload/drive/v3/files';
  async function api(tok, url, init) {
    const r = await fetch(url, Object.assign({}, init, { headers: Object.assign({ Authorization: 'Bearer ' + tok }, (init && init.headers) || {}) }));
    if (r.status === 401) { token = null; throw new Error('la autorización venció; vuelve a tocar el botón'); }
    if (!r.ok) throw new Error('Drive respondió ' + r.status);
    return r.json();
  }
  /* La carpeta la crea la app (así el permiso drive.file alcanza para escribir en ella). Se busca por nombre antes de crearla. */
  async function carpetaPropia(tok, nombre) {
    const q = encodeURIComponent(`name='${nombre.replace(/'/g, "\\'")}' and mimeType='application/vnd.google-apps.folder' and trashed=false`);
    const l = await api(tok, `${API}?q=${q}&fields=files(id,name)&spaces=drive`);
    if (l.files && l.files.length) return l.files[0].id;
    const c = await api(tok, API + '?fields=id', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: nombre, mimeType: 'application/vnd.google-apps.folder' }) });
    return c.id;
  }
  async function subirArchivo(tok, carpeta, a) {
    const meta = { name: a.nombre, parents: [carpeta], mimeType: a.mime };
    const fd = new FormData();
    fd.append('metadata', new Blob([JSON.stringify(meta)], { type: 'application/json; charset=UTF-8' }));
    fd.append('file', a.blob || new Blob([a.texto], { type: a.mime }));
    return api(tok, SUBIDA + '?uploadType=multipart&fields=id,name', { method: 'POST', body: fd });
  }
  /* archivos: [{ nombre, mime, blob | texto, clave }]. alListo(clave, id) se llama por cada archivo subido (permite guardar el avance).
   * Debe llamarse dentro de un toque (puede abrir la ventana de autorización). */
  async function subir(archivos, op) {
    op = op || {}; const attr = op.attr || 'data-com-enviar';
    if (!configurado()) throw new Error('"Drive" todavía no está configurado en esta app');
    if (navigator.onLine === false) throw new Error('sin internet');
    if (!cargado) { marcar('Conectando con Google…', attr); await cargar().finally(() => marcar('', attr)); throw new Error('Google recién terminó de cargar: vuelve a tocar el botón'); }
    if (ocupado) throw new Error('hay otra operación con Drive en curso');
    ocupado = true;
    try {
      const tok = await pedirToken();
      marcar('Preparando carpeta…', attr);
      const carpeta = await carpetaPropia(tok, op.carpeta || 'Genesis Gym - registro comidas');
      let n = 0;
      for (const a of archivos) {
        marcar(`Subiendo ${++n} de ${archivos.length}…`, attr);
        const r = await subirArchivo(tok, carpeta, a);
        if (op.alListo) await op.alListo(a.clave, r.id);
      }
      return n;
    } finally { ocupado = false; marcar('', attr); }
  }

  self.GGDrive = { configurado, boton, importar, subir, precargar, listo: () => cargado };
})();
