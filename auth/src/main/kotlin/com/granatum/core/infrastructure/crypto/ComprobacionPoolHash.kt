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
 * `SesionService.rotar` holds a transaction while
 * `RegistradorEventosSeguridad` opens a nested `REQUIRES_NEW` one, so a request
 * on that path uses **two** connections at once. With N requests in flight and
 * a pool smaller than 2N, every one of them holds a connection and waits for a
 * second that nobody will release: the pool deadlocks until the connection
 * timeout, and the symptom is an application that simply stops answering.
 *
 * It was found by a test hanging, and it would have been found in production by
 * an outage: the condition only appears under concurrency, so no amount of
 * manual checking would surface it. A comment in `application.yml` is not
 * enough either - the two values come from different environment variables
 * (`AUTH_HASH_CONCURRENCIA` and `DB_POOL_MAX_SIZE`) and whoever raises one has
 * no reason to think about the other.
 *
 * Failing at boot turns an invisible load-dependent outage into an error message
 * with the fix in it, which is the whole value of the check.
 */
@Component
class ComprobacionPoolHash(
    @param:Value("\${auth.hash.concurrencia}") private val concurrencia: Int,
    @param:Value("\${spring.datasource.hikari.maximum-pool-size:10}") private val tamanoPool: Int
) {

    @PostConstruct
    fun comprobar() {
        val minimo = concurrencia * CONEXIONES_POR_PETICION
        require(tamanoPool >= minimo) {
            "auth.hash.concurrencia=$concurrencia necesita un pool de al menos $minimo " +
                "conexiones y hay $tamanoPool. Una peticion de autenticacion puede " +
                "mantener $CONEXIONES_POR_PETICION conexiones a la vez (la transaccion " +
                "principal y la anidada del registro de seguridad), asi que con este " +
                "pool $concurrencia peticiones simultaneas lo agotan y se bloquean entre " +
                "si. Sube DB_POOL_MAX_SIZE a $minimo o baja AUTH_HASH_CONCURRENCIA a " +
                "${tamanoPool / CONEXIONES_POR_PETICION}."
        }
    }

    companion object {
        /**
         * The main transaction plus the nested one the security event opens.
         * If that nesting is ever removed everywhere, this becomes 1 and the
         * check relaxes on its own.
         */
        const val CONEXIONES_POR_PETICION = 2
    }
}
