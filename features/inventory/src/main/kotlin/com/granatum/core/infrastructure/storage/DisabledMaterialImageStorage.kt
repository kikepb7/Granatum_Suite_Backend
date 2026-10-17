package com.granatum.core.infrastructure.storage

import com.granatum.core.domain.exception.ImageStorageUnavailableException
import com.granatum.core.domain.port.MaterialImageStorage

/**
 * No Storage configured (research.md D-010): uploads are rejected, there is
 * nothing to delete and nothing to sign. Removing an image or a material still
 * works; only the object, if one was ever stored, stays behind.
 */
class DisabledMaterialImageStorage : MaterialImageStorage {

    override val available: Boolean = false

    override fun store(path: String, content: ByteArray, mediaType: String) =
        throw ImageStorageUnavailableException()

    override fun delete(paths: Collection<String>) = Unit

    override fun sign(paths: Collection<String>): Map<String, String> = emptyMap()
}
