package dev.narumi.kestrel.core.routeplan

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.narumi.kestrel.core.routeplan.routeimport.ImportOutcome
import dev.narumi.kestrel.core.routeplan.routeimport.MAX_IMPORT_CHARS
import dev.narumi.kestrel.core.routeplan.routeimport.RouteImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Reads a shared or picked `content://` file and runs it through [RouteImporter]. The format is
 * detected from the content, so the MIME type the provider reports does not matter.
 */
object RouteFileReader {
    private const val BUFFER_BYTES = 16 * 1024

    suspend fun import(
        context: Context,
        uri: Uri,
    ): ImportOutcome =
        withContext(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver
            try {
                val bytes =
                    resolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            // A UTF-8 file cannot be shorter in bytes than in characters, so this bounds memory.
                            if (out.size() > MAX_IMPORT_CHARS) return@withContext ImportOutcome.Failure("The file is too large to import.")
                        }
                        out.toByteArray()
                    } ?: return@withContext ImportOutcome.Failure("Kestrel could not open that file.")
                RouteImporter.import(String(bytes, Charsets.UTF_8), displayName(context, uri))
            } catch (e: SecurityException) {
                Log.w("RouteFileReader", "File access denied: ${e.javaClass.simpleName}")
                ImportOutcome.Failure("Kestrel is not allowed to read that file. Try choosing it with Choose file.")
            } catch (e: IOException) {
                ImportOutcome.Failure("Could not read the file: ${e.message ?: "unknown error"}")
            }
        }

    private fun displayName(
        context: Context,
        uri: Uri,
    ): String? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrElse { error ->
            Log.w("RouteFileReader", "Could not read the file display name: ${error.javaClass.simpleName}")
            uri.lastPathSegment
        } ?: uri.lastPathSegment
}
