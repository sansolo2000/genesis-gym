/* Génesis Gym 2.0 — importar la rutina desde Google Drive.
 *
 * Permiso mínimo (drive.file): la app solo puede leer el archivo que la persona elige en la ventana de Google.
 * No ve el resto del Drive ni carpetas. Nada se sube a Drive.
 * Solo se conecta a Google cuando hay configuración (google-config.js) y la persona está en la pantalla Importar.
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

  function elegir(tok) {
    return new Promise(ok => {
      const P = google.picker;
      const vista = new P.DocsView(P.ViewId.DOCS).setIncludeFolders(true).setMode(P.DocsViewMode.LIST);
      new P.PickerBuilder()
        .addView(vista).setOAuthToken(tok).setDeveloperKey(C.apiKey).setAppId(String(C.appId))
        .setLocale('es').setTitle('Elige la rutina (.json)')
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

  function marcar(texto) {
    const b = document.querySelector('[data-drive]');
    if (b) { b.disabled = !!texto; b.textContent = texto || 'Buscar en Drive'; }
  }

  /* alTexto(texto, nombre) recibe el contenido del archivo elegido; alError(mensaje) explica qué pasó. */
  async function importar(alTexto, alError) {
    if (ocupado) return;
    if (!configurado()) return alError('"Buscar en Drive" todavía no está configurado en esta app.');
    if (navigator.onLine === false) return alError('Para buscar en Drive necesitas internet. Conéctate, o elige el archivo guardado en el celular.');
    if (!cargado) { marcar('Conectando con Google…'); return cargar().then(() => marcar(''), () => { marcar(''); alError('No se pudo conectar con Google. Revisa la conexión y vuelve a tocar "Buscar en Drive", o elige el archivo guardado en el celular.'); }); }
    ocupado = true;
    try {
      const tok = await pedirToken();          // dentro del toque: abre la ventana de Google
      marcar('Abriendo Drive…');
      const doc = await elegir(tok);
      if (!doc) return;                       // la persona cerró la ventana sin elegir
      marcar('Bajando el archivo…');
      const { nombre, texto } = await descargar(doc, tok);
      if (!String(texto).trim()) return alError(`El archivo "${nombre}" está vacío en Drive.`);
      alTexto(texto, nombre);
    } catch (e) {
      if (e && e.message === 'cancelado') return;
      alError('No se pudo traer el archivo desde Drive: ' + (e && e.message || e) + '. También puedes elegir el archivo guardado en el celular o pegar el contenido.');
    } finally {
      ocupado = false; marcar('');
    }
  }

  function boton() {
    if (!configurado()) return `<div class="campo"><button class="btn" disabled data-drive-off>Buscar en Drive (falta configurar)</button></div>`;
    // Hasta que Google termine de cargar, el botón queda en espera: así el toque siempre puede abrir la ventana de autorización.
    // Si la primera carga falla (por ejemplo, la red estaba cambiando), se reintenta una vez antes de liberar el botón.
    if (!cargado) setTimeout(() => { if (navigator.onLine === false) return marcar(''); cargar().catch(() => new Promise(r => setTimeout(r, 1500)).then(cargar)).then(() => marcar(''), () => marcar('')); }, 0);
    const listo = cargado || navigator.onLine === false;
    return `<div class="campo"><button class="btn" data-drive ${listo ? '' : 'disabled'}>${listo ? 'Buscar en Drive' : 'Conectando con Google…'}</button><p class="muted" style="margin:6px 0 0">Necesita internet. La app solo puede leer el archivo que elijas.</p></div>`;
  }

  self.GGDrive = { configurado, boton, importar, precargar, listo: () => cargado };
})();
