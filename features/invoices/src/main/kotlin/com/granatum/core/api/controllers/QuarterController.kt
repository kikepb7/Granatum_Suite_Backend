package com.granatum.core.api.controllers

import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.QuarterStatus
import com.granatum.core.service.Quarters
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class QuarterReopenRequest(@field:NotBlank @field:Size(min = 10, max = 500) val reason: String) {
    override fun toString(): String = "QuarterReopenRequest(***)"
}

/** Fiscal quarters (FR-030 to FR-033). `ADMIN` only, by the `/api/invoicing` rule. */
@RestController
@RequestMapping("/api/invoicing/quarters")
class QuarterController(private val quarters: Quarters) {

    @GetMapping
    fun status(@RequestParam year: Int): List<QuarterStatus> = quarters.status(year)

    @PostMapping("/{year}/{quarter}/close")
    fun close(@PathVariable year: Int, @PathVariable quarter: Int): QuarterStatus {
        quarters.close(year, quarter, requestUserId)
        return quarters.status(year).single { it.quarter == quarter }
    }

    /** A reason of 10 to 500 characters is required; without it, 400 VALIDATION (FR-032). */
    @PostMapping("/{year}/{quarter}/reopen")
    fun reopen(@PathVariable year: Int, @PathVariable quarter: Int, @Valid @RequestBody r: QuarterReopenRequest): QuarterStatus {
        quarters.reopen(year, quarter, r.reason, requestUserId)
        return quarters.status(year).single { it.quarter == quarter }
    }
}
