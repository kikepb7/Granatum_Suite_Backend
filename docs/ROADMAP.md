# Hoja de ruta

Estado a 2026-10-08 y lo que queda por hacer, ordenado por versión. El modelo de
ramas está en [RAMAS.md](RAMAS.md).

## Estado actual

- **Funcionalidad completa** para un primer despliegue: inventario, registro de
  jornada (con exportación), acceso y registro del personal, facturación,
  ausencias y notificaciones. Ocho specs (`specs/001` a `specs/008`), todas sus
  tareas cerradas.
- **Calidad**: ~720 tests (unitarios, integración con Postgres real, punta a
  punta en `app`), CI en todas las ramas, imagen de contenedor verificada,
  contrato OpenAPI versionado (`docs/openapi.json`).
- **Constitución** 2.3.0, una sola excepción viva: la depuración a los 4 años,
  construida pero apagada a propósito.
- **Lo que impide ir a producción hoy**: el proyecto depende de **Spring Boot
  `4.0.0-SNAPSHOT`** y de los repositorios de snapshots y milestones de Spring.
  Una snapshot cambia sin aviso: el mismo commit puede compilar distinto de un
  día a otro. Ninguna versión con snapshots puede llegar a `main`.

## 1.0.0 — primer despliegue (`release/1.0.0`)

Solo estabilización y despliegue; ninguna feature nueva.

| # | Tarea | Tipo | Bloquea |
|---|---|---|---|
| R1 | Pasar de Spring Boot `4.0.0-SNAPSHOT` a la GA `4.0.8` y quitar los repositorios `repo.spring.io/snapshot` y `/milestone` de `settings.gradle.kts` y `build-logic`. Quedarse en la línea 4.0: springdoc 3.0.x se construye contra ella. Build completo en verde y contrato OpenAPI sin cambios (o regenerado y revisado). | código | sí |
| R2 | Versión `1.0.0` en `app/build.gradle.kts` y `CHANGELOG.md`. | código | sí |
| R3 | Elegir plataforma de despliegue (Railway, Fly.io, Render, VPS con Docker…) y escribir `docs/DESPLIEGUE.md`: variables, base de datos, primer `ADMIN`, comprobación de salud. | decisión + docs | sí |
| R4 | Publicar la imagen: trabajo de CI que, al etiquetar `v*`, sube la imagen a un registro (GHCR con `GITHUB_TOKEN` es lo natural). | código | sí, si el despliegue tira de un registro |
| R5 | Recalibrar Argon2 en la máquina de destino (`specs/002-auth/research.md`) y ajustar `AUTH_ARGON2_*` y `DB_POOL_MAX_SIZE`. | operación | sí |
| R6 | Variables de producción: `JWT_SECRET_BASE64`, `DB_*`, `AUTH_CODIGO_ARRANQUE` (y retirarlo tras crear el primer `ADMIN`), `SERVER_FORWARD_HEADERS_STRATEGY=native` si hay proxy, `CORS_ALLOWED_ORIGINS`. | operación | sí |
| R7 | Copias de seguridad de la base de datos y prueba de restauración. El registro de jornada tiene valor legal: perderlo es un incumplimiento. | operación | sí |
| R8 | Crear el primer `ADMIN` con el código de arranque y aprobar enseguida un segundo `ADMIN`. | operación | — |

**Antes de usar facturación con facturas reales** (no bloquea el despliegue;
sin clave la facturación funciona en modo manual):

| # | Tarea |
|---|---|
| F1 | Contrato de encargado del tratamiento con Anthropic. |
| F2 | Prueba real de reconocimiento (`claudeRealTest`) con `ANTHROPIC_API_KEY` y ~30 facturas de referencia: tiempo (SC-001) y precisión (SC-002). |
| F3 | Decidir dónde viven los originales: en Postgres ocupan espacio de base de datos en Supabase. |

**Decisiones del responsable del producto** (hay valores por defecto; confirmarlos):

- Activar o no la depuración a los 4 años (`timetracking.retencion.habilitada`).
- Límites de peticiones (10 inicios de sesión/min, 5 registros/h), 30 días
  naturales de vacaciones, 2 años de eventos de seguridad, 90/180 días de avisos.

## 1.1.0 — mantenimiento (`develop`)

| # | Tarea |
|---|---|
| M1 | Kotlin 2.2 → 2.4 (con el `allOpen` y el plugin JPA comprobados). |
| M2 | Testcontainers 1.20 → 2.0 (cambia de paquetes; afecta a todos los tests de integración). |
| M3 | mockk 1.13 → 1.14, jjwt 0.12.6 → 0.12.7. |
| M4 | Observabilidad: métricas de Actuator (Prometheus), alertas sobre errores `5xx` y sobre los trabajos programados (depuraciones, avisos). |
| M5 | Borrar las ramas `*-feature` antiguas, ya integradas. |
| M6 | Valorar Spring Boot 4.1 cuando springdoc 3.1.x esté probado con el resto. |

## Siguientes features (cada una, su spec en `feature/<nnn-nombre>`)

Por valor estimado para una floristería con personal por turnos:

1. **Cuadrantes y festivos**: turnos previstos y calendario laboral. Desbloquea
   el aviso de "no has fichado la entrada", las ausencias en días laborables y
   la comparación de jornada real frente a prevista.
2. **Correo saliente**: recuperación de contraseña, verificación del registro y
   avisos por correo. Exige proveedor SMTP (secreto por variable de entorno).
3. **Notificaciones push** para la app móvil.
4. **Doble factor** (TOTP), al menos para `ADMIN`.
5. **Horas extra** sobre la jornada registrada y los cuadrantes.
6. **Fiscal**: emisión de facturas y VeriFactu. Grande y con requisitos legales;
   necesita su propio análisis antes de especificarse.

## Cómo se trabaja cada punto

- Código de 1.0.0 → en `release/1.0.0`, PR contra `main`; al fusionar, etiqueta
  `v1.0.0` y merge de `main` en `develop`.
- Mantenimiento y features → `feature/*` desde `develop`.
- Fallos en producción → `hotfix/X.Y.Z` desde `main`.
