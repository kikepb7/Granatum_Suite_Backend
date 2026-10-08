package com.granatum.core.api.errors

import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.webmvc.error.DefaultErrorAttributes
import org.springframework.stereotype.Component
import org.springframework.web.context.request.WebRequest

/**
 * What `/error` answers - an unknown route, a method not allowed, an exception
 * nobody handled - in the single `{code, message}` format of principle VIII,
 * with a fixed message per status and nothing else (feature 006, FR-009,
 * research.md D-005).
 *
 * Spring Boot's default body carries `timestamp`, `status`, `error` and `path`,
 * and, depending on `server.error.*`, the exception class, its message and the
 * stack trace. An exception message can carry anything the code had to hand -
 * a constraint violation quotes the offending value - so none of it leaves.
 *
 * Why not an `@ExceptionHandler(Exception::class)`: it would run before Spring's
 * own resolvers and turn into 500 the 404, 405 and 415 they answer correctly,
 * and swallow the security exceptions `EntryPointJson` has to see.
 */
@Component
class ErroresJson : DefaultErrorAttributes() {

    override fun getErrorAttributes(webRequest: WebRequest, options: ErrorAttributeOptions): Map<String, Any?> {
        val estado = (super.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults())["status"] as? Int) ?: 500
        val (code, message) = when (estado) {
            400 -> "VALIDACION" to "La petición no es válida"
            401 -> "NO_AUTENTICADO" to "Se requiere un token de acceso valido"
            403 -> "FORBIDDEN" to "No tiene permisos para realizar esta operacion"
            404 -> "RECURSO_NO_ENCONTRADO" to "No existe ese recurso"
            405 -> "METODO_NO_PERMITIDO" to "Ese método no está admitido en esta ruta"
            413 -> "PETICION_DEMASIADO_GRANDE" to "La petición es demasiado grande"
            415 -> "TIPO_NO_ADMITIDO" to "Ese tipo de contenido no está admitido"
            500 -> "ERROR_INTERNO" to "Error interno. Inténtalo de nuevo más tarde"
            else -> "ERROR_$estado" to "No se pudo completar la petición"
        }
        // `status` is kept for Spring's BasicErrorController, which reads it to
        // set the response code; it is the HTTP status, nothing internal.
        return linkedMapOf("code" to code, "message" to message, "status" to estado)
    }
}
