package com.granatum.core.service

import com.granatum.core.domain.exception.VerificationFileTooLargeException
import com.granatum.core.infrastructure.database.entities.RegisterExportEntity
import com.granatum.core.infrastructure.database.repositories.RegisterExportRepository
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
 * than `verification-max-bytes` is refused as soon as it crosses the limit,
 * without reading the rest.
 */
@Service
class RegisterExportVerification(
    private val exports: RegisterExportRepository,
    @param:Value("\${timetracking.export.verification-max-bytes:104857600}")
    private val maxBytes: Long
) {
    data class VerificationResult(
        val fingerprint: String,
        /** Every export with these exact bytes, oldest first. Empty: not a file this system produced, or altered. */
        val matchingExports: List<RegisterExportEntity>
    )

    @Transactional(readOnly = true)
    fun verify(file: InputStream): VerificationResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = file.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw VerificationFileTooLargeException(maxBytes)
            digest.update(chunk, 0, n)
        }
        val fingerprint = HexFormat.of().formatHex(digest.digest())
        return VerificationResult(fingerprint, exports.findAllByFingerprintOrderByGeneratedAtAsc(fingerprint))
    }
}
