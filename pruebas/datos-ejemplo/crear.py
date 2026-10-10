"""Crea los datos de ejemplo de las pruebas. Son INVENTADOS: no son datos de ninguna persona.
Uso: python3 crear.py   (escribe en esta misma carpeta)
- programa-alimentacion-ejemplo.json: programa ficticio con 3 días y 5 comidas al día (perfil "prueba").
- base-1.0/: exportación ficticia de la base 1.0 (rutina, 2 sesiones y 2 imágenes) para la prueba de migración."""
import json, os
AQUI = os.path.dirname(os.path.abspath(__file__))
def escribir(ruta, obj):
    ruta = os.path.join(AQUI, ruta); os.makedirs(os.path.dirname(ruta), exist_ok=True)
    with open(ruta, 'w', encoding='utf-8') as f: json.dump(obj, f, ensure_ascii=False, indent=1, sort_keys=True); f.write('\n')

# ---------- programa de alimentación ficticio ----------
alimentos = {'avena': [389, 17, 66, 7], 'leche descremada': [35, 3.4, 5, 0.1], 'manzana': [52, 0.3, 14, 0.2], 'yogur natural': [60, 4, 7, 1.5],
             'pollo': [165, 31, 0, 3.6], 'arroz': [360, 7, 79, 0.6], 'zapallo': [26, 1, 6.5, 0.1], 'huevo': [143, 12.6, 0.7, 9.5],
             'pan integral': [250, 12, 41, 4], 'lentejas': [352, 25, 60, 1], 'tomate': [18, 0.9, 3.9, 0.2], 'aceite de oliva': [884, 0, 0, 100]}
alimentos = {k: dict(zip(['kcal', 'proteina_g', 'carbohidrato_g', 'grasa_g'], v)) for k, v in alimentos.items()}
def prep(nombre, porc, notas=None):
    tot = {'kcal': 0, 'proteina_g': 0, 'carbohidrato_g': 0, 'grasa_g': 0}
    for a, g in porc:
        for k in tot: tot[k] += alimentos[a][k] * g / 100
    p = {'nombre': nombre, 'porciones': {'prueba': [{'alimento': a, 'gramos': g} for a, g in porc]},
         'aporte_estimado': {'prueba': {k: round(v) for k, v in tot.items()}}}
    if notas: p['notas'] = notas
    return p
preps = {
    'desayuno-avena': prep('Avena con leche (ejemplo)', [('avena', 40), ('leche descremada', 200)]),
    'desayuno-huevo': prep('Huevo con pan (ejemplo)', [('huevo', 100), ('pan integral', 50)]),
    'colacion-fruta': prep('Manzana (ejemplo)', [('manzana', 150)]),
    'colacion-yogur': prep('Yogur natural (ejemplo)', [('yogur natural', 150)]),
    'plato-pollo': prep('Pollo con arroz (ejemplo)', [('pollo', 120), ('arroz', 50), ('zapallo', 100)], 'Plato ficticio para pruebas.'),
    'plato-lentejas': prep('Lentejas con tomate (ejemplo)', [('lentejas', 60), ('tomate', 100), ('aceite de oliva', 5)]),
}
TIPOS = [('desayuno', '07:00'), ('colacion_am', '10:00'), ('almuerzo', '13:30'), ('colacion_pm', '17:00'), ('cena', '20:30')]
menus = [['desayuno-avena', 'colacion-fruta', 'plato-pollo', 'colacion-yogur', 'plato-lentejas'],
         ['desayuno-huevo', 'colacion-yogur', 'plato-lentejas', 'colacion-fruta', 'plato-pollo'],
         ['desayuno-avena', 'colacion-fruta', 'plato-pollo', 'colacion-yogur', 'plato-lentejas']]
dias = []
for i, m in enumerate(menus):
    f = f'2026-10-0{5 + i}'
    com = [{'tipo': t, 'hora': h, 'preparacion': p, 'origen': 'preparar-en-el-dia'} for (t, h), p in zip(TIPOS, m)]
    com[2]['conservacion'] = 'refrigerador'; com[2]['origen'] = 'tanda-domingo'
    dias.append({'fecha': f, 'etiqueta': f'Día de ejemplo {i + 1}', 'comidas': com,
                 'tareas': [{'hora': '21:00', 'texto': 'Tarea de ejemplo'}] if i == 0 else [],
                 'total_estimado_kcal': {'prueba': sum(preps[p]['aporte_estimado']['prueba']['kcal'] for p in m)}})
escribir('programa-alimentacion-ejemplo.json', {
    'formato': 'programa-alimentacion', 'version_formato': '1.0',
    'programa': {'id': 'programa-ejemplo', 'nombre': 'Programa de ejemplo (ficticio)', 'desde': '2026-10-05', 'hasta': '2026-10-07',
                 'zona_horaria': 'America/Santiago', 'autor': 'Pruebas automáticas', 'generado': '2026-10-10', 'nota': 'Datos inventados para pruebas.'},
    'perfiles': [{'id': 'prueba', 'nombre': 'Persona de prueba', 'meta_kcal': 1500, 'origen_meta': 'estimacion'}],
    'restricciones': ['Restricción de ejemplo.'], 'horario_por_defecto': dict(TIPOS),
    'alimentos': alimentos, 'preparaciones': preps, 'dias': dias})

# ---------- base 1.0 ficticia ----------
def ej(id_, nombre):
    return {'id': id_, 'nombre': nombre, 'descripcion': 'Ejercicio de ejemplo.', 'musculos_principales': ['ejemplo'], 'musculos_secundarios': [],
            'equipo': 'máquina', 'pasos': ['Paso uno.', 'Paso dos.'], 'errores_comunes': ['Error de ejemplo.']}
def serie(n, tipo, reps):
    return {'n': n, 'tipo': tipo, 'reps_min': reps[0], 'reps_max': reps[1], 'carga_kg': None, 'carga_indicacion': 'Carga de ejemplo.', 'rpe_objetivo': 6}
rutina = {'formato': 'genesis-rutina', 'version_formato': '1.0',
          'rutina': {'id': 'rutina-ejemplo', 'nombre': 'Rutina de ejemplo (ficticia)', 'version': 1, 'vigente_desde': '2026-10-05', 'autor': 'Pruebas automáticas', 'notas': 'Datos inventados.'},
          'sesiones': [{'id': 'sesion-lunes', 'dia_semana': 'lunes', 'nombre': 'A', 'duracion_estimada_min': 30,
                        'ejercicios': [{'ejercicio_id': 'curl-biceps-polea', 'orden': 1, 'descanso_seg': 60, 'series': [serie(1, 'efectiva', (10, 12)), serie(2, 'efectiva', (10, 12))]},
                                       {'ejercicio_id': 'ejercicio-fuera', 'orden': 2, 'descanso_seg': 60, 'series': [serie(1, 'efectiva', (8, 10))]}]}],
          'ejercicios': [ej('curl-biceps-polea', 'Curl de bíceps en polea'), ej('ejercicio-fuera', 'Ejercicio fuera del catálogo')]}
doc_rutina = {'cargada_por': 'Pruebas automáticas', 'importada_en': '2026-10-05T08:00:00-03:00', 'rutina': rutina}
escribir('base-1.0/config/rutina_activa.json', doc_rutina)
escribir('base-1.0/rutinas/rutina-ejemplo_v1.json', doc_rutina)
def ses(fecha, cargas, rpe):
    ejs = []
    for i, (eid, nombre, kgs) in enumerate([('curl-biceps-polea', 'Curl de bíceps en polea', cargas[:2]), ('ejercicio-fuera', 'Ejercicio fuera del catálogo', cargas[2:])]):
        ejs.append({'descanso_seg': 60, 'ejercicio_id': eid, 'nombre': nombre, 'notas': '', 'orden': i + 1,
                    'series': [{'carga_indicacion': 'Carga de ejemplo.', 'carga_prescrita_kg': None, 'carga_real_kg': kg, 'modo': 'rango', 'n': n + 1,
                                'prescrito_reps': '10–12', 'prescrito_seg': None, 'reps_reales': 11, 'rpe_objetivo': 6, 'seg_reales': None, 'tipo': 'efectiva'}
                               for n, kg in enumerate(kgs)]})
    return {'actualizado': fecha + 'T12:00:00.000Z', 'creado': fecha + 'T11:00:00.000Z', 'dia_semana': 'lunes', 'duracion_estimada_min': 30, 'duracion_min': 32,
            'ejercicios': ejs, 'estado': 'cerrada', 'fecha': fecha, 'nota': 'Sesión de ejemplo.', 'rpe_sesion': rpe,
            'rutina_id': 'rutina-ejemplo', 'rutina_version': 1, 'sesion_id': 'sesion-lunes', 'sesion_nombre': 'A'}
escribir('base-1.0/sesiones/2026-10-05_sesion-lunes.json', ses('2026-10-05', [10, 10, 20], 5))
escribir('base-1.0/sesiones/2026-10-12_sesion-lunes.json', ses('2026-10-12', [12.5, 12.5, 22.5], 6))
svg = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxMCAxMCI+PHJlY3Qgd2lkdGg9IjEwIiBoZWlnaHQ9IjEwIi8+PC9zdmc+'
img = lambda: {'autor': 'Pruebas automáticas', 'cargada': '2026-10-05', 'etiquetas': ['Ejemplo'], 'fuente': 'Imagen de ejemplo', 'licencia': 'propia',
               'nota': '', 'srcs': [svg], 'validada_entrenamiento': False}
escribir('base-1.0/imagenes/curl-biceps-polea.json', img())
escribir('base-1.0/imagenes/ejercicio-fuera.json', img())
print('datos de ejemplo creados en', AQUI)
