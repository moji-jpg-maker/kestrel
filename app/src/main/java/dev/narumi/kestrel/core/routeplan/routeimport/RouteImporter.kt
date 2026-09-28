package dev.narumi.kestrel.core.routeplan.routeimport

const val MAX_IMPORT_CHARS = 32_000_000

private const val SNIFF_WINDOW = 2_000

/**
 * Entry point for turning file or clipboard text into an [ImportedRoute].
 *
 * The format is detected from the content, not the file name or MIME type, because `content://`
 * providers often report `application/octet-stream`. Supported: GPX, GeoJSON, Kestrel plan JSON,
 * GraphHopper / Valhalla / OSRM route responses, CSV, and plain coordinate lists.
 */
object RouteImporter {
    /** [fileName] is only used to name the route when the file does not name itself. */
    fun import(
        text: String,
        fileName: String? = null,
    ): ImportOutcome =
        try {
            ImportOutcome.Success(importOrThrow(text, fileName))
        } catch (e: RouteImportException) {
            ImportOutcome.Failure(e.message ?: "The route could not be imported.")
        } catch (e: JsonSyntaxException) {
            ImportOutcome.Failure("Invalid JSON: ${e.message}")
        }

    private fun importOrThrow(
        text: String,
        fileName: String?,
    ): ImportedRoute {
        val head = validatedInput(text)
        val parsed =
            when (head.first()) {
                '<' -> parseXml(head, text)
                '{' -> parseJson(text)
                '[' ->
                    throw RouteImportException(
                        "A bare JSON array is ambiguous (latitude/longitude order). Use GeoJSON or a Kestrel plan object.",
                    )
                else -> CsvRouteParser.parse(text)
            }
        val cleaned = RouteSanitizer.clean(parsed)
        return if (cleaned.name == null) cleaned.copy(name = nameFromFile(fileName)) else cleaned
    }

    private fun validatedInput(text: String): String {
        if (text.length > MAX_IMPORT_CHARS) throw RouteImportException("The file is too large to import.")
        val head = text.trimStart { it == '\uFEFF' || it.isWhitespace() }
        if (head.isEmpty()) throw RouteImportException("The file is empty.")
        return head
    }

    private fun parseXml(
        head: String,
        text: String,
    ): ImportedRoute {
        val window = head.take(SNIFF_WINDOW)
        return when {
            window.contains("<gpx", ignoreCase = true) -> GpxParser.parse(text)
            window.contains("<kml", ignoreCase = true) ->
                throw RouteImportException("KML is not supported. Export the route as GPX instead.")
            else -> throw RouteImportException("This XML file is not GPX.")
        }
    }

    private fun parseJson(text: String): ImportedRoute {
        val root = MiniJson.parse(text).asJsonObject() ?: throw RouteImportException("Expected a JSON object.")
        return if (root["type"] in GEOJSON_TYPES) GeoJsonParser.parse(root) else JsonRouteParser.parse(root)
    }

    private fun nameFromFile(fileName: String?): String? =
        fileName
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
