package com.granatum.core

import com.granatum.core.domain.model.TipoFactura
import com.granatum.core.domain.service.ClasificadorFactura
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Research D-014: issued or received, by the company's own tax id. */
class ClasificadorFacturaTest {

    private val empresa = "B12345674"

    @Test
    fun `issued when the company is the issuer, received when it is the recipient`() {
        assertEquals(TipoFactura.EMITIDA, ClasificadorFactura.clasificar(empresa, "B12345674", "A58818501"))
        assertEquals(TipoFactura.RECIBIDA, ClasificadorFactura.clasificar(empresa, "A58818501", "B12345674"))
    }

    @Test
    fun `tax ids compare normalised - VAT prefix, spaces, hyphens, case`() {
        assertEquals(TipoFactura.EMITIDA, ClasificadorFactura.clasificar(empresa, "ES b-1234 5674", "A58818501"))
        assertEquals(TipoFactura.RECIBIDA, ClasificadorFactura.clasificar(empresa, "A58818501", "esB12345674"))
    }

    @Test
    fun `neither, or nothing to compare with, is left undecided`() {
        assertNull(ClasificadorFactura.clasificar(empresa, "A58818501", "12345678Z"))
        assertNull(ClasificadorFactura.clasificar(empresa, null, null))
        assertNull(ClasificadorFactura.clasificar(null, "B12345674", "A58818501"))
    }
}
