/* Validador del contrato FORMATO-RUTINA v1.0 — Proyecto Génesis.
 * Función pura: validarRutina(obj) -> { ok, errores: string[], avisos: string[] }.
 * Regla: nunca completa ni corrige datos. Cualquier error => rutina rechazada. */
function validarRutina(r) {
  const E = [], A = [];
  const DIAS = ['lunes','martes','miercoles','jueves','viernes','sabado','domingo'];
  const SLUG = /^[a-z0-9][a-z0-9-]{1,60}$/;
  const isObj = v => v !== null && typeof v === 'object' && !Array.isArray(v);
  const isStr = v => typeof v === 'string' && v.trim().length > 0;
  const isInt = (v, min, max) => Number.isInteger(v) && v >= min && v <= max;
  const isNum = (v, min, max) => typeof v === 'number' && isFinite(v) && v >= min && v <= max;
  const isFecha = v => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) &&
    !isNaN(Date.parse(v + 'T12:00:00Z')) && new Date(v + 'T12:00:00Z').toISOString().slice(0,10) === v;
  function claves(o, ruta, req, opt) {
    for (const k of req) if (!(k in o)) E.push(`${ruta}: falta el campo obligatorio "${k}"`);
    for (const k of Object.keys(o)) if (!req.includes(k) && !opt.includes(k)) E.push(`${ruta}: campo no reconocido "${k}" (¿error de escritura?)`);
  }
  function listaStr(v, ruta, min) {
    if (!Array.isArray(v)) { E.push(`${ruta}: debe ser una lista de textos`); return; }
    if (v.length < min) E.push(`${ruta}: debe tener al menos ${min} elemento(s)`);
    v.forEach((s, i) => { if (!isStr(s)) E.push(`${ruta}[${i}]: debe ser un texto no vacío`); });
  }

  if (!isObj(r)) return { ok: false, errores: ['El archivo no contiene un objeto JSON'], avisos: [] };
  claves(r, 'raíz', ['formato','version_formato','rutina','sesiones','ejercicios'], []);
  if ('formato' in r && r.formato !== 'genesis-rutina') E.push('raíz.formato: debe ser "genesis-rutina"');
  if ('version_formato' in r && r.version_formato !== '1.0') E.push('raíz.version_formato: esta app acepta solo "1.0"');

  // rutina
  if ('rutina' in r) {
    const ru = r.rutina;
    if (!isObj(ru)) E.push('rutina: debe ser un objeto');
    else {
      claves(ru, 'rutina', ['id','nombre','version','vigente_desde','autor'], ['notas']);
      if ('id' in ru && !(typeof ru.id === 'string' && SLUG.test(ru.id))) E.push('rutina.id: solo minúsculas, números y guiones (2–61 caracteres)');
      if ('nombre' in ru && !isStr(ru.nombre)) E.push('rutina.nombre: texto no vacío');
      if ('version' in ru && !isInt(ru.version, 1, 9999)) E.push('rutina.version: entero ≥ 1');
      if ('vigente_desde' in ru && !isFecha(ru.vigente_desde)) E.push('rutina.vigente_desde: fecha válida AAAA-MM-DD');
      if ('autor' in ru && ru.autor !== 'Entrenamiento') E.push('rutina.autor: debe ser "Entrenamiento"');
      if ('notas' in ru && typeof ru.notas !== 'string') E.push('rutina.notas: debe ser texto');
    }
  }

  // ejercicios (catálogo)
  const idsEj = new Set();
  if ('ejercicios' in r) {
    if (!Array.isArray(r.ejercicios) || r.ejercicios.length === 0) E.push('ejercicios: lista con al menos 1 ejercicio');
    else r.ejercicios.forEach((ej, i) => {
      const p = `ejercicios[${i}]` + (ej && ej.id ? ` (${ej.id})` : '');
      if (!isObj(ej)) { E.push(`${p}: debe ser un objeto`); return; }
      claves(ej, p, ['id','nombre','descripcion','musculos_principales','musculos_secundarios','equipo','pasos','errores_comunes'], ['precauciones']);
      if ('id' in ej) {
        if (!(typeof ej.id === 'string' && SLUG.test(ej.id))) E.push(`${p}.id: solo minúsculas, números y guiones`);
        else if (idsEj.has(ej.id)) E.push(`${p}.id: "${ej.id}" está repetido`);
        else idsEj.add(ej.id);
      }
      for (const k of ['nombre','descripcion','equipo']) if (k in ej && !isStr(ej[k])) E.push(`${p}.${k}: texto no vacío`);
      if ('musculos_principales' in ej) listaStr(ej.musculos_principales, `${p}.musculos_principales`, 1);
      if ('musculos_secundarios' in ej) listaStr(ej.musculos_secundarios, `${p}.musculos_secundarios`, 0);
      if ('pasos' in ej) listaStr(ej.pasos, `${p}.pasos`, 2);
      if ('errores_comunes' in ej) listaStr(ej.errores_comunes, `${p}.errores_comunes`, 1);
      if ('precauciones' in ej) listaStr(ej.precauciones, `${p}.precauciones`, 0);
    });
  }

  // sesiones
  const usados = new Set();
  if ('sesiones' in r) {
    if (!Array.isArray(r.sesiones) || r.sesiones.length === 0) E.push('sesiones: lista con al menos 1 sesión');
    else {
      const idsSes = new Set(), dias = new Set();
      r.sesiones.forEach((s, i) => {
        const p = `sesiones[${i}]` + (s && s.id ? ` (${s.id})` : '');
        if (!isObj(s)) { E.push(`${p}: debe ser un objeto`); return; }
        claves(s, p, ['id','dia_semana','nombre','duracion_estimada_min','ejercicios'], ['notas']);
        if ('id' in s) {
          if (!(typeof s.id === 'string' && SLUG.test(s.id))) E.push(`${p}.id: solo minúsculas, números y guiones`);
          else if (idsSes.has(s.id)) E.push(`${p}.id: "${s.id}" está repetido`); else idsSes.add(s.id);
        }
        if ('dia_semana' in s) {
          if (!DIAS.includes(s.dia_semana)) E.push(`${p}.dia_semana: uno de ${DIAS.join(', ')} (sin tildes)`);
          else if (dias.has(s.dia_semana)) E.push(`${p}.dia_semana: "${s.dia_semana}" tiene más de una sesión`);
          else dias.add(s.dia_semana);
        }
        if ('nombre' in s && !isStr(s.nombre)) E.push(`${p}.nombre: texto no vacío`);
        if ('duracion_estimada_min' in s && !isInt(s.duracion_estimada_min, 10, 180)) E.push(`${p}.duracion_estimada_min: entero entre 10 y 180`);
        if ('notas' in s && typeof s.notas !== 'string') E.push(`${p}.notas: debe ser texto`);
        if (!('ejercicios' in s)) return;
        if (!Array.isArray(s.ejercicios) || s.ejercicios.length === 0) { E.push(`${p}.ejercicios: al menos 1`); return; }
        const ordenes = new Set();
        s.ejercicios.forEach((x, j) => {
          const q = `${p}.ejercicios[${j}]`;
          if (!isObj(x)) { E.push(`${q}: debe ser un objeto`); return; }
          claves(x, q, ['ejercicio_id','orden','descanso_seg','series'], ['notas']);
          if ('ejercicio_id' in x) {
            if (!idsEj.has(x.ejercicio_id)) E.push(`${q}.ejercicio_id: "${x.ejercicio_id}" no existe en el catálogo "ejercicios"`);
            else usados.add(x.ejercicio_id);
          }
          if ('orden' in x) {
            if (!isInt(x.orden, 1, 50)) E.push(`${q}.orden: entero ≥ 1`);
            else if (ordenes.has(x.orden)) E.push(`${q}.orden: ${x.orden} repetido en la sesión`); else ordenes.add(x.orden);
          }
          if ('descanso_seg' in x && !isInt(x.descanso_seg, 0, 600)) E.push(`${q}.descanso_seg: entero entre 0 y 600`);
          if ('notas' in x && typeof x.notas !== 'string') E.push(`${q}.notas: debe ser texto`);
          if (!('series' in x)) return;
          if (!Array.isArray(x.series) || x.series.length === 0 || x.series.length > 10) { E.push(`${q}.series: entre 1 y 10 series`); return; }
          x.series.forEach((se, k) => {
            const t = `${q}.series[${k}]`;
            if (!isObj(se)) { E.push(`${t}: debe ser un objeto`); return; }
            claves(se, t, ['n','tipo','carga_kg'], ['reps','reps_min','reps_max','duracion_seg','carga_indicacion','rpe_objetivo']);
            if ('n' in se && se.n !== k + 1) E.push(`${t}.n: debe ser ${k + 1} (numeración correlativa desde 1)`);
            if ('tipo' in se && !['calentamiento','efectiva'].includes(se.tipo)) E.push(`${t}.tipo: "calentamiento" o "efectiva"`);
            const modos = [('reps' in se), ('reps_min' in se || 'reps_max' in se), ('duracion_seg' in se)].filter(Boolean).length;
            if (modos !== 1) E.push(`${t}: indicar exactamente UNA de estas opciones: "reps", o "reps_min"+"reps_max", o "duracion_seg"`);
            if ('reps' in se && !isInt(se.reps, 1, 100)) E.push(`${t}.reps: entero entre 1 y 100`);
            if ('reps_min' in se || 'reps_max' in se) {
              if (!isInt(se.reps_min, 1, 100) || !isInt(se.reps_max, 1, 100) || se.reps_min >= se.reps_max)
                E.push(`${t}: "reps_min" y "reps_max" enteros (1–100) con reps_min < reps_max`);
            }
            if ('duracion_seg' in se && !isInt(se.duracion_seg, 5, 600)) E.push(`${t}.duracion_seg: entero entre 5 y 600`);
            if ('carga_kg' in se) {
              if (se.carga_kg === null) {
                if (!isStr(se.carga_indicacion)) E.push(`${t}: si "carga_kg" es null, "carga_indicacion" es obligatoria (p. ej. "peso corporal")`);
              } else if (!isNum(se.carga_kg, 0, 500)) E.push(`${t}.carga_kg: número entre 0 y 500, o null`);
            }
            if ('carga_indicacion' in se && !isStr(se.carga_indicacion)) E.push(`${t}.carga_indicacion: texto no vacío`);
            if ('rpe_objetivo' in se && !isNum(se.rpe_objetivo, 1, 10)) E.push(`${t}.rpe_objetivo: número entre 1 y 10`);
          });
        });
      });
      if (!['lunes','miercoles','viernes'].every(d => dias.has(d))) A.push('No hay sesión para todos los días de fuerza del plan (lunes, miércoles y viernes)');
    }
  }
  for (const id of idsEj) if (!usados.has(id)) A.push(`El ejercicio "${id}" está en el catálogo pero ninguna sesión lo usa`);
  return { ok: E.length === 0, errores: E, avisos: A };
}
if (typeof module !== 'undefined') module.exports = { validarRutina };
