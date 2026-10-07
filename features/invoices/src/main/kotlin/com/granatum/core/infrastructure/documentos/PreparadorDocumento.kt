package com.granatum.core.infrastructure.documentos

import com.granatum.core.domain.port.DocumentoParaEnviar
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
sealed class DocumentoNoAdmitido(val resultado: String) : RuntimeException(resultado) {
    class PdfNoLegible : DocumentoNoAdmitido("PDF_NO_LEGIBLE")
    class DemasiadoGrande : DocumentoNoAdmitido("DEMASIADO_GRANDE")
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
class PreparadorDocumento(
    @param:Value("\${invoices.documentos.pdf-max-paginas:20}") private val pdfMaxPaginas: Int
) {
    companion object {
        const val LADO_LARGO_MAXIMO = 2576
        /** 10 MB once base64-encoded (4/3 of the raw size). */
        private const val MAX_BYTES_SIN_REDUCIR = 10_000_000L * 3 / 4
    }

    /**
     * At upload, before anything is stored: refuses what could never be read.
     * @throws DocumentoNoAdmitido
     */
    fun examinar(contenido: ByteArray, tipo: TipoDocumento) {
        when (tipo) {
            TipoDocumento.PDF -> examinarPdf(contenido)
            // The JVM cannot decode WebP, so it cannot be reduced: one too big
            // to send is refused now rather than failing at recognition.
            TipoDocumento.WEBP -> if (contenido.size > MAX_BYTES_SIN_REDUCIR) throw DocumentoNoAdmitido.DemasiadoGrande()
            TipoDocumento.JPEG, TipoDocumento.PNG -> Unit
        }
    }

    fun paraEnviar(contenido: ByteArray, tipo: TipoDocumento): DocumentoParaEnviar {
        if (tipo == TipoDocumento.PDF || tipo == TipoDocumento.WEBP) return DocumentoParaEnviar(contenido, tipo.mediaType)
        val imagen = ImageIO.read(ByteArrayInputStream(contenido))
            ?: return DocumentoParaEnviar(contenido, tipo.mediaType)
        val ladoLargo = maxOf(imagen.width, imagen.height)
        if (ladoLargo <= LADO_LARGO_MAXIMO && contenido.size <= MAX_BYTES_SIN_REDUCIR) {
            return DocumentoParaEnviar(contenido, tipo.mediaType)
        }
        return DocumentoParaEnviar(reducirAJpeg(imagen, ladoLargo), TipoDocumento.JPEG.mediaType)
    }

    private fun examinarPdf(contenido: ByteArray) {
        try {
            Loader.loadPDF(contenido).use { doc ->
                if (doc.isEncrypted || doc.numberOfPages !in 1..pdfMaxPaginas) throw DocumentoNoAdmitido.PdfNoLegible()
            }
        } catch (e: InvalidPasswordException) {
            throw DocumentoNoAdmitido.PdfNoLegible()
        } catch (e: IOException) {
            throw DocumentoNoAdmitido.PdfNoLegible()
        }
    }

    private fun reducirAJpeg(imagen: BufferedImage, ladoLargo: Int): ByteArray {
        val escala = minOf(1.0, LADO_LARGO_MAXIMO.toDouble() / ladoLargo)
        val ancho = maxOf(1, Math.round(imagen.width * escala).toInt())
        val alto = maxOf(1, Math.round(imagen.height * escala).toInt())
        val reducida = BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB)
        reducida.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(imagen, 0, 0, ancho, alto, null)
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
            writer.write(null, IIOImage(reducida, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
