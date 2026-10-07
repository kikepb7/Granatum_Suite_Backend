package com.granatum.core.api.dto

import com.granatum.core.domain.model.Periodo
import com.granatum.core.domain.model.Reporte
import com.granatum.core.domain.model.TotalesGrupo
import java.time.Instant
import java.time.LocalDate

data class PeriodoDto(val tipo: String, val anio: Int, val mes: Int?, val trimestre: Int?, val desde: LocalDate, val hasta: LocalDate)

data class TotalesDto(
    val facturas: Int,
    val base: String,
    val ivaPorTipo: Map<String, String>,
    val recargo: String,
    val retenciones: String,
    val total: String,
    val sinCuota: Map<String, String>
)

data class TrimestreCerradoDto(val anio: Int, val trimestre: Int, val desde: Instant)

data class ReporteDto(
    val periodo: PeriodoDto,
    val emitidas: TotalesDto,
    val recibidas: TotalesDto,
    val pendientes: Int,
    val trimestresCerrados: List<TrimestreCerradoDto>,
    val calculadoEn: Instant
)

private fun TotalesGrupo.aDto() = TotalesDto(
    facturas, base.toPlainString(),
    ivaPorTipo.entries.associate { (t, c) -> t.toPlainString() to c.toPlainString() },
    recargo.toPlainString(), retenciones.toPlainString(), total.toPlainString(),
    sinCuota.entries.associate { (c, b) -> c.name to b.toPlainString() }
)

fun Reporte.aDto() = ReporteDto(
    periodo = when (val p = periodo) {
        is Periodo.Mensual -> PeriodoDto("MENSUAL", p.anio, p.mes, null, p.desde, p.hasta)
        is Periodo.Trimestral -> PeriodoDto("TRIMESTRAL", p.anio, null, p.trimestre, p.desde, p.hasta)
        is Periodo.Anual -> PeriodoDto("ANUAL", p.anio, null, null, p.desde, p.hasta)
    },
    emitidas = emitidas.aDto(),
    recibidas = recibidas.aDto(),
    pendientes = pendientes,
    trimestresCerrados = trimestresCerrados.map { TrimestreCerradoDto(it.anio, it.trimestre, it.desde) },
    calculadoEn = calculadoEn
)
