package com.granatum.core

import com.granatum.core.domain.model.LineaPropuesta
import com.granatum.core.domain.model.PropuestaReconocida
import com.granatum.core.domain.port.DocumentoParaEnviar
import com.granatum.core.domain.port.ReconocedorFacturas
import com.granatum.core.domain.port.ResultadoReconocimiento
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * For the app tests that need an invoice recognised: a recogniser that always
 * proposes the same invoice, from a self-employed supplier with distinctive
 * values that a log search can look for. Never the real API.
 */
@TestConfiguration
class ConReconocedorDeFacturaFija {

    companion object {
        const val NOMBRE = "Proveedorasingular Lopez"
        const val NIF = "73918264J"
        const val CONCEPTO = "Conceptoinconfundible de prueba"
        const val TOTAL = "4321.87"

        /** 3571.79 + 21% = 750.08 → 4321.87. */
        val PROPUESTA = PropuestaReconocida(
            esFactura = true, variasFacturas = false,
            emisorNombre = NOMBRE, emisorNif = NIF,
            destinatarioNombre = "Floristeria Granatum S.L.", destinatarioNif = "B12345674",
            numero = "SING-${java.util.UUID.randomUUID().toString().take(8)}", fechaEmision = "2026-10-02", concepto = CONCEPTO, moneda = "EUR",
            lineas = listOf(LineaPropuesta("21.00", "3571.79", "750.08", "0.00", null)),
            retenciones = "0.00", total = TOTAL, rectificativa = false, camposDudosos = emptyList()
        )
    }

    @Bean
    @Primary
    fun reconocedorDeFacturaFija(): ReconocedorFacturas = object : ReconocedorFacturas {
        override val activo = true
        override fun reconocer(documento: DocumentoParaEnviar) =
            ResultadoReconocimiento.Reconocida(PROPUESTA, "modelo-fijo", 1, 1)
    }
}
