package com.granatum.core.infrastructure.database

import com.granatum.core.domain.exception.FacturaNoEncontradaException
import com.granatum.core.domain.port.AlmacenDocumentos
import com.granatum.core.domain.port.DocumentoGuardado
import com.granatum.core.infrastructure.database.entities.FacturaDocumentoEntity
import com.granatum.core.infrastructure.database.repositories.FacturaDocumentoRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Originals in `factura_documentos`, in the same transaction as their invoice (research.md D-008). */
@Component
class AlmacenDocumentosPostgres(private val documentos: FacturaDocumentoRepository) : AlmacenDocumentos {

    override fun guardar(facturaId: UUID, contenido: ByteArray, mediaType: String, nombreOriginal: String?) {
        documentos.save(FacturaDocumentoEntity(facturaId, contenido, mediaType, contenido.size, nombreOriginal?.take(255)))
    }

    @Transactional(readOnly = true)
    override fun leer(facturaId: UUID): DocumentoGuardado =
        documentos.findById(facturaId)
            .map { DocumentoGuardado(it.contenido, it.mediaType) }
            .orElseThrow { FacturaNoEncontradaException(facturaId) }
}
