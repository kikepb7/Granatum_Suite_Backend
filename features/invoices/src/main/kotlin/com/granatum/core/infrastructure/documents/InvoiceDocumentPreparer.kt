package com.granatum.core.infrastructure.documents

import com.granatum.core.domain.port.RecognitionDocument
import com.granatum.core.files.DocumentType
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Why a file cannot be taken as an invoice, decided at upload (contracts/README.md). */
sealed class RejectedInvoiceDocument(val result: String) : RuntimeException(result) {
    class UnreadablePdf : RejectedInvoiceDocument("UNREADABLE_PDF")
    class TooLarge : RejectedInvoiceDocument("TOO_LARGE")
}

/**
 * Checks an uploaded document and prepares the copy sent to Claude
 * (research.md D-007). The stored original is never modified.
 *
 * Limits, from the API's vision documentation (checked 2026-10-08): images up
 * to 10 MB in base64 and 8000x8000 px; above 2576 px on the long edge, the
 * current models downscale anyway. So a larger image is reduced to that long
 * edge before sending - same result, fewer bytes, never over the limit. PDFs
 * are sent as they are: up to 32 MB per request and 600 pages, far above an
 * invoice.
 */
@Component
class InvoiceDocumentPreparer(
    @param:Value("\${invoices.documents.pdf-max-pages:20}") private val pdfMaxPages: Int
) {
    companion object {
        const val MAX_LONG_EDGE = 2576
        /** 10 MB once base64-encoded (4/3 of the raw size). */
        private const val MAX_BYTES_UNREDUCED = 10_000_000L * 3 / 4
    }

    /**
     * At upload, before anything is stored: refuses what could never be read.
     * @throws RejectedInvoiceDocument
     */
    fun inspect(content: ByteArray, type: DocumentType) {
        when (type) {
            DocumentType.PDF -> inspectPdf(content)
            // The JVM cannot decode WebP, so it cannot be reduced: one too big
            // to send is refused now rather than failing at recognition.
            DocumentType.WEBP -> if (content.size > MAX_BYTES_UNREDUCED) throw RejectedInvoiceDocument.TooLarge()
            DocumentType.JPEG, DocumentType.PNG -> Unit
        }
    }

    fun forSending(content: ByteArray, type: DocumentType): RecognitionDocument {
        if (type == DocumentType.PDF || type == DocumentType.WEBP) return RecognitionDocument(content, type.mediaType)
        val image = ImageIO.read(ByteArrayInputStream(content))
            ?: return RecognitionDocument(content, type.mediaType)
        val longEdge = maxOf(image.width, image.height)
        if (longEdge <= MAX_LONG_EDGE && content.size <= MAX_BYTES_UNREDUCED) {
            return RecognitionDocument(content, type.mediaType)
        }
        return RecognitionDocument(downscaleToJpeg(image, longEdge), DocumentType.JPEG.mediaType)
    }

    private fun inspectPdf(content: ByteArray) {
        try {
            Loader.loadPDF(content).use { doc ->
                if (doc.isEncrypted || doc.numberOfPages !in 1..pdfMaxPages) throw RejectedInvoiceDocument.UnreadablePdf()
            }
        } catch (e: InvalidPasswordException) {
            throw RejectedInvoiceDocument.UnreadablePdf()
        } catch (e: IOException) {
            throw RejectedInvoiceDocument.UnreadablePdf()
        }
    }

    private fun downscaleToJpeg(image: BufferedImage, longEdge: Int): ByteArray {
        val scale = minOf(1.0, MAX_LONG_EDGE.toDouble() / longEdge)
        val width = maxOf(1, Math.round(image.width * scale).toInt())
        val height = maxOf(1, Math.round(image.height * scale).toInt())
        val reduced = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        reduced.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(image, 0, 0, width, height, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.9f
            }
            writer.write(null, IIOImage(reduced, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
