#!/usr/bin/env python3
"""Construye Génesis Gym 2.0 a partir de la fuente de la 1.0, sin modificarla.

Uso:
    python construir.py <carpeta genesis-app de la 1.0> <carpeta genesis-gym de la 2.0>

Lee (solo lectura) app.src.html y validador-rutina.js de la 1.0 y escribe en la 2.0:
    index.html            = cabecera PWA + app.src.html con los cambios de CAMBIOS
    validador-rutina.js   = copia exacta del validador de la 1.0
    fuente-1.0.txt        = huellas md5 de los archivos de la 1.0 usados (trazabilidad)

Cada cambio exige encontrar el texto original exactamente una vez. Si la 1.0 cambió ese texto,
el script se detiene con error en vez de construir algo a medias.
"""
import hashlib
import pathlib
import sys

CABECERA = """<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#1f5fbf">
<link rel="manifest" href="manifest.webmanifest">
<link rel="icon" type="image/png" sizes="192x192" href="iconos/icono-192.png">
<link rel="apple-touch-icon" href="iconos/apple-touch-icon.png">
<style>/* Base que la 1.0 recibía del contenedor de Claude; se reproduce igual. */
:root{color-scheme:light;box-sizing:border-box;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}html{scroll-padding-top:env(safe-area-inset-top,0px)}body{margin:0;padding:0;font:14px -apple-system,BlinkMacSystemFont,sans-serif;background:#faf9f5;color:#141413}img{max-width:100%}[hidden]:not([hidden=until-found i]){display:none!important}</style>
</head>
<body>
"""

PIE = "\n</body>\n</html>\n"

# (descripción, texto original en la 1.0, texto en la 2.0)
CAMBIOS = [
    ("Fuentes locales en vez de Google Fonts (funciona sin internet)",
     '<link rel="preconnect" href="https://fonts.googleapis.com">\n'
     '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>\n'
     '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@600;700&family=Barlow:wght@400;500;600&display=swap">',
     '<link rel="stylesheet" href="fuentes/fuentes.css">'),
    ("Validador como archivo aparte (copia exacta) + base local + plataforma",
     '<script>\n/*__VALIDADOR__*/\n</script>',
     '<script src="validador-rutina.js"></script>\n'
     '<script src="version.js"></script>\n'
     '<script src="db.js"></script>\n'
     '<script src="plataforma.js"></script>\n'
     '<script src="respaldo.js"></script>\n'
     '<script src="imagenes.js"></script>\n'
     '<script src="google-config.js"></script>\n'
     '<script src="drive.js"></script>\n'
     '<script src="alimentacion.js"></script>'),
    ("Texto de carga: ya no depende de Claude",
     'Si esto no cambia, abre la app desde Claude con tu sesión iniciada.',
     'Si esto no cambia, cierra la app y vuelve a abrirla.'),
    ("Aviso sin base de datos: ya no depende de Claude",
     '<strong>No se pueden guardar ni leer datos en esta vista.</strong> Abre la app desde la app de Claude con tu sesión iniciada.',
     '<strong>No se pudo abrir la base de datos de este celular.</strong> Cierra la app y vuelve a abrirla. Si sigue igual, avisa en el chat Desarrollador 2.'),
    ("Importar desde archivo: avisa si el archivo llega vacío (pasa al elegirlo directo desde WhatsApp o Drive)",
     "    const fr = new FileReader();\n"
     "    fr.onload = () => { S.importacion = { texto: String(fr.result) }; validarImport(String(fr.result)); };\n"
     "    fr.readAsText(ev.target.files[0]);",
     "    const f = ev.target.files[0];\n"
     "    const fr = new FileReader();\n"
     "    const vacio = () => { S.importacion = { texto: '', resultado: { ok: false, archivo: true, errores: [`El archivo \"${f.name}\" llegó vacío al celular (${f.size} bytes). Suele pasar al elegirlo directo desde WhatsApp o Drive. Guárdalo primero en el celular (Descargas) y elígelo desde Mis archivos, o abre el archivo, copia todo el texto y pégalo en el cuadro.`], avisos: [] } }; render(); };\n"
     "    fr.onload = () => { const t = String(fr.result || ''); if (!t.trim()) return vacio(); S.importacion = { texto: t }; validarImport(t); };\n"
     "    fr.onerror = vacio;\n"
     "    fr.readAsText(f);"),
    ("Si falla la lectura del archivo, no culpar a la rutina ni a Entrenamiento",
     "<strong>Rutina rechazada.</strong> ${r.errores.length} error${r.errores.length === 1 ? '' : 'es'}. Devuelve esta lista a Entrenamiento.",
     "${r.archivo ? '<strong>No se pudo leer el archivo.</strong> El problema es el archivo, no la rutina.' : `<strong>Rutina rechazada.</strong> ${r.errores.length} error${r.errores.length === 1 ? '' : 'es'}. Devuelve esta lista a Entrenamiento.`}"),
    ("Selector de archivo sin filtro de tipo: en Android un .json recibido por WhatsApp puede no figurar como JSON y quedar bloqueado",
     '<input id="archivo" type="file" accept=".json,application/json" style="font-size:1rem;padding:10px">',
     '<input id="archivo" type="file" style="font-size:1rem;padding:10px">'),
    ("Validar con el cuadro vacío: explicar qué hacer en vez de mostrar un error de JSON",
     "  const texto = textoArg != null ? textoArg : (ta ? ta.value : '');",
     "  const texto = textoArg != null ? textoArg : (ta ? ta.value : '');\n"
     "  if (!String(texto).trim()){ S.importacion = { texto: '', resultado: { ok: false, archivo: true, errores: ['No hay nada que validar: el cuadro de texto está vacío. Toca \"Seleccionar archivo\" y elige el archivo (se valida solo al elegirlo), o pega el contenido completo en el cuadro.'], avisos: [] } }; render(); return; }"),
    ("Hoy: hueco para el perfil y el recordatorio de respaldo",
     "  h += `</header>` + avisoDb() + avisoEjemplo();",
     "  h += `</header>` + avisoDb() + avisoEjemplo() + (self.GGRespaldo ? GGRespaldo.avisoHoy() : '');"),
    ("Historial: hueco para la tarjeta de respaldo y restauración",
     "  </section>`;\n  h += `<div class=\"row\"><span class=\"estado ${S.errorLectura",
     "  </section>`;\n  h += (self.GGRespaldo ? GGRespaldo.tarjeta() : '');\n  h += `<div class=\"row\"><span class=\"estado ${S.errorLectura"),
    ("Importar: avisar si la rutina nueva es de otra persona (otro rutina.id), sin cambiar FORMATO-RUTINA",
     "  const resultado = validarRutina(obj);\n",
     "  const resultado = validarRutina(obj);\n"
     "  if (resultado.ok && S.rutina && S.rutina.rutina.id !== RUTINA_EJEMPLO && obj.rutina && obj.rutina.id !== S.rutina.rutina.id) resultado.avisos.unshift(`Esta rutina (${obj.rutina.id}) es distinta de la activa (${S.rutina.rutina.id}). ¿Es de otra persona? Cada celular debe tener solo la rutina de su dueño.`);\n"
     "  if (resultado.ok && self.GGCatalogo && obj.rutina && obj.rutina.id !== RUTINA_EJEMPLO && Array.isArray(obj.ejercicios)) { const fuera = [...new Set(obj.ejercicios.map(x => x && x.id).filter(id => id && !(id in (GGCatalogo.imagenes || {}))))]; if (fuera.length) resultado.avisos.push(`${fuera.length === 1 ? 'Este ejercicio no está' : 'Estos ejercicios no están'} en el universo de ejercicios de Entrenamiento y no tendrá${fuera.length === 1 ? '' : 'n'} imagen: ${fuera.join(', ')}. Confírmalo con Entrenamiento.`); }\n"),
    ("Importar: botón \"Buscar en Drive\" (permiso mínimo drive.file: solo el archivo elegido)",
     '<input id="archivo" type="file" style="font-size:1rem;padding:10px"></div>',
     '<input id="archivo" type="file" style="font-size:1rem;padding:10px"></div>\n'
     "    ${self.GGDrive ? GGDrive.boton() : ''}"),
    ("Importar: el botón de Drive trae el texto y lo valida igual que un archivo",
     "  if (t.hasAttribute('data-validar')) return validarImport();",
     "  if (t.hasAttribute('data-validar')) return validarImport();\n"
     "  if (t.hasAttribute('data-drive') && self.GGDrive) return GGDrive.importar(texto => { S.importacion = { texto }; validarImport(texto); }, msg => { S.importacion = { texto: '', resultado: { ok: false, archivo: true, errores: [msg], avisos: [] } }; render(); });"),
    ("Importar: la rutina idéntica a la activa es un aviso informativo, no un rechazo (Coordinador #24)",
     "if (JSON.stringify(S.rutina) === JSON.stringify(obj)) { resultado.ok = false; resultado.errores.push('Esta rutina (misma versión y contenido) ya está activa.'); }",
     "if (JSON.stringify(S.rutina) === JSON.stringify(obj)) { resultado.ok = false; resultado.yaActiva = true; }"),
    ("Importar: mostrar \"ya está activa\" sin \"Rutina rechazada\" ni \"Devuelve esta lista a Entrenamiento\"",
     "    const r = im.resultado;\n    if (!r.ok){",
     "    const r = im.resultado;\n"
     "    if (r.yaActiva){\n"
     "      h += `<section class=\"card\"><div class=\"note info\"><strong>Esta rutina ya está activa.</strong> No hay nada que importar: es la misma versión y el mismo contenido (${esc(im.obj.rutina.nombre)} · versión ${esc(im.obj.rutina.version)}).</div></section>`;\n"
     "    } else if (!r.ok){"),
    ("Barra inferior: 5 pestañas (se agrega Comidas)",
     "grid-template-columns:repeat(4,1fr);gap:4px;z-index:5",
     "grid-template-columns:repeat(5,1fr);gap:2px;z-index:5"),
    ("Barra inferior: pestaña Comidas (módulo Alimentación)",
     '  <button data-vista="historial">',
     '  <button data-vista="comidas"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 3v8M5 3v5a2 2 0 0 0 4 0V3M7 11v10M17 3c-2 2-3 5-3 8h3v10"/></svg>Comidas</button>\n'
     '  <button data-vista="historial">'),
    ("Vista Comidas: la arma alimentacion.js",
     "  app.innerHTML = v === 'sesion' ? vSesion() : v === 'rutina' ? vRutina() :",
     "  app.innerHTML = v === 'comidas' ? (self.GGComidas ? GGComidas.vista() : '') : v === 'sesion' ? vSesion() : v === 'rutina' ? vRutina() :"),
    ("Botones del módulo Comidas: los maneja alimentacion.js",
     "  const t = ev.target.closest('button'); if (!t) return;",
     "  const t = ev.target.closest('button'); if (!t) return;\n"
     "  if (self.GGComidas && !t.dataset.vista && GGComidas.click(t)) return;"),
    ("Comidas: alimentacion.js pide redibujar cuando cambian sus datos (solo si la vista activa es Comidas)",
     "function ir(v){",
     "self.GGRepintarComidas = () => { if (S.vista === 'comidas') render(); };\n"
     "function ir(v){"),
]


def md5(p: pathlib.Path) -> str:
    return hashlib.md5(p.read_bytes()).hexdigest()


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    v1 = pathlib.Path(sys.argv[1])
    v2 = pathlib.Path(sys.argv[2])
    src_p, val_p = v1 / "app.src.html", v1 / "validador-rutina.js"
    src = src_p.read_text(encoding="utf-8")
    for desc, orig, nuevo in CAMBIOS:
        n = src.count(orig)
        if n != 1:
            print(f"ERROR: '{desc}': el texto original aparece {n} veces en {src_p} (se esperaba 1). No se construyó nada.")
            return 1
        src = src.replace(orig, nuevo)
    if "window.claude.use('db')" not in src:
        print("ERROR: la 1.0 ya no pide la base con window.claude.use('db'); revisar plataforma.js.")
        return 1
    (v2 / "index.html").write_text(CABECERA + src.rstrip("\n") + PIE, encoding="utf-8")
    (v2 / "validador-rutina.js").write_bytes(val_p.read_bytes())
    (v2 / "fuente-1.0.txt").write_text(
        "Construido desde la fuente de Génesis Gym 1.0 (solo lectura):\n"
        f"app.src.html         md5 {md5(src_p)}\n"
        f"validador-rutina.js  md5 {md5(val_p)}\n", encoding="utf-8")
    print(f"OK: index.html construido con {len(CAMBIOS)} cambios; validador copiado sin cambios.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
