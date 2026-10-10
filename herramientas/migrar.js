#!/usr/bin/env node
/* Migración Génesis Gym 1.0 → 2.0.
 *
 * Uso:  node migrar.js <carpeta con la exportación de la base 1.0> <archivo de salida .json>
 *
 * La carpeta de entrada tiene un .json por documento, en <coleccion>/<id>.json (así la deja la lectura
 * de solo lectura de la base del artifact 1.0). El resultado es un archivo en el MISMO formato del
 * respaldo de la 2.0 ("genesis-respaldo" v1), que se carga en el celular con Historial → Restaurar.
 *
 * No modifica ni interpreta los datos: copia cada documento tal cual (incluidos campos no documentados
 * como nota_fecha, cambios_fecha o cargada_por). Imprime un informe para verificar contra la 1.0.
 * El archivo resultante contiene datos personales: nunca debe subirse al repositorio público.
 */
const fs = require('fs'), path = require('path'), crypto = require('crypto');

const COLECCIONES = ['config', 'rutinas', 'sesiones', 'imagenes'];
const RUTA = /^[A-Za-z0-9_\-.~:@+]{1,200}\/[A-Za-z0-9_\-.~:@+]{1,200}$/;

function huella(docs) {
  const orden = [...docs].sort((a, b) => a.ruta < b.ruta ? -1 : a.ruta > b.ruta ? 1 : 0);
  return crypto.createHash('sha256').update(JSON.stringify(orden.map(d => ({ ruta: d.ruta, data: d.data })))).digest('hex');
}

function main() {
  const [entrada, salida] = process.argv.slice(2);
  if (!entrada || !salida) { console.error('Uso: node migrar.js <carpeta exportación 1.0> <salida.json>'); process.exit(2); }
  const docs = [];
  for (const col of fs.readdirSync(entrada).sort()) {
    const dir = path.join(entrada, col);
    if (!fs.statSync(dir).isDirectory()) continue;
    if (!COLECCIONES.includes(col)) { console.error(`ERROR: colección desconocida "${col}". No se generó nada.`); process.exit(1); }
    for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.json')).sort()) {
      const ruta = `${col}/${f.slice(0, -5)}`;
      if (!RUTA.test(ruta)) { console.error(`ERROR: ruta no válida "${ruta}".`); process.exit(1); }
      const data = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
      if (!data || typeof data !== 'object' || Array.isArray(data)) { console.error(`ERROR: "${ruta}" no es un objeto.`); process.exit(1); }
      docs.push({ ruta, data });
    }
  }
  docs.sort((a, b) => a.ruta < b.ruta ? -1 : a.ruta > b.ruta ? 1 : 0);
  const conteos = {}; for (const c of COLECCIONES) conteos[c] = docs.filter(d => d.ruta.startsWith(c + '/')).length;
  const archivo = {
    formato: 'genesis-respaldo', version: 1,
    app: 'migracion-desde-genesis-gym-1.0', version_app: null,
    exportado_en: new Date().toISOString(),
    perfil: null,
    conteos,
    huella_sha256: huella(docs),
    documentos: docs
  };
  fs.writeFileSync(salida, JSON.stringify(archivo, null, 1));

  // Informe de verificación
  console.log(`Archivo: ${salida}`);
  console.log(`Documentos: ${docs.length} · ` + COLECCIONES.map(c => `${c} ${conteos[c]}`).join(' · '));
  console.log(`Huella SHA-256: ${archivo.huella_sha256}`);
  console.log('Sesiones (fecha · sesión · estado · series registradas/total · suma de cargas reales kg · RPE · min):');
  let tReg = 0, tSer = 0, tKg = 0;
  for (const d of docs.filter(d => d.ruta.startsWith('sesiones/'))) {
    const s = d.data, series = (s.ejercicios || []).flatMap(e => e.series || []);
    const reg = series.filter(x => x.carga_real_kg != null || x.reps_reales != null || x.seg_reales != null);
    const kg = series.reduce((a, x) => a + (typeof x.carga_real_kg === 'number' ? x.carga_real_kg : 0), 0);
    tReg += reg.length; tSer += series.length; tKg += kg;
    console.log(`  ${s.fecha} · ${s.sesion_id} · ${s.estado} · ${reg.length}/${series.length} · ${kg} · ${s.rpe_sesion ?? '—'} · ${s.duracion_min ?? '—'}`);
  }
  console.log(`Totales: ${tReg}/${tSer} series registradas · ${tKg} kg sumados`);
}
main();
