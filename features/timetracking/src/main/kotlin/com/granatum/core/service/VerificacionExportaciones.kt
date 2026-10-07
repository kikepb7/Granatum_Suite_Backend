package com.granatum.core.service

import com.granatum.core.domain.exception.FicheroDemasiadoGrandeException
import com.granatum.core.infrastructure.database.entities.ExportacionEntity
import com.granatum.core.infrastructure.database.repositories.ExportacionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Tells whether a file is one the system handed over, by its content alone
 * (FR-028, D-006, D-007).
 *
 * The file is **read as a stream and only hashed**. Nothing of it is stored,
 * logged or deserialised: it is a register full of names and identity
 * documents, and the one thing needed from it is its SHA-256. A body larger
 * than `verificacion-max-bytes` is refused as soon as it crosses the limit,
 * without reading the rest.
 */
@Service
class VerificacionExportaciones(
    private val exportaciones: ExportacionRepository,
    @param:Value("\${timetracking.exportacion.verificacion-max-bytes:104857600}")
    private val maxBytes: Long
) {
    data class Resultado(
        val huella: String,
        /** Every export with these exact bytes, oldest first. Empty: not a file this system produced, or altered. */
        val coincidencias: List<ExportacionEntity>
    )

    @Transactional(readOnly = true)
    fun verificar(fichero: InputStream): Resultado {
        val digest = MessageDigest.getInstance("SHA-256")
        val bloque = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = fichero.read(bloque)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw FicheroDemasiadoGrandeException(maxBytes)
            digest.update(bloque, 0, n)
        }
        val huella = HexFormat.of().formatHex(digest.digest())
        return Resultado(huella, exportaciones.findAllByHuellaOrderByGeneradaEnAsc(huella))
    }
}
