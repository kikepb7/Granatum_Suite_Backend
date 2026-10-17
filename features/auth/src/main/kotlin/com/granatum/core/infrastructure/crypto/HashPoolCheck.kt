package com.granatum.core.infrastructure.crypto

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Refuses to start when the connection pool is too small for the configured
 * hash concurrency.
 *
 * ## Why this is a boot failure and not a comment
 *
 * `SessionService.rotate` holds a transaction while
 * `SecurityEventRecorder` opens a nested `REQUIRES_NEW` one, so a request
 * on that path uses **two** connections at once. With N requests in flight and
 * a pool smaller than 2N, every one of them holds a connection and waits for a
 * second that nobody will release: the pool deadlocks until the connection
 * timeout, and the symptom is an application that simply stops answering.
 *
 * It was found by a test hanging, and it would have been found in production by
 * an outage: the condition only appears under concurrency, so no amount of
 * manual checking would surface it. A comment in `application.yml` is not
 * enough either - the two values come from different environment variables
 * (`AUTH_HASH_CONCURRENCY` and `DB_POOL_MAX_SIZE`) and whoever raises one has
 * no reason to think about the other.
 *
 * Failing at boot turns an invisible load-dependent outage into an error message
 * with the fix in it, which is the whole value of the check.
 */
@Component
class HashPoolCheck(
    @param:Value("\${auth.hash.concurrency}") private val concurrency: Int,
    @param:Value("\${spring.datasource.hikari.maximum-pool-size:10}") private val poolSize: Int
) {

    @PostConstruct
    fun verify() {
        val minimum = concurrency * CONNECTIONS_PER_REQUEST
        require(poolSize >= minimum) {
            "auth.hash.concurrency=$concurrency necesita un pool de al menos $minimum " +
                "conexiones y hay $poolSize. Una peticion de autenticacion puede " +
                "mantener $CONNECTIONS_PER_REQUEST conexiones a la vez (la transaccion " +
                "principal y la anidada del registro de seguridad), asi que con este " +
                "pool $concurrency peticiones simultaneas lo agotan y se bloquean entre " +
                "si. Sube DB_POOL_MAX_SIZE a $minimum o baja AUTH_HASH_CONCURRENCY a " +
                "${poolSize / CONNECTIONS_PER_REQUEST}."
        }
    }

    companion object {
        /**
         * The main transaction plus the nested one the security event opens.
         * If that nesting is ever removed everywhere, this becomes 1 and the
         * check relaxes on its own.
         */
        const val CONNECTIONS_PER_REQUEST = 2
    }
}
