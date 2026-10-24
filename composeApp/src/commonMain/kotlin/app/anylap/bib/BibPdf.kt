package app.anylap.bib

import app.anylap.data.Racer
import app.anylap.platform.Platform
import app.anylap.platform.joinPath
import app.anylap.vision.Markers

/**
 * Printable A4 race bibs (one page per runner): colour band with the number, the marker drawn as
 * crisp vector squares, and the runner's name. Hand-written PDF so it works identically everywhere.
 */
object BibPdf {

    fun create(racers: List<Racer>): String {
        val pages = racers.filter { it.markerId != null }.map { pageContent(it) }
        val bytes = buildPdf(pages)
        val name = if (racers.size == 1) "bib_${racers[0].number}.pdf" else "anylap_bibs.pdf"
        val path = joinPath(Platform.cacheDir, name)
        Platform.writeBytes(path, bytes)
        return path
    }

    fun share(racers: List<Racer>) {
        Platform.shareFile(create(racers), "application/pdf", "Print or share bibs")
    }

    // ------------------------------------------------------------------ page

    private const val W = 595f
    private const val H = 842f

    private fun pageContent(r: Racer): String {
        val sb = StringBuilder()
        val argb = r.color
        val red = ((argb shr 16) and 0xFF) / 255f
        val green = ((argb shr 8) and 0xFF) / 255f
        val blue = (argb and 0xFF) / 255f

        // Colour band + number
        sb.append("${f(red)} ${f(green)} ${f(blue)} rg 0 ${f(H - 150)} ${f(W)} 150 re f\n")
        val num = r.number.toString()
        sb.append(textCentered(num, "F1", 110f, H - 118, 1f, 0.556f))

        // Marker: 6x6 cells + white margin (page is white already)
        val side = 400f
        val cell = side / 6f
        val left = (W - side) / 2f
        val top = H - 190f // top edge of marker
        sb.append("0 0 0 rg\n")
        val cells = Markers.cells(r.markerId ?: 0)
        for (row in 0 until 6) for (col in 0 until 6) {
            if (cells[row][col]) {
                val x = left + col * cell
                val y = top - (row + 1) * cell
                // Slight overlap avoids hairline gaps in some viewers.
                sb.append("${f(x)} ${f(y)} ${f(cell + 0.3f)} ${f(cell + 0.3f)} re f\n")
            }
        }

        // Name + footer
        sb.append(textCentered(r.name, "F1", 40f, top - side - 60f, 0f, 0.6f))
        sb.append(
            textCentered(
                "Marker ${r.markerId} - wear on the chest, flat and uncovered. Print at 100% scale.",
                "F2", 12f, 52f, 0.35f, 0.5f,
            ),
        )
        sb.append(textCentered("AnyLap", "F1", 12f, 32f, 0.35f, 0.6f))
        return sb.toString()
    }

    /** Centres text using an average glyph width (good enough for labels). */
    private fun textCentered(text: String, font: String, size: Float, y: Float, grey: Float, avgWidth: Float): String {
        val width = text.length * size * avgWidth
        val x = (W - width) / 2f
        return "BT ${f(grey)} ${f(grey)} ${f(grey)} rg /$font ${f(size)} Tf ${f(x)} ${f(y)} Td (${escape(text)}) Tj ET\n"
    }

    private fun escape(s: String): String {
        val out = StringBuilder()
        for (ch in s) {
            when {
                ch == '(' || ch == ')' || ch == '\\' -> out.append('\\').append(ch)
                ch.code in 32..126 -> out.append(ch)
                ch.code in 160..255 -> out.append("\\").append(ch.code.toString(8).padStart(3, '0'))
                else -> out.append('?')
            }
        }
        return out.toString()
    }

    private fun f(v: Float): String {
        val r = kotlin.math.round(v * 100f) / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }

    // ------------------------------------------------------------------ file

    private fun buildPdf(pageContents: List<String>): ByteArray {
        val objects = ArrayList<String>()
        // 1: catalog, 2: pages, 3: font bold, 4: font regular, then (page, content) pairs
        val pageIds = pageContents.indices.map { 5 + it * 2 }
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>"
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        pageContents.forEachIndexed { i, content ->
            val contentId = pageIds[i] + 1
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents $contentId 0 R >>"
            objects += "<< /Length ${content.encodeToByteArray().size} >>\nstream\n$content\nendstream"
        }
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += sb.toString().encodeToByteArray().size
            sb.append("${i + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xref = sb.toString().encodeToByteArray().size
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(o.toString().padStart(10, '0')).append(" 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }
}
