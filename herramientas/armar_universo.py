"""Arma imagenes/ y imagenes/catalogo.json de la 2.0 desde el universo de Entrenamiento.
Uso: python -I armar_universo.py <universo.json> <repo everkinetic en el commit fijado> <imagenes propias (dir)> <salida genesis-gym/imagenes> <repo free-exercise-db en el commit fijado>
Copia cada imagen SIN modificar y verifica que el origen exista. No inventa nada: si falta algo, se detiene.
Las fotos de free-exercise-db van todas en imagenes/free-exercise-db/ (decisión de Héctor, Coordinador #32): procedencia
no verificada, así se pueden retirar de una vez si hiciera falta."""
import json, os, shutil, sys, hashlib
uni_p, ek_dir, propias_dir, salida, fedb_dir = sys.argv[1:6]
u = json.load(open(uni_p, encoding='utf-8'))
COMMIT = '446bb9a3d0c3beb6b84f7c9d77dfc8af707a2ab6'
COMMIT_FEDB = 'f00c92c7dcf1216a928a52c3706c7ce8e2f71ed5'
CARPETA_FEDB = 'free-exercise-db'
# Versión de la caché de imágenes del celular (sw.js). Agregar imágenes NO la cambia (el celular baja solo las nuevas).
# Solo se sube si cambia el contenido de un archivo que ya existía: el script lo detecta y se detiene.
VERSION_IMAGENES = 3
VERSION_CATALOGO = 4
# Decisión del Coordinador (estado-genesis v15, buzón coordinador #14): las 7 imágenes generadas con IA llevan licencia 'generada-ia'.
GENERADA_IA = {'dead-bug', 'face-pull-polea', 'pallof-press', 'peso-muerto-rumano-mancuernas', 'press-inclinado-maquina', 'press-maquina-agarre-neutro', 'remo-mancuerna-una-mano'}
ETQ = {'inicio': 'Posición inicial', 'final': 'Posición final', 'medio': 'Posición intermedia', 'inicio-y-final': 'Inicio y final'}
os.makedirs(salida, exist_ok=True)
cat, faltan, cambiadas, n = {}, [], [], 0
for e in u['ejercicios']:
    srcs, etiquetas, origenes = [], [], []
    for im in e.get('imagenes', []):
        org, app = im['origen'], im['archivo_app']
        if org.startswith('everkinetic/data@'):
            rep, ruta = org.split(':', 1)
            if rep.split('@')[1] != COMMIT: sys.exit(f'commit distinto en {org}')
            src = os.path.join(ek_dir, ruta)
            sub = ''
        elif org.startswith('proyecto:'):
            src = os.path.join(propias_dir, os.path.basename(org.split(':', 1)[1])); sub = ''
        elif org.startswith('yuhonas/free-exercise-db@'):
            rep, ruta = org.split(':', 1)
            if rep.split('@')[1] != COMMIT_FEDB: sys.exit(f'commit distinto en {org}')
            src = os.path.join(fedb_dir, ruta); sub = CARPETA_FEDB
        else:
            sys.exit(f'origen desconocido {org}')
        if not os.path.isfile(src): faltan.append(org); continue
        os.makedirs(os.path.join(salida, sub), exist_ok=True)
        dst = os.path.join(salida, sub, os.path.basename(app))
        if os.path.isfile(dst) and open(dst, 'rb').read() != open(src, 'rb').read(): cambiadas.append(dst)
        shutil.copyfile(src, dst); n += 1
        app = 'imagenes/' + (sub + '/' if sub else '') + os.path.basename(app)
        srcs.append(app); etiquetas.append(ETQ.get(im.get('posicion'), str(im.get('posicion') or '').capitalize())); origenes.append(org)
    if not srcs: continue
    val = e.get('validacion'); val = val.get('estado') if isinstance(val, dict) else val
    cat[e['ejercicio_id']] = {
        'nombre': e.get('nombre'), 'srcs': srcs, 'etiquetas': etiquetas,
        'credito': e.get('credito'), 'licencia': 'generada-ia' if e['ejercicio_id'] in GENERADA_IA else e.get('licencia'), 'fuente': e.get('fuente'),
        'url_fuente': ' · '.join(origenes), 'validada_entrenamiento': val == 'validada', 'nota': ''}
if faltan: sys.exit('FALTAN ' + str(len(faltan)) + ': ' + ', '.join(faltan[:10]))
if cambiadas: sys.exit('Cambió el contenido de imágenes que ya existían (sube VERSION_IMAGENES y vuelve a correr): ' + ', '.join(cambiadas[:10]))
out = {'version': VERSION_CATALOGO, 'version_imagenes': VERSION_IMAGENES,
       'descripcion': f"Imágenes copiadas sin modificar según el universo de ejercicios de Entrenamiento ({u.get('version')}, {u.get('fecha')}). Everkinetic en el commit {COMMIT}; free-exercise-db en el commit {COMMIT_FEDB} (carpeta {CARPETA_FEDB}/).",
       'imagenes': cat}
json.dump(out, open(os.path.join(salida, 'catalogo.json'), 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print(f'{len(cat)} ejercicios, {n} imágenes copiadas, validadas: {sum(v["validada_entrenamiento"] for v in cat.values())}')
