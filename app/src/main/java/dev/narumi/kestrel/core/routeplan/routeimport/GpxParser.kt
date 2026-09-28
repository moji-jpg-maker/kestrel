package dev.narumi.kestrel.core.routeplan.routeimport

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.routeplan.TrackPoint
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * Reads GPX 1.0/1.1 with SAX. Tracks win over routes, and routes over bare waypoints; only one of
 * the three is used so a file that carries several does not produce a doubled path.
 */
internal object GpxParser {
    private val DOCTYPE = Regex("<!(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)

    fun parse(text: String): ImportedRoute {
        // Refuse DTDs outright: it closes XXE and entity-expansion attacks even on parsers that do
        // not recognise the hardening features below (Android's does not recognise all of them).
        if (DOCTYPE.containsMatchIn(text)) {
            throw RouteImportException("GPX files that declare a DOCTYPE or entities are not supported.")
        }
        val handler = GpxHandler()
        readDocument(text, handler)
        handler.error?.let { throw RouteImportException(it) }
        return handler.result()
    }

    private fun readDocument(
        text: String,
        handler: GpxHandler,
    ) {
        try {
            newParserFactory().newSAXParser().parse(InputSource(StringReader(text)), handler)
        } catch (e: SAXException) {
            throw RouteImportException("Invalid GPX file: ${e.message}", e)
        } catch (e: IOException) {
            throw RouteImportException("Could not read the GPX file: ${e.message}", e)
        }
    }

    private fun newParserFactory(): SAXParserFactory {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        for ((feature, value) in HARDENING_FEATURES) {
            try {
                factory.setFeature(feature, value)
            } catch (_: Exception) {
                // Not every SAX implementation knows every feature; the DOCTYPE check above still holds.
            }
        }
        return factory
    }

    private val HARDENING_FEATURES =
        listOf(
            "http://javax.xml.XMLConstants/feature/secure-processing" to true,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )

    private class GpxHandler : DefaultHandler() {
        private val path = ArrayList<String>()
        private val text = StringBuilder()
        private val trackPoints = ArrayList<TrackPoint>()
        private val routePoints = ArrayList<TrackPoint>()
        private val waypoints = ArrayList<TrackPoint>()
        private var trackName: String? = null
        private var routeName: String? = null
        private var metadataName: String? = null
        private var segmentCount = 0
        private var pointLat = 0.0
        private var pointLng = 0.0
        private var pointTime: Long? = null
        var error: String? = null

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes,
        ) {
            val name = elementName(localName, qName)
            path += name
            text.setLength(0)
            when (name) {
                "trkseg" -> segmentCount++
                in POINT_ELEMENTS -> beginPoint(attributes)
            }
        }

        override fun characters(
            ch: CharArray,
            start: Int,
            length: Int,
        ) {
            if (text.length < MAX_TEXT_CHARS) text.append(ch, start, length)
        }

        override fun endElement(
            uri: String?,
            localName: String?,
            qName: String?,
        ) {
            val name = elementName(localName, qName)
            val parent = path.getOrNull(path.size - 2)
            val value = text.toString().trim()
            when {
                name == "time" && parent in POINT_ELEMENTS -> pointTime = parseTimeMs(value, allowRelative = false)
                name == "name" && value.isNotEmpty() -> recordName(parent, value)
                name in POINT_ELEMENTS -> commitPoint(name)
            }
            path.removeAt(path.lastIndex)
            text.setLength(0)
        }

        fun result(): ImportedRoute {
            val warnings = ArrayList<String>()
            val points =
                when {
                    trackPoints.isNotEmpty() -> {
                        if (routePoints.isNotEmpty()) warnings += "The file has a track and a route; using the track."
                        if (segmentCount > 1) warnings += "Joined $segmentCount track segments into one path."
                        trackPoints
                    }
                    routePoints.isNotEmpty() -> routePoints
                    else -> {
                        if (waypoints.isNotEmpty()) warnings += "No track or route found; using waypoints in file order."
                        waypoints
                    }
                }
            return ImportedRoute(trackName ?: routeName ?: metadataName, points, warnings = warnings)
        }

        private fun beginPoint(attributes: Attributes) {
            val lat = attributes.getValue("lat")?.trim()?.toDoubleOrNull()
            val lng = attributes.getValue("lon")?.trim()?.toDoubleOrNull()
            if (lat == null || lng == null) {
                if (error == null) error = "A GPX point is missing a valid lat/lon attribute."
                return
            }
            pointLat = lat
            pointLng = lng
            pointTime = null
        }

        private fun commitPoint(kind: String) {
            val point = TrackPoint(LatLng(pointLat, pointLng), pointTime)
            when (kind) {
                "trkpt" -> trackPoints += point
                "rtept" -> routePoints += point
                else -> waypoints += point
            }
        }

        private fun recordName(
            parent: String?,
            value: String,
        ) {
            when (parent) {
                "trk" -> if (trackName == null) trackName = value
                "rte" -> if (routeName == null) routeName = value
                "metadata" -> if (metadataName == null) metadataName = value
            }
        }

        private fun elementName(
            localName: String?,
            qName: String?,
        ): String = if (localName.isNullOrEmpty()) qName.orEmpty() else localName

        private companion object {
            val POINT_ELEMENTS = setOf("trkpt", "rtept", "wpt")
            const val MAX_TEXT_CHARS = 128
        }
    }
}
