/* Dobles de prueba de las bibliotecas de Google (identidad y selector de Drive). Los usan test-drive y test-comidas. */
const GSI = `window.google = window.google || {};
google.accounts = { oauth2: { initTokenClient(o) { return { requestAccessToken() {
  window.__pedidosToken = (window.__pedidosToken || 0) + 1;
  const r = window.__respToken || { access_token: 'tok-prueba', expires_in: 3600 };
  setTimeout(() => r.cerrada ? o.error_callback({ type: 'popup_closed' }) : o.callback(r), 10);
} }; } } };`;
const GAPI = `window.gapi = { load(n, o) {
  class V { constructor(id) { this.id = id; } setParent(p) { this.padre = p; return this; } setIncludeFolders() { return this; } setMode() { return this; } setMimeTypes() { return this; } }
  class B { constructor() { this.c = {}; }
    addView(v) { (this.c.vistas = this.c.vistas || []).push(v.padre || 'mi-unidad'); return this; } setOAuthToken(t) { this.c.token = t; return this; } setDeveloperKey(k) { this.c.clave = k; return this; }
    setAppId(a) { this.c.app = a; return this; } setLocale() { return this; } setTitle() { return this; } setCallback(f) { this.cb = f; return this; }
    build() { const yo = this; return { setVisible() { window.__picker = yo.c; setTimeout(() => yo.cb(window.__respPicker || { action: 'picked', docs: [{ id: 'ID1', name: 'rutina.json', mimeType: 'application/json' }] }), 10); } }; } }
  google.picker = { DocsView: V, PickerBuilder: B, ViewId: { DOCS: 'all' }, DocsViewMode: { LIST: 'list' },
    Response: { ACTION: 'action', DOCUMENTS: 'docs' }, Action: { PICKED: 'picked', CANCEL: 'cancel' },
    Document: { ID: 'id', NAME: 'name', MIME_TYPE: 'mimeType' } };
  setTimeout(o.callback, 0);
} };`;
module.exports = { GSI, GAPI };
