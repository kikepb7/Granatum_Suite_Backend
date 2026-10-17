package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.Almacen
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Embeddable
class LocalizacionEmbeddable(
    // VARCHAR(1) as in V26: left alone, Hibernate maps a one-character column
    // to CHAR(1), and `ddl-auto: validate` rejects the mismatch.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 1)
    var almacen: Almacen,

    // SMALLINT as in V26; an Int would otherwise be validated as INTEGER.
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(nullable = false)
    var seccion: Int
)
