package io.github.sansolo2000.genesisgym

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Base local del celular: los MISMOS documentos que la 2.0 (ruta "coleccion/id" → objeto JSON),
 * guardados en SQLite. Así un respaldo pasa entre la 2.0 y la nativa sin conversión.
 * El JSON se guarda con JsJson (igual que JavaScript), para que la huella sea la misma.
 */
class BaseLocal(context: Context) : SQLiteOpenHelper(context.applicationContext, "genesis-gym-n.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE docs (ruta TEXT PRIMARY KEY, col TEXT NOT NULL, data TEXT NOT NULL, actualizado TEXT NOT NULL)")
        db.execSQL("CREATE INDEX docs_col ON docs(col)")
    }
    override fun onUpgrade(db: SQLiteDatabase, viejo: Int, nuevo: Int) {}

    private fun valores(ruta: String, data: JsonObject) = ContentValues().apply {
        put("ruta", ruta); put("col", ruta.substringBefore('/')); put("data", JsJson.stringify(data)); put("actualizado", Gimnasio.ahoraIso())
    }

    fun get(ruta: String): JsonObject? = readableDatabase.rawQuery("SELECT data FROM docs WHERE ruta = ?", arrayOf(ruta)).use {
        if (it.moveToFirst()) Json.parseToJsonElement(it.getString(0)).jsonObject else null
    }

    fun put(ruta: String, data: JsonObject) { writableDatabase.insertWithOnConflict("docs", null, valores(ruta, data), SQLiteDatabase.CONFLICT_REPLACE) }

    fun borrar(ruta: String) { writableDatabase.delete("docs", "ruta = ?", arrayOf(ruta)) }

    fun coleccion(col: String): List<Respaldo.Doc> = leer("SELECT ruta, data FROM docs WHERE col = ?", arrayOf(col))
    fun todos(): List<Respaldo.Doc> = leer("SELECT ruta, data FROM docs", null)

    private fun leer(sql: String, args: Array<String>?): List<Respaldo.Doc> = readableDatabase.rawQuery(sql, args).use { c ->
        val l = mutableListOf<Respaldo.Doc>()
        while (c.moveToNext()) l.add(Respaldo.Doc(c.getString(0), Json.parseToJsonElement(c.getString(1)).jsonObject))
        l
    }

    /** Escribe varios documentos en una sola transacción (todos o ninguno). */
    fun ponerVarios(docs: List<Respaldo.Doc>) {
        val db = writableDatabase
        db.beginTransaction()
        try { for (d in docs) db.insertWithOnConflict("docs", null, valores(d.ruta, d.data), SQLiteDatabase.CONFLICT_REPLACE); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    /** Reemplaza TODA la base (restaurar un respaldo): o queda todo, o no cambia nada. */
    fun reemplazarTodo(docs: List<Respaldo.Doc>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("docs", null, null)
            for (d in docs) db.insertOrThrow("docs", null, valores(d.ruta, d.data))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
