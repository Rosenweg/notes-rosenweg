package org.fossify.notes.helpers

import org.fossify.commons.helpers.PROTECTION_NONE
import android.content.Context
import android.util.Log
import ch.rosenweg4303.cloud.Ablage
import ch.rosenweg4303.cloud.Eintrag
import ch.rosenweg4303.cloud.Hochladen
import ch.rosenweg4303.cloud.NextcloudFehler
import ch.rosenweg4303.cloud.WebDav
import org.fossify.notes.extensions.notesDB
import org.fossify.notes.models.Note
import org.fossify.notes.models.NoteType
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rosenweg: Notizen sind Textdateien in der Nextcloud der Person
 * (Persönlich/Notizen). Die App haelt einen Spiegel davon in einem privaten
 * Ordner; jede Notiz ist mit ihrer Spiegel-Datei verknuepft (Note.path) —
 * so liest und schreibt Fossify Notes sie wie jede Datei-Notiz.
 *
 * Abgleich (beim Oeffnen, beim Verlassen, kurz nach dem Speichern):
 * - nur in der Nextcloud geaendert -> herunterladen
 * - nur hier geaendert -> hochladen (If-Match auf die bekannte Fassung)
 * - beide geaendert -> die hiesige Fassung als Konfliktkopie hochladen, dann
 *   die aus der Nextcloud uebernehmen (nichts geht verloren)
 * - neu auf einer Seite -> auf die andere; geloescht auf einer Seite -> auf
 *   der anderen loeschen, ausser die andere wurde inzwischen geaendert
 *
 * Neue und bisherige Text-Notizen (ohne Datei, ohne Sperre) werden dabei zu
 * Dateien. Checklisten und gesperrte Notizen bleiben vorerst lokal.
 */
object NotizenAbgleich {
    const val ORDNER = "${Hochladen.PERSOENLICH}/Notizen"
    private const val TAG = "RosenwegNotizen"
    private const val STAND = "rosenweg_notizen_stand"
    private val ENDUNGEN = setOf("txt", "md")
    private val sperre = Any()

    fun spiegel(ctx: Context) = File(ctx.filesDir, "nextcloud-notizen").apply { mkdirs() }

    fun istSpiegel(ctx: Context, pfad: String) =
        pfad.isNotEmpty() && File(pfad).absolutePath.startsWith(spiegel(ctx).absolutePath + "/")

    /** Gleicht ab. Blockiert. @return true, wenn sich Notizen hier geaendert haben (Anzeige neu laden). */
    fun abgleichen(ctx: Context): Boolean = synchronized(sperre) {
        val dav = Ablage.webDav(ctx) ?: return false
        return try {
            val neuAngelegt = notizenZuDateien(ctx)
            val geaendert = dateienAbgleichen(ctx, dav)
            val neueNotizen = dateienZuNotizen(ctx)
            neuAngelegt || geaendert || neueNotizen
        } catch (e: Exception) {
            Log.w(TAG, "Abgleich: $e")
            false
        }
    }

    // ---- 1. Text-Notizen ohne Datei werden Dateien im Spiegel ----

    private fun notizenZuDateien(ctx: Context): Boolean {
        var etwas = false
        for (n in ctx.notesDB.getNotes()) {
            if (n.path.isNotEmpty() || n.type != NoteType.TYPE_TEXT || n.isLocked()) continue
            val datei = freieDatei(ctx, dateiname(n.title))
            datei.writeText(n.value)
            n.path = datei.absolutePath
            ctx.notesDB.insertOrUpdate(n)
            etwas = true
        }
        return etwas
    }

    // ---- 2. Spiegel <-> Nextcloud ----

    private fun dateienAbgleichen(ctx: Context, dav: WebDav): Boolean {
        val oben: Map<String, Eintrag> = try {
            dav.liste(ORDNER)
        } catch (e: NextcloudFehler) {
            if (e.code != 404) throw e
            dav.ordnerSicherstellen(ORDNER)
            emptyList()
        }.filter { !it.istOrdner && endung(it.name) in ENDUNGEN }.associateBy { it.name }

        val dir = spiegel(ctx)
        val hier = (dir.listFiles() ?: emptyArray()).filter { it.isFile && endung(it.name) in ENDUNGEN }.associateBy { it.name }
        val stand = stand(ctx)
        var lokalGeaendert = false

        for (name in (oben.keys + hier.keys + stand.keys().asSequence().toList()).toSet()) {
            val r = oben[name]
            val l = hier[name]
            val s = stand.optJSONObject(name)
            val sEtag = s?.optString("etag")
            val hierNeu = l != null && (s == null || s.optString("stempel") != stempel(l))
            val obenNeu = r != null && (s == null || r.etag != sEtag)
            val pfad = "$ORDNER/$name"
            try {
                when {
                    r != null && l != null -> when {
                        !hierNeu && !obenNeu -> {}
                        !hierNeu && obenNeu -> { laden(dav, pfad, l); merken(stand, name, r.etag, l); lokalGeaendert = true }
                        hierNeu && !obenNeu -> merken(stand, name, hochladen(dav, pfad, l, sEtag), l)
                        else -> { // beide (oder erstes Mal mit beiden Seiten)
                            val tmp = File(ctx.cacheDir, "notiz-oben.tmp")
                            dav.herunterladen(pfad, tmp)
                            if (tmp.readBytes().contentEquals(l.readBytes())) {
                                merken(stand, name, r.etag, l)
                            } else {
                                konfliktkopie(dav, name, l)
                                tmp.copyTo(l, overwrite = true)
                                merken(stand, name, r.etag, l)
                                lokalGeaendert = true
                            }
                            tmp.delete()
                        }
                    }
                    r != null && l == null -> if (s != null && !obenNeu) {
                        dav.loeschen(pfad) // hier geloescht
                        stand.remove(name)
                    } else {
                        val neu = File(dir, name)
                        laden(dav, pfad, neu)
                        merken(stand, name, r.etag, neu)
                        lokalGeaendert = true
                    }
                    r == null && l != null -> if (s != null && !hierNeu) {
                        l.delete() // in der Nextcloud geloescht -> Notiz verschwindet beim naechsten Laden
                        stand.remove(name)
                        lokalGeaendert = true
                    } else {
                        merken(stand, name, hochladen(dav, pfad, l, null), l)
                    }
                    else -> stand.remove(name)
                }
            } catch (e: NextcloudFehler) {
                if (e.code == 412) {
                    // jemand war schneller: beim naechsten Abgleich als "beide geaendert"
                    stand.remove(name)
                } else {
                    Log.w(TAG, "$name: ${e.code} ${e.message}")
                }
            }
        }
        speichern(ctx, stand)
        return lokalGeaendert
    }

    // ---- 3. Spiegel-Dateien ohne Notiz bekommen eine ----

    private fun dateienZuNotizen(ctx: Context): Boolean {
        val bekannt = ctx.notesDB.getNotes().map { File(it.path).absolutePath }.toSet()
        var etwas = false
        (spiegel(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile && endung(it.name) in ENDUNGEN && it.absolutePath !in bekannt }
            .forEach {
                ctx.notesDB.insertOrUpdate(
                    Note(null, titel(ctx, it.nameWithoutExtension), "", NoteType.TYPE_TEXT, it.absolutePath, PROTECTION_NONE, "")
                )
                etwas = true
            }
        return etwas
    }

    // ---- Hilfen ----

    private fun hochladen(dav: WebDav, pfad: String, l: File, etag: String?): String? {
        val neu = dav.hochladen(pfad, { l.inputStream() }, l.length(), "text/plain", l.lastModified(), wennEtag = etag, nurNeu = etag == null)
        return neu ?: dav.eigenschaften(pfad)?.etag
    }

    private fun laden(dav: WebDav, pfad: String, ziel: File) = dav.herunterladen(pfad, ziel)

    private fun konfliktkopie(dav: WebDav, name: String, l: File) {
        val zeit = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date())
        val k = "${name.substringBeforeLast('.')} (Konflikt Telefon $zeit).${endung(name)}"
        dav.hochladen("$ORDNER/$k", { l.inputStream() }, l.length(), "text/plain", nurNeu = true)
    }

    private fun merken(stand: JSONObject, name: String, etag: String?, l: File) {
        stand.put(name, JSONObject().put("etag", etag ?: "").put("stempel", stempel(l)))
    }

    private fun stempel(f: File) = "${f.length()}/${f.lastModified()}"
    private fun endung(n: String) = n.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Dateiname aus dem Titel: ohne Schraegstriche, Endung .txt, falls keine. */
    fun dateiname(titel: String): String {
        val n = titel.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "Notiz" }
        return if (endung(n) in ENDUNGEN) n else "$n.txt"
    }

    private fun freieDatei(ctx: Context, name: String): File {
        val dir = spiegel(ctx)
        var f = File(dir, name)
        var i = 1
        while (f.exists()) f = File(dir, "${name.substringBeforeLast('.')} ($i).${endung(name)}").also { i++ }
        return f
    }

    /** Titel muessen in Fossify Notes eindeutig sein. */
    private fun titel(ctx: Context, wunsch: String): String {
        val vorhanden = ctx.notesDB.getNotes().map { it.title.lowercase(Locale.ROOT) }.toSet()
        if (wunsch.lowercase(Locale.ROOT) !in vorhanden) return wunsch
        var i = 2
        while ("$wunsch ($i)".lowercase(Locale.ROOT) in vorhanden) i++
        return "$wunsch ($i)"
    }

    private fun stand(ctx: Context): JSONObject =
        try {
            JSONObject(ctx.getSharedPreferences(STAND, Context.MODE_PRIVATE).getString("stand", "{}")!!)
        } catch (e: Exception) {
            JSONObject()
        }

    private fun speichern(ctx: Context, s: JSONObject) {
        ctx.getSharedPreferences(STAND, Context.MODE_PRIVATE).edit().putString("stand", s.toString()).apply()
    }
}
