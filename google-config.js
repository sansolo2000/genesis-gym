/* Datos del proyecto de Google Cloud de Héctor para "Buscar en Drive".
 * No son secretos: Google los expone en cualquier app web. Pero la clave API debe quedar restringida
 * al sitio https://sansolo2000.github.io/* y a la Google Picker API, y el ID de cliente OAuth debe tener
 * como origen autorizado https://sansolo2000.github.io.
 * Mientras estén vacíos, el botón "Buscar en Drive" aparece desactivado y la app no se conecta a Google. */
self.GG_GOOGLE = {
  clientId: '854756460668-c0rtpp90dhhh611kfem3hktf0i38bljp.apps.googleusercontent.com',  // ID de cliente OAuth 2.0 (tipo "Aplicación web"), termina en .apps.googleusercontent.com
  apiKey: 'AIzaSyBbDl2y28vqfS9OnyOo2F5sAL0Thsh5OZ4',    // Clave de API restringida a la Google Picker API
  appId: '854756460668',     // Número del proyecto de Google Cloud (solo dígitos)
  carpetaRutinas: '1U6dOaWpjtAuMBKyzDtHkJzyUrBXSiCFa'  // Carpeta "Genesis Gym/rutinas" del Drive de Héctor: la ventana de Drive abre ahí
};
