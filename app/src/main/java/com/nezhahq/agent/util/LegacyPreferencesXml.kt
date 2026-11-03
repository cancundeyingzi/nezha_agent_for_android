package com.nezhahq.agent.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/**
 * Read-only parser for the SharedPreferences XML subset. Android's preferences loader silently
 * converts malformed files to empty maps and moves .bak files, so it cannot establish migration
 * authority. The same SAX path is available on API 23 and the host JVM.
 */
internal object LegacyPreferencesXml {
    fun read(file: File): ConfigurationValues = try {
        val bytes = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(bytes.size() + count <= MAX_BYTES) { "Legacy preferences exceed size limit" }
                bytes.write(buffer, 0, count)
            }
        }
        // Platform SharedPreferences writes UTF-8. Decode strictly before scanning, then pass a
        // character source: an XML encoding declaration cannot hide a DTD from this check.
        val xml = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
        check(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "Legacy DTD is forbidden" }
        val handler = PreferencesHandler()
        // No vendor-specific JAXP feature flags: Android and desktop parsers support different
        // sets. DTD rejection above plus a rejecting resolver prevents external/entity expansion.
        SAXParserFactory.newInstance().newSAXParser().xmlReader.apply {
            contentHandler = handler
            errorHandler = handler
            entityResolver = handler
        }.parse(InputSource(StringReader(xml)))
        ConfigurationValues.from(handler.values)
    } catch (failure: Exception) {
        throw IOException("Unable to read legacy preferences", failure)
    }

    private class PreferencesHandler : DefaultHandler() {
        val values = linkedMapOf<String, Any>()
        private var depth = 0
        private var rootSeen = false
        private var rootClosed = false
        private var key = ""
        private var type = ""
        private var scalar = ""
        private val text = StringBuilder()
        private val strings = linkedSetOf<String>()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            depth++
            when (depth) {
                1 -> {
                    valid(!rootSeen && qName == "map" && attributes.length == 0)
                    rootSeen = true
                }
                2 -> {
                    valid(!rootClosed && values.size < MAX_ENTRIES)
                    valid(qName in setOf("string", "int", "long", "float", "boolean", "set"))
                    type = qName
                    val attributeNames = (0 until attributes.length).map { attributes.getQName(it) }.toSet()
                    val expected = if (type == "string" || type == "set") setOf("name") else setOf("name", "value")
                    valid(attributeNames == expected && attributes.length == expected.size)
                    key = attributes.getValue("name")
                    valid(!values.containsKey(key))
                    scalar = attributes.getValue("value").orEmpty()
                    text.setLength(0)
                    strings.clear()
                }
                3 -> {
                    valid(type == "set" && qName == "string" && attributes.length == 0 && strings.size < MAX_ENTRIES)
                    text.setLength(0)
                }
                else -> throw SAXException("Unsupported legacy preferences nesting")
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if ((depth == 2 && type == "string") || (depth == 3 && type == "set")) {
                valid(text.length + length <= MAX_TEXT)
                text.append(ch, start, length)
            } else {
                valid((start until start + length).all { ch[it].isWhitespace() })
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (depth) {
                3 -> { valid(type == "set" && qName == "string"); strings.add(text.toString()) }
                2 -> {
                    valid(type == qName)
                    values[key] = when (type) {
                        "string" -> text.toString()
                        "int" -> scalar.toInt()
                        "long" -> scalar.toLong()
                        "float" -> scalar.toFloat()
                        "boolean" -> when (scalar) {
                            "true" -> true
                            "false" -> false
                            else -> throw SAXException("Invalid legacy boolean")
                        }
                        "set" -> strings.toSet()
                        else -> throw SAXException("Unsupported legacy value")
                    }
                }
                1 -> { valid(qName == "map"); rootClosed = true }
                else -> throw SAXException("Invalid legacy preferences structure")
            }
            depth--
        }

        override fun endDocument() = valid(rootSeen && rootClosed && depth == 0)
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            throw SAXException("External entities are forbidden")
        override fun skippedEntity(name: String?) = throw SAXException("Unresolved entity is forbidden")
        override fun warning(e: SAXParseException) = throw e
        override fun error(e: SAXParseException) = throw e
        override fun fatalError(e: SAXParseException) = throw e

        private fun valid(condition: Boolean) {
            if (!condition) throw SAXException("Invalid legacy preferences")
        }
    }

    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_TEXT = 64 * 1024
    private const val MAX_ENTRIES = 256
}
