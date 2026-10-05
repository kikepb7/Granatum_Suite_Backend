package com.granatum.core.domain.exception

/**
 * The caller is authenticated but not allowed to do this.
 *
 * Takes an optional message so a feature can say *which* rule was broken -
 * "the fichaje does not belong to the authenticated user" is far more useful in
 * a log and in a test failure than a fixed sentence. The default keeps the
 * previous behaviour for callers that pass nothing.
 *
 * The message reaches clients and logs, so it must never name a person, a
 * document number or a location (principle VI): say which rule failed, not who
 * or what was involved.
 */
open class ForbiddenException(
    override val message: String = "You are not allowed to perform this action"
) : RuntimeException(message)
