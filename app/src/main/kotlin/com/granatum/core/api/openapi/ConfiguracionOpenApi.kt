package com.granatum.core.api.openapi

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The OpenAPI document of the whole API (springdoc), for the clients - the
 * mobile and web apps generate their code from it.
 *
 * What springdoc cannot infer from the controllers is added here, in one place,
 * so the feature modules carry no documentation annotations:
 *
 * - **Authentication**: a bearer JWT on every operation, except the routes that
 *   are public in `SecurityConfig` (sign-in, renewal, sign-out, sign-up). The
 *   list below must follow that file; `ContratoOpenApiIT` would show the drift
 *   in the committed `docs/openapi.json`.
 * - **The error format**: principle VIII's `{code, message}`, as the `Error`
 *   schema, with the answers every operation can give (validation, rate limit,
 *   and 401/403 where a token is needed). The codes specific to each route are
 *   in each feature's `specs/<feature>/contracts/README.md`.
 * - **A relative server** (`/`), so the document is the same on every machine
 *   and environment, and the committed copy does not change with the port.
 */
@Configuration
class ConfiguracionOpenApi {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Granatum Suite API")
                .version("v1")
                .description(
                    "API de Granatum: inventario, registro de jornada, acceso y registro, facturación, " +
                        "ausencias y notificaciones. Autenticación con token Bearer (JWT) obtenido en " +
                        "POST /api/auth/login. Los errores siguen siempre el formato {code, message}; " +
                        "los códigos de cada ruta están en specs/<feature>/contracts/README.md."
                )
        )
        .servers(listOf(Server().url("/")))
        .components(
            Components()
                .addSecuritySchemes(
                    ESQUEMA_SEGURIDAD,
                    SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                )
        )
        .addSecurityItem(SecurityRequirement().addList(ESQUEMA_SEGURIDAD))

    /**
     * Runs after springdoc has built the document. The `Error` schema is added
     * here and not in [openApi]: springdoc replaces the bean's schemas with the
     * ones it generates. OpenAPI 3.1, so types are sets (`types`), not `type`.
     */
    @Bean
    fun personalizacionOpenApi(): OpenApiCustomizer = OpenApiCustomizer { api ->
        api.components.addSchemas(
            ESQUEMA_ERROR,
            Schema<Any>()
                .types(setOf("object"))
                .description("Formato único de error (principio VIII)")
                .addProperty("code", Schema<String>().types(setOf("string")).description("Identificador estable, para programas").example("VALIDACION"))
                .addProperty("message", Schema<String>().types(setOf("string")).description("Texto para personas; puede cambiar"))
                .required(listOf("code", "message"))
        )
        api.paths.orEmpty().forEach { (ruta, item) ->
            item.readOperationsMap().forEach { (metodo, operacion) ->
                val publica = (metodo to ruta) in RUTAS_PUBLICAS
                if (publica) operacion.security = emptyList()
                operacion.respuestaError("400", "Petición no válida (VALIDACION)")
                if (!publica) {
                    operacion.respuestaError("401", "Sin token, o token caducado (NO_AUTENTICADO, TOKEN_ACCESO_EXPIRADO)")
                    operacion.respuestaError("403", "El rol no permite esta operación (FORBIDDEN)")
                }
                operacion.respuestaError("429", "Demasiadas peticiones desde esta dirección (DEMASIADAS_PETICIONES); ver Retry-After")
            }
        }
    }

    private fun Operation.respuestaError(estado: String, descripcion: String) {
        if (responses?.containsKey(estado) == true) return
        responses.addApiResponse(
            estado,
            ApiResponse()
                .description(descripcion)
                .content(Content().addMediaType("application/json", MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/$ESQUEMA_ERROR"))))
        )
    }

    companion object {
        const val ESQUEMA_SEGURIDAD = "bearerAuth"
        const val ESQUEMA_ERROR = "Error"

        /** The routes `SecurityConfig` leaves public: no token in the document either. */
        private val RUTAS_PUBLICAS = setOf(
            PathItem.HttpMethod.POST to "/api/auth/login",
            PathItem.HttpMethod.POST to "/api/auth/refresh",
            PathItem.HttpMethod.POST to "/api/auth/logout",
            PathItem.HttpMethod.POST to "/api/auth/registro"
        )
    }
}
