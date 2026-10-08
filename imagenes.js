/* Génesis Gym 2.0 — imágenes de los ejercicios.
 * Las imágenes vienen incluidas en la app (carpeta imagenes/, sin modificar, con crédito), según el
 * universo de ejercicios de Entrenamiento. Al abrir, se cargan en la colección "imagenes" de la base
 * local, donde las busca la pantalla de la 1.0.
 * Para los ejercicios del catálogo, el catálogo manda: reemplaza la imagen que haya en la base
 * (por ejemplo, la que vino de la migración desde la 1.0). Las imágenes de ejercicios que NO están en el
 * catálogo no se tocan. Las imágenes del catálogo se guardan para usarse sin internet (sw.js). */
(function () {
  'use strict';
  const ORIGEN = 'catalogo-2.0';
  async function cargar() {
    try {
      const r = await fetch('imagenes/catalogo.json');
      if (!r.ok) return;
      const cat = await r.json();
      self.GGCatalogo = cat;
      const db = await self.GGDB.abrir();
      const actuales = new Map((await db.collection('imagenes').get()).docs.map(d => [d.id, d.data()]));
      const poner = [];
      for (const [id, meta] of Object.entries(cat.imagenes || {})) {
        const d = actuales.get(id);
        if (d && d.origen === ORIGEN && d.catalogo_version === cat.version) continue; // ya está al día
        poner.push({ ruta: 'imagenes/' + id, data: Object.assign({}, meta, { origen: ORIGEN, catalogo_version: cat.version }) });
      }
      await self.GGDB.ponerVarios(poner);
    } catch (_) { /* sin imágenes no se bloquea nada */ }
  }
  cargar();
})();
