# Implementation Plan: Endurecimiento y despliegue

**Branch**: `backlog-feature` | **Date**: 2026-10-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-hardening-deploy/spec.md`

## Summary

Un limitador de ritmo por dirección de origen, en memoria y acotado, delante de
la cadena de seguridad: cupos propios para inicio de sesión, renovación, cierre
de sesión y registro, y uno general para `/api/**`. El perfil por defecto pasa a
ser `prod` (el arranque local sigue en `dev`). Respuestas de error sin trazas y
con el formato único. Cabeceras de seguridad y CORS explícito en `SecurityConfig`.
Una imagen de contenedor multietapa, sin privilegios y con comprobación de
salud; la CI en todas las ramas y construyendo la imagen.

**Enfoque técnico**: todo lo que toca HTTP vive en `app`, junto a
`SecurityConfig`, porque es transversal y es el único módulo con la cadena de
seguridad. El código de arranque se comprueba en `auth`, que es su dueño.
**Ninguna dependencia nueva**: el limitador es una cubeta de fichas de unas
decenas de líneas, probada con un reloj inyectado (D-001).

## Technical Context

**Language/Version**: Kotlin 2.2 sobre JVM 21

**Primary Dependencies**: Spring Boot 4 (Web, Security). Sin dependencias nuevas.

**Storage**: ninguna tabla. Contadores en memoria.

**Testing**: JUnit 5 (cubeta con reloj inyectado), integración en `app` con la
cadena real, y una comprobación de la imagen en la CI.

**Target Platform**: contenedor Linux; la imagen es el entregable.

**Project Type**: servicio web.

**Performance Goals**: el limitador añade un acceso a un mapa por petición.

**Constraints**: sin infraestructura compartida (constitución, "simplicidad
deliberada"); sin direcciones de origen en logs ni en base de datos.

**Scale/Scope**: una instancia; hasta 10 000 direcciones recordadas por cupo.

## Constitution Check

| Principio | Cumplimiento |
|---|---|
| I. Features independientes | Nada nuevo cruza módulos. Limitador, cabeceras, CORS y errores en `app`; comprobación del código de arranque en `auth`. |
| II. Flyway | Sin migraciones. |
| III. Registro horario | No se toca. |
| IV. Roles | `SecurityConfig` sigue siendo el único mapa de autorización; las cabeceras y CORS se declaran ahí. Los cupos son configuración aparte (no son autorización). |
| V. Tests | Cubeta con reloj inyectado; integración del límite, del perfil por defecto, de las cabeceras, de CORS y de los errores; la imagen se comprueba en la CI. |
| VI. Secretos | **Se corrige un riesgo del propio principio**: el perfil por defecto era `dev`, así que olvidar la variable dejaba viva la ruta que emite tokens. Ahora el defecto es `prod`. El código de arranque corto impide arrancar. Las direcciones de origen no se registran. |
| VII. RLS | Sin tablas. |
| VIII. Contrato API | `429 DEMASIADAS_PETICIONES` con el formato único. **Desviación declarada**: lo escribe un filtro, no un `@RestControllerAdvice`, porque ocurre antes del `DispatcherServlet` —igual que `EntryPointJson` con los `401`/`403`—. Las respuestas de `/error` pasan al formato `{code, message}`. |
| IX. Documentación | Spec, plan, tareas; README (despliegue, variables) y ARCHITECTURE (limitador, perfil por defecto). |

**Resultado**: pasa, con la desviación de VIII declarada y justificada.

## Project Structure

```text
app/src/main/kotlin/com/granatum/core/api/security/
├── SecurityConfig.kt            # cabeceras, CORS, filtro del limitador
├── CuboFichas.kt                # cubeta de fichas pura (D-001)
├── LimitadorPorOrigen.kt        # cupos por ruta y dirección, LRU acotado
├── FiltroLimitePorOrigen.kt     # 429 antes de la cadena
└── ConfiguracionCors.kt
app/src/main/kotlin/com/granatum/core/api/errors/ErroresJson.kt   # /error con {code, message}
app/src/main/resources/application.yml                            # perfil prod por defecto, server.error.*, seguridad.*
features/auth/src/main/kotlin/com/granatum/core/infrastructure/crypto/ComprobacionCodigoArranque.kt
build-logic/src/main/kotlin/granatum.spring-boot-app.gradle.kts   # bootRun en dev
Dockerfile, .dockerignore, docker-compose.yml (servicio app, perfil "app")
.github/workflows/ci.yml                                          # todas las ramas + imagen
```

## Fases

1. **Fundación**: propiedades `seguridad.*`, cubeta y limitador con sus tests.
2. **US1 – Límite en rutas públicas** (P1).
3. **US2 – Seguro por defecto** (P1): perfil, errores, código de arranque.
4. **US3 – Cabeceras y CORS** (P2).
5. **US4 – Imagen y CI** (P2).
6. **US5 – Límite general** (P3).
7. **Pulido**: README, ARCHITECTURE, quickstart, build.

## Complexity Tracking

| Desviación | Por qué | Alternativa descartada |
|---|---|---|
| El `429` lo escribe un filtro (principio VIII) | Tiene que ocurrir antes de comprobar el token y antes del `DispatcherServlet`, donde no hay `@RestControllerAdvice`. | Limitar en cada controlador: llegaría tarde (el JWT ya se habría validado) y repartiría la regla por los módulos. |
