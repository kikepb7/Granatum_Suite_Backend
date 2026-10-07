package com.granatum.core

import com.granatum.core.domain.model.LineaPropuesta
import com.granatum.core.domain.model.PropuestaReconocida
import com.granatum.core.domain.port.DocumentoParaEnviar
import com.granatum.core.domain.port.ReconocedorFacturas
import com.granatum.core.domain.port.ResultadoReconocimiento
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The double of [ReconocedorFacturas] that every test of the suite uses: no
 * test calls the real API, spends money or needs the network (research.md
 * D-019). Scripted per test, and it records whether it was called with a
 * transaction open - which must never happen (D-005).
 */
class ReconocedorFalso : ReconocedorFacturas {

    @Volatile override var activo: Boolean = true

    /** What the next calls return. */
    @Volatile var guion: (DocumentoParaEnviar) -> ResultadoReconocimiento = { reconocida(propuesta()) }

    /** When set, every call waits on it before answering. */
    @Volatile var esperar: CountDownLatch? = null

    data class Llamada(val conTransaccion: Boolean, val mediaType: String)

    val llamadas = CopyOnWriteArrayList<Llamada>()

    override fun reconocer(documento: DocumentoParaEnviar): ResultadoReconocimiento {
        llamadas += Llamada(TransactionSynchronizationManager.isActualTransactionActive(), documento.mediaType)
        esperar?.await(30, TimeUnit.SECONDS)
        return guion(documento)
    }

    fun reiniciar() {
        activo = true
        guion = { reconocida(propuesta()) }
        esperar = null
        llamadas.clear()
    }

    companion object {
        const val MODELO = "modelo-falso"

        fun reconocida(p: PropuestaReconocida) = ResultadoReconocimiento.Reconocida(p, MODELO, 3000, 800)

        /**
         * A correct invoice from a supplier to the company, with two VAT rates:
         * 100.00 at 21% and 50.00 at 10% → 150.00 + 21.00 + 5.00 = 176.00.
         */
        fun propuesta(
            esFactura: Boolean = true,
            variasFacturas: Boolean = false,
            emisorNombre: String? = "Flores del Sur S.L.",
            emisorNif: String? = "A58818501",
            destinatarioNombre: String? = "Floristeria Granatum S.L.",
            destinatarioNif: String? = "B12345674",
            numero: String? = "2026-0815",
            fechaEmision: String? = "2026-10-02",
            concepto: String? = "Rosas rojas",
            moneda: String? = "EUR",
            lineas: List<LineaPropuesta> = listOf(
                LineaPropuesta("21.00", "100.00", "21.00", "0.00", null),
                LineaPropuesta("10.00", "50.00", "5.00", "0.00", null)
            ),
            retenciones: String? = "0.00",
            total: String? = "176.00",
            rectificativa: Boolean? = false,
            camposDudosos: List<String> = emptyList()
        ) = PropuestaReconocida(
            esFactura, variasFacturas, emisorNombre, emisorNif, destinatarioNombre, destinatarioNif,
            numero, fechaEmision, concepto, moneda, lineas, retenciones, total, rectificativa, camposDudosos
        )
    }
}

/** Registers the double in place of the real recogniser. Import it in a test with `@Import`. */
@TestConfiguration
class ConReconocedorFalso {
    @Bean
    @Primary
    fun reconocedorFalso(): ReconocedorFalso = ReconocedorFalso()
}
