# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/);
versiones según [SemVer](https://semver.org/lang/es/). Modelo de ramas en
[docs/RAMAS.md](docs/RAMAS.md).

## [1.0.0] — en preparación (`release/1.0.0`)

Primera versión para producción. Pendiente antes de fusionar en `main`: elegir
plataforma y las tareas de operación de [docs/ROADMAP.md](docs/ROADMAP.md)
(apartado 1.0.0); el despliegue se describe en [docs/DESPLIEGUE.md](docs/DESPLIEGUE.md).

### Añadido

- **Inventario** de material: categorías, materiales con fotos e historial de
  cambios inmutable.
- **Registro de jornada** conforme al RD-ley 8/2019 (spec 001): entrada, pausas
  y salida, modo sin conexión idempotente, correcciones con aprobación,
  fichajes incompletos, resumen mensual y depuración a los 4 años (apagada por
  defecto).
- **Exportación del registro** (spec 003): CSV por persona y de toda la
  plantilla, con huella SHA-256 y registro de quién exportó qué.
- **Acceso** (spec 002): inicio de sesión con correo y contraseña (Argon2),
  renovación de un solo uso, cierre de sesión, bloqueo creciente, contraseñas
  temporales y restablecimiento por un `ADMIN`.
- **Registro del personal** (spec 005): cada persona se registra y un `ADMIN`
  la aprueba con un código de verificación; primer `ADMIN` con código de
  arranque.
- **Facturación** (spec 004): subida de fotos y PDF, lectura con Claude o modo
  manual, revisión, reportes mensuales, trimestrales y anuales en CSV y PDF,
  cierre de trimestres.
- **Ausencias y vacaciones** (spec 007): solicitudes, aprobación, bajas sin
  datos de salud, sin solapamientos, saldo anual.
- **Notificaciones** dentro de la aplicación (spec 008).
- **Contrato OpenAPI** generado (springdoc) y versionado en `docs/openapi.json`.
- **Despliegue** (spec 006): imagen de contenedor sin privilegios con
  comprobación de salud; CI en todas las ramas.

### Plataforma

- Spring Boot **4.0.8** (GA) en lugar de `4.0.0-SNAPSHOT`, sin repositorios de
  snapshots. Hibernate 7.2, Jackson 2.21, Testcontainers 1.21.4.
- La versión vive en un solo sitio (`build.gradle.kts`) y `/actuator/info` la
  muestra.
- Imagen publicada en GitHub Container Registry al etiquetar `vX.Y.Z`
  (`.github/workflows/publicar-imagen.yml`), tras comprobar que la etiqueta
  coincide con la versión.
- Modelo de ramas Git Flow ([docs/RAMAS.md](docs/RAMAS.md)); la CI rechaza PR
  contra `main` que no vengan de `release/*` o `hotfix/*`.

### Seguridad

- Límites de peticiones por dirección de origen, cabeceras de seguridad, CORS
  explícito, errores sin trazas, perfil `prod` por defecto (spec 006).
- Row Level Security en todas las tablas, incluida la de Flyway.
- Sin datos personales en los logs, comprobado en cada módulo con el log en
  `DEBUG`; con Hibernate 7.2 se fija también `org.hibernate.orm.core`.
