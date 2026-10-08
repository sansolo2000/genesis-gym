/* Génesis Gym 2.0 — módulo Comidas (Alimentación).
 *
 * - Importa el programa de alimentación (formato "programa-alimentacion" 1.0, lo escribe el chat Alimentación).
 * - Muestra el día: 5 comidas con hora, preparación, porciones y kcal estimadas, más las tareas del día.
 * - Por cada comida se marca: "Comí lo indicado", "Comí otra cosa o parcial" (pide foto y nota) o "No comí".
 * - "Enviar a Alimentación" sube a Drive (carpeta "Genesis Gym - registro comidas", creada por la app) un JSON
 *   por comida marcada y la foto, para que el chat Alimentación revise lo consumido.
 * La app muestra los números del programa tal cual: no calcula calorías nuevas ni corrige el menú.
 * El programa y las fotos son datos de salud: viven solo en este celular, en su respaldo y en el Drive de la persona.
 */
(function () {
  'use strict';
  const TIPOS = ['desayuno', 'colacion_am', 'almuerzo', 'colacion_pm', 'cena'];
  const ETQ = { desayuno: 'Desayuno', colacion_am: 'Colación AM', almuerzo: 'Almuerzo', colacion_pm: 'Colación PM', cena: 'Cena' };
  const ESTADOS = { indicado: 'Comí lo indicado', otro: 'Comí otra cosa o parcial', 'no-comi': 'No comí' };
  const M = { cargado: false, cargando: false, programa: null, perfil: null, registros: {}, fotos: {}, dia: null,
              importacion: null, verImport: false, notas: {}, mensaje: null, error: null,
              cambios: {},          // intercambios: { 'fecha_tipo': { preparacion, conservacion, desde: 'fecha_tipo' } }
              eligiendo: null };    // comida para la que se está eligiendo con cuál intercambiar

  const e = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const dosD = n => String(n).padStart(2, '0');
  const hoyISO = () => { const d = new Date(); return `${d.getFullYear()}-${dosD(d.getMonth() + 1)}-${dosD(d.getDate())}`; };
  const fechaOk = f => /^\d{4}-\d{2}-\d{2}$/.test(f) && !isNaN(new Date(f + 'T12:00:00'));
  const horaOk = h => /^([01]\d|2[0-3]):[0-5]\d$/.test(h);
  const sumarDias = (f, n) => { const d = new Date(f + 'T12:00:00'); d.setDate(d.getDate() + n); return `${d.getFullYear()}-${dosD(d.getMonth() + 1)}-${dosD(d.getDate())}`; };
  const DIAS = ['domingo', 'lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado'];
  const MESES = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];
  const fechaLarga = f => { const d = new Date(f + 'T12:00:00'); return `${DIAS[d.getDay()]} ${d.getDate()}-${MESES[d.getMonth()]}`; };
  const repintar = () => { try { if (self.GGRepintarComidas) self.GGRepintarComidas(); } catch (_) {} };
  const db = () => self.GGDB.abrir();

  /* ---------- validación del programa (FORMATO §5 de CONTEXTO-MODULO-ALIMENTACION) ---------- */
  function validarPrograma(o) {
    const errores = [], avisos = [];
    const err = m => errores.push(m);
    if (!o || typeof o !== 'object' || Array.isArray(o)) return { ok: false, errores: ['El archivo no es un objeto JSON.'], avisos };
    if (o.formato !== 'programa-alimentacion') err('formato: debe ser "programa-alimentacion".');
    if (o.version_formato !== '1.0') err('version_formato: debe ser "1.0".');
    const p = o.programa || {};
    if (!p.id) err('programa.id: falta.');
    if (!p.nombre) err('programa.nombre: falta.');
    if (!fechaOk(p.desde)) err('programa.desde: fecha inválida (AAAA-MM-DD).');
    if (!fechaOk(p.hasta)) err('programa.hasta: fecha inválida (AAAA-MM-DD).');
    if (fechaOk(p.desde) && fechaOk(p.hasta) && p.desde > p.hasta) err('programa: "desde" es posterior a "hasta".');
    const perfiles = Array.isArray(o.perfiles) ? o.perfiles : [];
    if (!perfiles.length) err('perfiles: debe haber al menos uno.');
    const idsPerfil = new Set(perfiles.map(x => x && x.id));
    perfiles.forEach((x, i) => { if (!x || !x.id) err(`perfiles[${i}].id: falta.`); });
    const alimentos = o.alimentos && typeof o.alimentos === 'object' ? o.alimentos : {};
    const preps = o.preparaciones && typeof o.preparaciones === 'object' ? o.preparaciones : {};
    if (o.horario_por_defecto) for (const [k, h] of Object.entries(o.horario_por_defecto)) if (!horaOk(h)) err(`horario_por_defecto.${k}: hora inválida "${h}" (HH:MM).`);
    for (const [id, pr] of Object.entries(preps)) {
      for (const [pid, lista] of Object.entries((pr && pr.porciones) || {})) {
        if (!idsPerfil.has(pid)) err(`preparaciones.${id}.porciones: el perfil "${pid}" no existe en perfiles.`);
        (Array.isArray(lista) ? lista : []).forEach((x, i) => { if (!x || !(x.alimento in alimentos)) err(`preparaciones.${id}.porciones.${pid}[${i}]: el alimento "${x && x.alimento}" no existe en alimentos.`); });
      }
      for (const pid of Object.keys((pr && pr.aporte_estimado) || {})) if (!idsPerfil.has(pid)) err(`preparaciones.${id}.aporte_estimado: el perfil "${pid}" no existe en perfiles.`);
    }
    const dias = Array.isArray(o.dias) ? o.dias : [];
    if (!dias.length) err('dias: no hay ningún día.');
    const vistas = new Set();
    dias.forEach((d, i) => {
      const r = `dias[${i}]`;
      if (!d || !fechaOk(d.fecha)) return err(`${r}.fecha: fecha inválida.`);
      if (vistas.has(d.fecha)) err(`${r}.fecha: ${d.fecha} está repetida.`);
      vistas.add(d.fecha);
      if (fechaOk(p.desde) && fechaOk(p.hasta) && (d.fecha < p.desde || d.fecha > p.hasta)) err(`${r}.fecha: ${d.fecha} está fuera del rango del programa.`);
      (Array.isArray(d.comidas) ? d.comidas : []).forEach((c, k) => {
        if (!c || !TIPOS.includes(c.tipo)) err(`${r}.comidas[${k}].tipo: debe ser ${TIPOS.join(', ')}.`);
        if (!c || !horaOk(c.hora)) err(`${r}.comidas[${k}].hora: hora inválida (HH:MM).`);
        if (!c || !(c.preparacion in preps)) err(`${r}.comidas[${k}].preparacion: "${c && c.preparacion}" no existe en preparaciones.`);
      });
      (Array.isArray(d.tareas) ? d.tareas : []).forEach((t, k) => { if (!t || !horaOk(t.hora)) err(`${r}.tareas[${k}].hora: hora inválida (HH:MM).`); });
      for (const pid of Object.keys(d.total_estimado_kcal || {})) if (!idsPerfil.has(pid)) err(`${r}.total_estimado_kcal: el perfil "${pid}" no existe en perfiles.`);
    });
    if (!errores.length) {
      const pid = perfiles[0].id;
      if (perfiles.length > 1) avisos.push(`El programa trae ${perfiles.length} perfiles; este celular usará "${perfiles[0].nombre || pid}". Pide a Alimentación un archivo por persona.`);
      for (const [id, pr] of Object.entries(preps)) if (!(pr.porciones || {})[pid]) avisos.push(`La preparación "${pr.nombre || id}" no tiene porción para ${perfiles[0].nombre || pid}.`);
      dias.forEach(d => {
        const tot = (d.total_estimado_kcal || {})[pid];
        const suma = (d.comidas || []).reduce((a, c) => a + (((preps[c.preparacion] || {}).aporte_estimado || {})[pid] || {}).kcal || 0, 0);
        if (typeof tot === 'number' && suma && Math.abs(tot - suma) > 0.1 * suma) avisos.push(`${d.fecha}: el total estimado (${tot} kcal) difiere más de 10 % de la suma de las comidas (${suma} kcal).`);
      });
    }
    return { ok: !errores.length, errores, avisos };
  }

  /* ---------- datos ---------- */
  async function cargar() {
    if (M.cargando) return; M.cargando = true;
    try {
      const d = await db();
      const p = await d.doc('alimentacion/programa_activo').get();
      M.programa = p.exists ? p.data().programa : null;
      M.perfil = M.programa ? M.programa.perfiles[0] : null;
      const ic = await d.doc('alimentacion/intercambios').get();
      M.cambios = ic.exists && M.programa && ic.data().programa_id === M.programa.programa.id ? (ic.data().cambios || {}) : {};
      const regs = await d.collection('comidas').get();
      M.registros = {}; regs.docs.forEach(x => { M.registros[x.id] = x.data(); });
      const fotos = await d.collection('fotos').get();
      M.fotos = {}; fotos.docs.forEach(x => { M.fotos[x.id] = x.data().dataUrl; });
      M.error = null;
    } catch (er) { M.error = 'No se pudo leer la base de este celular: ' + (er && er.message || er); }
    M.cargado = true; M.cargando = false;
    if (!M.dia) M.dia = elegirDia();
    repintar();
  }
  function elegirDia() {
    const h = hoyISO(), pr = M.programa && M.programa.programa;
    if (!pr) return h;
    if (h < pr.desde) return pr.desde;
    if (h > pr.hasta) return pr.hasta;
    return h;
  }
  const clave = (f, t) => `${f}_${t}`;
  async function guardarRegistro(f, t, cambios) {
    const k = clave(f, t), previo = M.registros[k] || { fecha: f, tipo: t, version: 0 };
    const r = Object.assign({}, previo, cambios, { version: (previo.version || 0) + 1, actualizado: new Date().toISOString() });
    M.registros[k] = r; repintar();
    try { await (await db()).doc('comidas/' + k).set(r); } catch (er) { M.mensaje = { tipo: 'err', texto: 'No se pudo guardar: ' + (er && er.message || er) }; repintar(); }
  }

  /* ---------- foto: se reduce a 1280 px y JPEG para no llenar el celular ni el Drive ---------- */
  function reducir(file) {
    return new Promise((ok, mal) => {
      const url = URL.createObjectURL(file), img = new Image();
      img.onload = () => {
        const max = 1280, k = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight));
        const c = document.createElement('canvas'); c.width = Math.round(img.naturalWidth * k); c.height = Math.round(img.naturalHeight * k);
        c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
        URL.revokeObjectURL(url); ok(c.toDataURL('image/jpeg', 0.75));
      };
      img.onerror = () => { URL.revokeObjectURL(url); mal(new Error('no se pudo leer la imagen')); };
      img.src = url;
    });
  }
  async function guardarFoto(f, t, file) {
    try {
      const dataUrl = await reducir(file), k = clave(f, t);
      await (await db()).doc('fotos/' + k).set({ dataUrl, fecha: f, tipo: t });
      M.fotos[k] = dataUrl;
      await guardarRegistro(f, t, { foto: true });
    } catch (er) { M.mensaje = { tipo: 'err', texto: 'No se pudo guardar la foto: ' + (er && er.message || er) }; repintar(); }
  }

  /* ---------- envío a Alimentación ---------- */
  const pendientes = () => Object.values(M.registros).filter(r => r.estado && (!r.enviado || r.enviado.version !== r.version)).sort((a, b) => (a.fecha + a.tipo).localeCompare(b.fecha + b.tipo));
  function diaDe(f) { return ((M.programa && M.programa.dias) || []).find(d => d.fecha === f); }
  function comidaPlan(f, t) { const d = diaDe(f); return d && (d.comidas || []).find(c => c.tipo === t); }
  /* La comida que toca en ese lugar, con los intercambios aplicados: la hora es la del lugar; el plato (y dónde está guardado) viene del otro. */
  function comidaDe(f, t) {
    const c = comidaPlan(f, t), x = M.cambios[clave(f, t)];
    if (!c || !x) return c;
    return Object.assign({}, c, { preparacion: x.preparacion, conservacion: x.conservacion, intercambio: x });
  }
  const etiquetaLugar = k => { const [f, t] = k.split('_'); return `${ETQ[t] || t} del ${fechaLarga(f)}`; };
  const kcalDe = c => (((M.programa.preparaciones[c.preparacion] || {}).aporte_estimado || {})[M.perfil.id] || {}).kcal || 0;
  async function guardarCambios() {
    await (await db()).doc('alimentacion/intercambios').set({ programa_id: M.programa.programa.id, cambios: M.cambios, actualizado: new Date().toISOString() });
  }
  async function intercambiar(kA, kB) {
    const [fA, tA] = kA.split('_'), [fB, tB] = kB.split('_');
    const a = comidaDe(fA, tA), b = comidaDe(fB, tB);
    if (!a || !b || kA === kB) return;
    const nuevo = (k, c, desde) => { const [f, t] = k.split('_'), plan = comidaPlan(f, t); return plan.preparacion === c.preparacion && (plan.conservacion || null) === (c.conservacion || null) ? null : { preparacion: c.preparacion, conservacion: c.conservacion || null, desde }; };
    const xA = nuevo(kA, b, kB), xB = nuevo(kB, a, kA);
    if (xA) M.cambios[kA] = xA; else delete M.cambios[kA];
    if (xB) M.cambios[kB] = xB; else delete M.cambios[kB];
    M.eligiendo = null;
    M.mensaje = { tipo: 'ok', texto: `Intercambiadas: ${etiquetaLugar(kA)} ↔ ${etiquetaLugar(kB)}.` };
    try { await guardarCambios(); } catch (er) { M.mensaje = { tipo: 'err', texto: 'No se pudo guardar el intercambio: ' + (er && er.message || er) }; }
    // Si alguna ya estaba marcada, su registro cambia de plato: queda por enviar de nuevo.
    for (const [f, t] of [[fA, tA], [fB, tB]]) { const r = M.registros[clave(f, t)]; if (r && r.estado) await guardarRegistro(f, t, {}); }
    repintar();
  }
  function nombreBase(r) { const base = `comida-${M.perfil.id}-${r.fecha}-${r.tipo}`; return r.enviado ? `${base}-v${r.version}` : base; }
  function registroJSON(r, nombreFoto) {
    const c = comidaDe(r.fecha, r.tipo), pr = c && M.programa.preparaciones[c.preparacion], pid = M.perfil.id;
    return {
      formato: 'registro-comida', version_formato: '1.0',
      perfil: { id: pid, nombre: M.perfil.nombre || pid },
      programa: { id: M.programa.programa.id, nombre: M.programa.programa.nombre },
      fecha: r.fecha, tipo: r.tipo, hora_programada: c ? c.hora : null,
      indicado: c && pr ? { preparacion: c.preparacion, nombre: pr.nombre, porciones: (pr.porciones || {})[pid] || [], aporte_estimado: (pr.aporte_estimado || {})[pid] || null } : null,
      intercambio: c && c.intercambio ? { con: { fecha: c.intercambio.desde.split('_')[0], tipo: c.intercambio.desde.split('_')[1] }, preparacion_planificada: (comidaPlan(r.fecha, r.tipo) || {}).preparacion || null } : null,
      estado: r.estado, nota: r.nota || '', foto: nombreFoto || null,
      registrado_en: r.actualizado, version: r.version, app: 'genesis-gym-2 ' + (self.GG_VERSION || '')
    };
  }
  async function enviar() {
    const lista = pendientes();
    if (!lista.length) { M.mensaje = { tipo: 'info', texto: 'No hay nada nuevo que enviar.' }; return repintar(); }
    const sinFoto = lista.filter(r => r.estado === 'otro' && !M.fotos[clave(r.fecha, r.tipo)]);
    if (sinFoto.length) { M.mensaje = { tipo: 'err', texto: `Falta la foto de: ${sinFoto.map(r => `${ETQ[r.tipo]} del ${fechaLarga(r.fecha)}`).join(', ')}. Agrégala antes de enviar.` }; return repintar(); }
    const archivos = [];
    for (const r of lista) {
      const base = nombreBase(r), k = clave(r.fecha, r.tipo);
      let nombreFoto = null;
      if (r.estado === 'otro') { nombreFoto = base + '.jpg'; archivos.push({ nombre: nombreFoto, mime: 'image/jpeg', blob: await (await fetch(M.fotos[k])).blob(), clave: k + '|foto' }); }
      archivos.push({ nombre: base + '.json', mime: 'application/json', texto: JSON.stringify(registroJSON(r, nombreFoto), null, 2), clave: k + '|json', version: r.version });
    }
    const subidos = {};
    try {
      const n = await self.GGDrive.subir(archivos, {
        alListo: async (cl, id) => {
          const [k, que] = cl.split('|'); (subidos[k] = subidos[k] || {})[que] = id;
          if (que === 'json') { const r = M.registros[k]; r.enviado = { version: r.version, json: id, foto: (subidos[k] || {}).foto || null, en: new Date().toISOString() }; await (await db()).doc('comidas/' + k).set(r); }
        }
      });
      M.mensaje = { tipo: 'ok', texto: `Enviado a Alimentación: ${lista.length} comida${lista.length === 1 ? '' : 's'} (${n} archivo${n === 1 ? '' : 's'}) en la carpeta "Genesis Gym - registro comidas" de tu Drive.` };
    } catch (er) {
      if (er && er.message === 'cancelado') return repintar();
      M.mensaje = { tipo: 'err', texto: 'No se pudo enviar a Drive: ' + (er && er.message || er) + '. Lo marcado queda guardado en el celular; vuelve a intentarlo con internet.' };
    }
    repintar();
  }

  /* ---------- importar programa ---------- */
  function validarTexto(texto) {
    let obj;
    try { obj = JSON.parse(texto); } catch (er) { M.importacion = { resultado: { ok: false, errores: ['El texto no es JSON válido: ' + er.message], avisos: [] } }; return repintar(); }
    const resultado = validarPrograma(obj);
    const actual = M.programa;
    if (resultado.ok && actual && JSON.stringify(actual) === JSON.stringify(obj)) { resultado.ok = false; resultado.yaActivo = true; }
    M.importacion = { obj, resultado }; repintar();
  }
  async function activar() {
    const im = M.importacion; if (!im || !im.resultado || !im.resultado.ok) return;
    try {
      const d = await db();
      await d.doc('alimentacion/programa_activo').set({ programa: im.obj, importado_en: new Date().toISOString() });
      await d.doc(`programas_alimentacion/${im.obj.programa.id}`).set({ programa: im.obj, importado_en: new Date().toISOString() });
      if (!M.programa || M.programa.programa.id !== im.obj.programa.id) M.cambios = {};
      M.programa = im.obj; M.perfil = im.obj.perfiles[0]; M.dia = elegirDia(); M.importacion = null; M.verImport = false;
      M.mensaje = { tipo: 'ok', texto: `Programa activado: ${im.obj.programa.nombre}.` };
    } catch (er) { M.mensaje = { tipo: 'err', texto: 'No se pudo guardar el programa: ' + (er && er.message || er) }; }
    repintar();
  }

  /* ---------- vistas ---------- */
  function vImportar() {
    const im = M.importacion;
    let h = `<section class="card stack"><h2>Importar programa de alimentación</h2>
      <p class="muted">Lo entrega el chat Alimentación (archivo <code>programa-alimentacion-…json</code>). Si tiene un solo error, se rechaza completo.</p>
      <div class="campo"><label for="com-archivo">Archivo .json</label><input id="com-archivo" type="file" style="font-size:1rem;padding:10px"></div>
      ${self.GGDrive ? GGDrive.boton({ attr: 'data-drive-programa' }) : ''}`;
    if (im && im.resultado) {
      const r = im.resultado;
      if (r.yaActivo) h += `<div class="note info"><strong>Este programa ya está activo.</strong> No hay nada que importar.</div>`;
      else if (!r.ok) h += `<div class="note err"><strong>Programa rechazado.</strong> ${r.errores.length} error${r.errores.length === 1 ? '' : 'es'}. Devuelve esta lista a Alimentación.</div><ul class="errores">${r.errores.map(x => `<li>${e(x)}</li>`).join('')}</ul>`;
      else {
        const p = im.obj.programa;
        h += `<div class="note ok"><strong>Programa válido.</strong></div>
          <p><strong>${e(p.nombre)}</strong><br>${e(fechaLarga(p.desde))} a ${e(fechaLarga(p.hasta))} · ${im.obj.dias.length} días · perfil ${e(im.obj.perfiles[0].nombre || im.obj.perfiles[0].id)}</p>
          ${r.avisos.length ? `<div class="note warn"><strong>Avisos (no bloquean):</strong><ul class="errores">${r.avisos.map(a => `<li>${e(a)}</li>`).join('')}</ul></div>` : ''}
          <button class="btn primary" data-com-activar>Activar este programa</button>`;
      }
    }
    return h + `${M.programa ? '<button class="btn" data-com-cerrar-import>Cerrar</button>' : ''}</section>`;
  }

  function vComida(f, c0) {
    const c = comidaDe(f, c0.tipo) || c0;
    const pid = M.perfil.id, pr = M.programa.preparaciones[c.preparacion] || {}, k = clave(f, c.tipo), r = M.registros[k] || {};
    const porc = (pr.porciones || {})[pid] || [], ap = (pr.aporte_estimado || {})[pid];
    const foto = M.fotos[k];
    const enviado = r.enviado && r.enviado.version === r.version;
    const botones = Object.entries(ESTADOS).map(([v, t]) => `<button class="btn${r.estado === v ? ' primary' : ''}" data-com-estado="${e(c.tipo)}|${v}" aria-pressed="${r.estado === v}">${t}</button>`).join('');
    return `<section class="card stack" id="com-${e(c.tipo)}">
      <div class="row" style="justify-content:space-between;align-items:baseline"><p class="eyebrow">${e(c.hora)} · ${e(ETQ[c.tipo] || c.tipo)}</p>${r.estado ? `<span class="chip${enviado ? '' : ' run'}">${enviado ? 'Enviado' : 'Por enviar'}</span>` : ''}</div>
      <h2 style="margin:0">${e(pr.nombre || c.preparacion)}</h2>
      ${porc.length ? `<ul class="pasos" style="margin:0">${porc.map(x => `<li>${e(x.alimento)}: ${e(x.gramos)} g${x.medida ? ` (${e(x.medida)})` : ''}</li>`).join('')}</ul>` : '<p class="muted">Sin porción para este perfil.</p>'}
      <p class="muted" style="margin:0">${ap ? `≈ ${e(ap.kcal)} kcal · ${e(ap.proteina_g)} g proteína` : ''}${c.conservacion ? ` · desde el ${e(c.conservacion)}` : ''}${pr.notas ? ` · ${e(pr.notas)}` : ''}</p>
      ${c.intercambio ? `<div class="note info">Intercambiada con: <strong>${e(etiquetaLugar(c.intercambio.desde))}</strong>. En el plan original aquí iba ${e((M.programa.preparaciones[(comidaPlan(f, c.tipo) || {}).preparacion] || {}).nombre || '')}.${(M.cambios[c.intercambio.desde] || {}).desde === k ? `<br><button class="btn" data-com-deshacer="${e(k)}" style="margin-top:6px">Deshacer intercambio</button>` : ''}</div>` : ''}
      <div class="stack" style="gap:6px">${botones}</div>
      ${M.eligiendo === k ? vElegir(k) : `<button class="btn" data-com-intercambiar="${e(k)}">Intercambiar con otra comida…</button>`}
      ${r.estado === 'otro' ? `<div class="stack" style="gap:6px">
        <label class="btn" for="com-foto-${e(c.tipo)}" style="text-align:center">${foto ? 'Cambiar foto' : 'Tomar o elegir foto de lo que comiste'}</label>
        <input id="com-foto-${e(c.tipo)}" data-com-foto="${e(c.tipo)}" type="file" accept="image/*" capture="environment" style="position:absolute;width:1px;height:1px;opacity:0">
        ${foto ? `<img src="${foto}" alt="Foto de ${e(ETQ[c.tipo])}" style="border-radius:10px;max-height:260px;object-fit:cover">` : '<div class="note warn">Falta la foto: Alimentación la necesita para estimar lo que comiste.</div>'}
        <label for="com-nota-${e(c.tipo)}" class="muted">Nota (opcional): qué y cuánto comiste</label>
        <textarea id="com-nota-${e(c.tipo)}" data-com-nota="${e(c.tipo)}" rows="2" style="width:100%;border:1px solid var(--line);border-radius:10px;padding:8px;font:inherit;background:var(--bg)">${e(M.notas[k] != null ? M.notas[k] : (r.nota || ''))}</textarea>
      </div>` : ''}
    </section>`;
  }

  function vElegir(k) {
    // Primero este día, después los siguientes y al final los anteriores (lo más probable es intercambiar hacia adelante).
    const orden = d => d.fecha === M.dia ? '0' : (d.fecha > M.dia ? '1' : '2') + d.fecha;
    const dias = (M.programa.dias || []).slice().sort((x, y) => orden(x).localeCompare(orden(y)));
    let h = `<div class="stack" style="gap:6px;border:1px solid var(--line);border-radius:10px;padding:10px"><p style="margin:0"><strong>¿Con qué comida la intercambias?</strong></p>
      <p class="muted" style="margin:0">Ambas quedan cambiadas: aquí comerás lo de la otra, y allá lo de esta. La hora de cada comida no cambia.</p>`;
    for (const d of dias) {
      const op = TIPOS.map(t => comidaDe(d.fecha, t)).filter(Boolean).filter(c => clave(d.fecha, c.tipo) !== k);
      if (!op.length) continue;
      h += `<p class="eyebrow" style="margin:8px 0 0">${e(fechaLarga(d.fecha))}${d.fecha === M.dia ? ' · este día' : d.fecha < M.dia ? ' · día anterior' : ''}</p>` + op.map(c => `<button class="btn" data-com-con="${e(k)}|${e(clave(d.fecha, c.tipo))}" style="text-align:left;justify-content:flex-start">${e(ETQ[c.tipo])} · ${e((M.programa.preparaciones[c.preparacion] || {}).nombre || c.preparacion)}</button>`).join('');
    }
    return h + `<button class="btn" data-com-cancelar-intercambio>Cancelar</button></div>`;
  }

  function vista() {
    if (!M.cargado) { cargar(); return `<header class="stack"><p class="eyebrow">Alimentación</p><h1>Comidas</h1></header><p class="muted">Cargando…</p>`; }
    let h = `<header class="stack"><p class="eyebrow">Alimentación</p><h1>Comidas</h1></header>`;
    if (M.error) h += `<div class="note err">${e(M.error)}</div>`;
    if (M.mensaje) h += `<div class="note ${M.mensaje.tipo === 'err' ? 'err' : M.mensaje.tipo === 'ok' ? 'ok' : 'info'}">${e(M.mensaje.texto)}</div>`;
    if (!M.programa) return h + `<div class="card"><h2>Aún no hay programa</h2><p class="muted">El menú lo entrega el chat Alimentación en un archivo JSON. Impórtalo para ver aquí tus 5 comidas de cada día.</p></div>` + vImportar();
    if (M.verImport) return h + vImportar();
    const p = M.programa.programa, pid = M.perfil.id, f = M.dia, d = diaDe(f);
    h += `<div class="row" style="justify-content:space-between;align-items:center">
        <button class="btn" data-com-dia="-1" ${f <= p.desde ? 'disabled' : ''} aria-label="Día anterior">‹</button>
        <div style="text-align:center"><strong>${e(fechaLarga(f))}</strong>${f === hoyISO() ? ' · hoy' : ''}<br><span class="muted">${e(d ? d.etiqueta || '' : 'Sin menú para este día')}</span></div>
        <button class="btn" data-com-dia="1" ${f >= p.hasta ? 'disabled' : ''} aria-label="Día siguiente">›</button></div>`;
    if ((M.programa.restricciones || []).length) h += `<div class="note warn"><strong>Restricciones:</strong> ${M.programa.restricciones.map(e).join(' · ')}</div>`;
    if (d) {
      const comidas = TIPOS.map(t => comidaDe(f, t)).filter(Boolean);
      const regs = comidas.map(c => M.registros[clave(f, c.tipo)] || {});
      const kcalInd = comidas.reduce((a, c, i) => a + (regs[i].estado === 'indicado' ? kcalDe(c) : 0), 0);
      const conCambios = comidas.some(c => c.intercambio);
      const marcadas = regs.filter(r => r.estado).length, otras = regs.filter(r => r.estado === 'otro').length;
      const tot = conCambios ? comidas.reduce((a, c) => a + kcalDe(c), 0) : (d.total_estimado_kcal || {})[pid];
      h += `<section class="card"><p style="margin:0"><strong>${marcadas} de ${comidas.length}</strong> comidas marcadas · plan del día ≈ ${e(tot != null ? tot : '—')} kcal${conCambios ? ' con intercambios' : ''} (meta ${e(M.perfil.meta_kcal || '—')})</p>
        <p class="muted" style="margin:4px 0 0">Lo indicado que comiste suma ≈ ${kcalInd} kcal${otras ? ` · ${otras} comida${otras === 1 ? '' : 's'} por revisar en Alimentación` : ''}. Son estimaciones del programa.</p></section>`;
      h += comidas.map(c => vComida(f, c)).join('');
      if ((d.tareas || []).length) h += `<section class="card stack"><h2>Tareas del día</h2><ul class="pasos" style="margin:0">${d.tareas.map(t => `<li><strong>${e(t.hora)}</strong> · ${e(t.texto)}</li>`).join('')}</ul></section>`;
    } else h += `<div class="card"><p class="muted">El programa no trae menú para este día.</p></div>`;
    const n = pendientes().length;
    h += `<section class="card stack"><h2>Enviar a Alimentación</h2>
      <p class="muted" style="margin:0">${n ? `${n} comida${n === 1 ? '' : 's'} por enviar.` : 'Todo lo marcado ya fue enviado.'} Se sube a tu Drive, carpeta "Genesis Gym - registro comidas". Necesita internet.</p>
      <button class="btn primary" data-com-enviar ${n && self.GGDrive && GGDrive.configurado() ? '' : 'disabled'}>Enviar a Alimentación${n ? ` (${n})` : ''}</button></section>`;
    h += `<p class="muted">Programa: ${e(p.nombre)} · ${e(fechaLarga(p.desde))} a ${e(fechaLarga(p.hasta))}</p><button class="btn" data-com-ver-import>Importar otro programa</button>`;
    if (self.GGDrive && GGDrive.configurado() && n) setTimeout(() => GGDrive.precargar(), 0);
    return h;
  }

  /* ---------- eventos ---------- */
  function click(t) {
    if (t.hasAttribute('data-com-estado')) {
      const [tipo, est] = t.getAttribute('data-com-estado').split('|');
      M.mensaje = null;
      const r = M.registros[clave(M.dia, tipo)];
      if (r && r.estado === est) return true;
      guardarRegistro(M.dia, tipo, { estado: est });
      return true;
    }
    if (t.hasAttribute('data-com-intercambiar')) { M.eligiendo = t.getAttribute('data-com-intercambiar'); M.mensaje = null; repintar(); return true; }
    if (t.hasAttribute('data-com-cancelar-intercambio')) { M.eligiendo = null; repintar(); return true; }
    if (t.hasAttribute('data-com-con')) { const [a, b] = t.getAttribute('data-com-con').split('|'); intercambiar(a, b); return true; }
    if (t.hasAttribute('data-com-deshacer')) { const k = t.getAttribute('data-com-deshacer'), x = M.cambios[k]; if (x) intercambiar(k, x.desde); return true; }
    if (t.hasAttribute('data-com-dia')) { M.dia = sumarDias(M.dia, Number(t.getAttribute('data-com-dia'))); M.mensaje = null; M.eligiendo = null; repintar(); window.scrollTo(0, 0); return true; }
    if (t.hasAttribute('data-com-enviar')) { enviar(); return true; }
    if (t.hasAttribute('data-com-activar')) { activar(); return true; }
    if (t.hasAttribute('data-com-ver-import')) { M.verImport = true; M.importacion = null; M.mensaje = null; repintar(); return true; }
    if (t.hasAttribute('data-com-cerrar-import')) { M.verImport = false; M.importacion = null; repintar(); return true; }
    if (t.hasAttribute('data-drive-programa') && self.GGDrive) {
      GGDrive.importar(texto => validarTexto(texto), msg => { M.importacion = { resultado: { ok: false, errores: [msg], avisos: [] } }; repintar(); },
        { attr: 'data-drive-programa', carpeta: (self.GG_GOOGLE || {}).carpetaProgramas || null, titulo: 'Elige el programa de alimentación (.json)' });
      return true;
    }
    return false;
  }
  document.addEventListener('change', ev => {
    const el = ev.target;
    if (el.id === 'com-archivo' && el.files && el.files[0]) {
      const fr = new FileReader();
      fr.onload = () => { const t = String(fr.result || ''); if (!t.trim()) { M.importacion = { resultado: { ok: false, errores: ['El archivo llegó vacío al celular.'], avisos: [] } }; return repintar(); } validarTexto(t); };
      fr.readAsText(el.files[0]);
    }
    if (el.hasAttribute && el.hasAttribute('data-com-foto') && el.files && el.files[0]) guardarFoto(M.dia, el.getAttribute('data-com-foto'), el.files[0]);
    if (el.hasAttribute && el.hasAttribute('data-com-nota')) {
      const tipo = el.getAttribute('data-com-nota'), k = clave(M.dia, tipo);
      delete M.notas[k];
      if ((M.registros[k] || {}).nota !== el.value) guardarRegistro(M.dia, tipo, { nota: el.value });
    }
  });
  document.addEventListener('input', ev => { const el = ev.target; if (el.hasAttribute && el.hasAttribute('data-com-nota')) M.notas[clave(M.dia, el.getAttribute('data-com-nota'))] = el.value; });

  self.GGComidas = { vista, click, validarPrograma, _estado: M };
})();
