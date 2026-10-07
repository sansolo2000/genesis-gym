/* Génesis Gym 2.0 — imágenes de los ejercicios.
 * Las imágenes vienen incluidas en la app (carpeta imagenes/, sin modificar, con crédito).
 * Al abrir, se cargan en la colección "imagenes" de la base local, donde las busca la pantalla de la 1.0.
 * No se pisan imágenes que vengan de otra fuente (por ejemplo, de la migración desde la 1.0). */
(function () {
  'use strict';
  const ORIGEN = 'catalogo-2.0';
  async function cargar() {
    try {
      const r = await fetch('imagenes/catalogo.json');
      if (!r.ok) return;
      const cat = await r.json();
      const db = await self.GGDB.abrir();
      for (const [id, meta] of Object.entries(cat.imagenes || {})) {
        const ref = db.doc('imagenes/' + id);
        const actual = await ref.get();
        if (actual.exists) {
          const d = actual.data();
          if (d.origen !== ORIGEN) continue;                 // vino de otra fuente: no se toca
          if (d.catalogo_version === cat.version) continue;  // ya está al día
        }
        await ref.set(Object.assign({}, meta, { origen: ORIGEN, catalogo_version: cat.version }));
      }
    } catch (_) { /* sin imágenes no se bloquea nada */ }
  }
  cargar();
})();
