/* Génesis Gym 2.0 — base de datos local (IndexedDB).
 *
 * Imita la forma de la base que usaba la 1.0 dentro de Claude, para reutilizar sus pantallas sin cambios:
 *   db.doc('coleccion/id').get() / .set(datos) / .delete() / .onSnapshot(fn, err)
 *   db.collection('coleccion').get() / .onSnapshot(fn, err)
 * Los documentos se guardan con la misma ruta que en la 1.0 ("sesiones/2026-10-05_sesion-lunes"),
 * así la migración es 1:1. Todo queda en este celular; no hay servidor ni sincronización.
 */
(function () {
  'use strict';
  const NOMBRE = 'genesis-gym-2';
  const STORE = 'docs';
  let abierta = null;      // Promise<IDBDatabase>
  let instancia = null;    // objeto db único
  const subs = new Set();  // { tipo: 'doc'|'col', ruta, fn }
  const canal = ('BroadcastChannel' in self) ? new BroadcastChannel('genesis-gym-2-db') : null;

  function err(codigo, causa) {
    const e = new Error(codigo + (causa && causa.message ? ': ' + causa.message : ''));
    e.code = codigo; e.causa = causa; return e;
  }
  function codigoDe(e) {
    const n = e && e.name;
    if (n === 'QuotaExceededError') return 'quota_exceeded';
    if (n === 'DataCloneError') return 'invalid_argument';
    return 'unavailable';
  }
  function partes(ruta) {
    const p = String(ruta).split('/');
    if (p.length !== 2 || !p[0] || !p[1]) throw err('invalid_argument', new Error('ruta no válida: ' + ruta));
    return { col: p[0], id: p[1] };
  }
  const clonar = v => v === undefined ? undefined : JSON.parse(JSON.stringify(v));

  function abrir() {
    if (abierta) return abierta;
    abierta = new Promise((ok, mal) => {
      if (!('indexedDB' in self)) return mal(err('unavailable', new Error('IndexedDB no disponible')));
      const req = indexedDB.open(NOMBRE, 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(STORE)) {
          const st = db.createObjectStore(STORE, { keyPath: 'ruta' });
          st.createIndex('col', 'col', { unique: false });
        }
      };
      req.onsuccess = () => {
        const db = req.result;
        db.onversionchange = () => { db.close(); abierta = null; };
        ok(db);
      };
      req.onerror = () => mal(err('unavailable', req.error));
      req.onblocked = () => mal(err('unavailable', new Error('base bloqueada por otra pestaña')));
    });
    abierta.catch(() => { abierta = null; });
    return abierta;
  }

  async function tx(modo, fn) {
    const db = await abrir();
    return new Promise((ok, mal) => {
      let t;
      try { t = db.transaction(STORE, modo); } catch (e) { return mal(err(codigoDe(e), e)); }
      const st = t.objectStore(STORE);
      let resultado;
      try { resultado = fn(st); } catch (e) { try { t.abort(); } catch (_) {} return mal(err(codigoDe(e), e)); }
      t.oncomplete = () => ok(resultado && 'result' in resultado ? resultado.result : undefined);
      t.onerror = () => mal(err(codigoDe(t.error), t.error));
      t.onabort = () => mal(err(codigoDe(t.error), t.error));
    });
  }

  const snapDoc = (ruta, reg) => ({
    id: partes(ruta).id, exists: !!reg,
    data: () => reg ? clonar(reg.data) : undefined
  });
  async function leerDoc(ruta) { partes(ruta); return snapDoc(ruta, await tx('readonly', st => st.get(ruta))); }
  async function leerCol(col) {
    const regs = await tx('readonly', st => st.index('col').getAll(IDBKeyRange.only(col)));
    return { docs: (regs || []).map(r => snapDoc(r.ruta, r)) };
  }

  function avisar(ruta, local) {
    const { col } = partes(ruta);
    for (const s of subs) {
      if (s.tipo === 'doc' && s.ruta === ruta) leerDoc(ruta).then(s.fn, s.err);
      if (s.tipo === 'col' && s.ruta === col) leerCol(col).then(s.fn, s.err);
    }
    if (local && canal) { try { canal.postMessage(ruta); } catch (_) {} }
  }
  if (canal) canal.onmessage = ev => { try { avisar(ev.data, false); } catch (_) {} };

  function doc(ruta) {
    partes(ruta);
    return {
      id: partes(ruta).id,
      get: () => leerDoc(ruta),
      async set(datos) {
        if (datos === null || typeof datos !== 'object' || Array.isArray(datos)) throw err('invalid_argument', new Error('el documento debe ser un objeto'));
        const { col, id } = partes(ruta);
        await tx('readwrite', st => st.put({ ruta, col, id, data: clonar(datos), actualizado_local: new Date().toISOString() }));
        avisar(ruta, true);
      },
      async delete() {
        await tx('readwrite', st => st.delete(ruta));
        avisar(ruta, true);
      },
      onSnapshot(fn, alError) {
        const s = { tipo: 'doc', ruta, fn, err: alError || (() => {}) };
        subs.add(s);
        leerDoc(ruta).then(fn, s.err);
        return () => subs.delete(s);
      }
    };
  }

  function collection(col) {
    if (!col || String(col).includes('/')) throw err('invalid_argument', new Error('colección no válida: ' + col));
    return {
      get: () => leerCol(col),
      onSnapshot(fn, alError) {
        const s = { tipo: 'col', ruta: col, fn, err: alError || (() => {}) };
        subs.add(s);
        leerCol(col).then(fn, s.err);
        return () => subs.delete(s);
      },
      orderBy() { return this; }, limit() { return this; }
    };
  }

  /* Lectura y escritura masiva (para respaldo, restauración y migración en los pasos 3 y 4). */
  async function todos() {
    const regs = await tx('readonly', st => st.getAll());
    return (regs || []).map(r => ({ ruta: r.ruta, data: clonar(r.data) }));
  }

  self.GGDB = {
    nombre: NOMBRE,
    abrir: async () => { await abrir(); if (!instancia) instancia = { doc, collection }; return instancia; },
    todos
  };
})();
