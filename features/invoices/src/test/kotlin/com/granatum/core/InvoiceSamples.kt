package com.granatum.core

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Test documents. Each call returns different bytes (a unique marker inside),
 * so tests sharing a database never collide on the duplicate-file check.
 */
object InvoiceSamples {

    fun pdf(pages: Int = 1, marker: String = UUID.randomUUID().toString()): ByteArray =
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            doc.documentInformation.title = marker
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    fun png(marker: Int = (0..0xFFFFFF).random()): ByteArray {
        val img = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, marker) }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    fun text(): ByteArray = "esto no es una factura ${UUID.randomUUID()}".toByteArray()

    /** A valid PDF header padded past the 10 MB per-file limit. */
    fun tooLargePdf(): ByteArray = "%PDF-1.7\n".toByteArray() + ByteArray(11 * 1024 * 1024)
}
