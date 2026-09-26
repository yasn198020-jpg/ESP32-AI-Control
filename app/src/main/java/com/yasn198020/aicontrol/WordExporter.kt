package com.yasn198020.aicontrol

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object WordExporter {
    private const val DOCS_DIR = "ESP32_AI_Control_History"

    data class ExportedFile(
        val file: File,
        val pointCount: Int
    )

    fun exportJsonlDay(
        context: Context,
        historyFile: File,
        dayLabel: String
    ): ExportedFile? =
        exportJsonlFiles(context, listOf(historyFile), dayLabel)

    fun exportJsonlFiles(
        context: Context,
        historyFiles: List<File>,
        periodLabel: String
    ): ExportedFile? {
        val files = historyFiles.filter { it.isFile && it.length() > 0L }
        if (files.isEmpty()) return null

        val points = mutableListOf<HistoryPoint>()
        runCatching {
            files.forEach { historyFile ->
                historyFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching {
                    val o = JSONObject(line)
                    HistoryPoint(
                        timestamp = o.optLong("t"),
                        deviceId = o.optString("d"),
                        widgetId = o.optString("w"),
                        value = o.optDouble("v", Double.NaN)
                    )
                }.getOrNull()?.takeIf { it.timestamp > 0L && it.value.isFinite() }
                    ?.let(points::add)
                }
            }
            }
        }.getOrElse {
            DiagnosticTrace.system("HISTORY Word read failed: " + (it.message ?: it.javaClass.simpleName))
            return null
        }

        if (points.isEmpty()) return null

        val docsDir = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS),
            DOCS_DIR
        )
        if (!docsDir.exists() && !docsDir.mkdirs()) {
            DiagnosticTrace.system("HISTORY Word mkdir failed: " + docsDir.absolutePath)
            return null
        }

        val safeLabel = periodLabel.replace(Regex("[^0-9A-Za-zА-Яа-я._-]"), "_")
        val outFile = File(docsDir, "history_${safeLabel}.docx")
        return runCatching {
            ZipOutputStream(FileOutputStream(outFile)).use { zip ->
                writeTextEntry(zip, "[Content_Types].xml", contentTypesXml())
                writeTextEntry(zip, "_rels/.rels", rootRelsXml())
                writeTextEntry(zip, "word/document.xml", documentXml(points, periodLabel))
                writeTextEntry(zip, "word/_rels/document.xml.rels", documentRelsXml())
            }
            ExportedFile(outFile, points.size)
        }.onFailure {
            DiagnosticTrace.system(
                "HISTORY Word write failed: " +
                    (it.message ?: it.javaClass.simpleName)
            )
            outFile.delete()
        }.getOrNull()
    }

    private fun writeTextEntry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        writer.write(text)
        writer.flush()
        zip.closeEntry()
    }

    private fun documentXml(points: List<HistoryPoint>, dayLabel: String): String {
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val grouped = points.groupBy { it.deviceId to it.widgetId }
            .toList()
            .sortedBy { it.first.first + "/" + it.first.second }

        val body = StringBuilder()
        body.append(paragraph("ESP32 AI Control — история измерений"))
        body.append(paragraph("Дата: ${xmlEscape(dayLabel)}"))
        body.append(paragraph("Измерений: ${points.size}"))

        grouped.forEach { (key, values) ->
            body.append(paragraph("Устройство: ${xmlEscape(key.first)}"))
            body.append(paragraph("Виджет: ${xmlEscape(key.second)}"))
            body.append(
                table(
                    listOf("Время", "Значение") +
                        values.sortedBy { it.timestamp }.map { point ->
                            listOf(time.format(Date(point.timestamp)), formatValue(point.value))
                        }
                )
            )
            body.append(paragraph(" "))
        }

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    $body
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/>
    </w:sectPr>
  </w:body>
</w:document>"""
    }

    private fun paragraph(text: String): String =
        "<w:p><w:r><w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"

    private fun table(rows: List<List<String>>): String {
        val xml = StringBuilder()
        xml.append("<w:tbl>")
        xml.append(
            "<w:tblPr><w:tblBorders>" +
                "<w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/>" +
                "<w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/>" +
                "<w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/>" +
                "</w:tblBorders></w:tblPr>"
        )
        rows.forEachIndexed { rowIndex, row ->
            xml.append("<w:tr>")
            row.forEach { cell ->
                xml.append("<w:tc><w:tcPr/>")
                xml.append(
                    "<w:p><w:r>" +
                        (if (rowIndex == 0) "<w:rPr><w:b/></w:rPr>" else "") +
                        "<w:t xml:space=\"preserve\">${xmlEscape(cell)}</w:t>" +
                        "</w:r></w:p></w:tc>"
                )
            }
            xml.append("</w:tr>")
        }
        xml.append("</w:tbl>")
        return xml.toString()
    }

    private fun formatValue(value: Double): String =
        String.format(Locale.US, "%.3f", value)

    private fun xmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun contentTypesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private fun rootRelsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private fun documentRelsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""
}
