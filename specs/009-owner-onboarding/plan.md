# Implementation Plan: Alta del personal por el propietario

**Branch**: `feature/009-alta-por-propietario` | **Date**: 2026-10-09 | **Spec**: [spec.md](./spec.md)

## Summary

Se retira el registro con solicitud de la 005 y se deja solo el del propietario
(código de arranque). En su lugar, una ruta `ADMIN`, `POST /api/auth/altas`,
crea en una operación la ficha de personal (por el contrato `FichasPersonal`,
que ya existe) y la cuenta con contraseña provisional y cambio obligatorio (el
mecanismo de la 002). Dos migraciones nuevas borran lo que queda de la 005:
`solicitudes_registro` (V24, `auth`) y los avisos `REGISTRO_PENDIENTE` (V25,
`notifications`).

## Technical Context

Kotlin 2.2 / JVM 21, Spring Boot 4.0.8. Sin dependencias nuevas. Testcontainers
para integración; `app` para permisos y punta a punta.

## Constitution Check

| Principio | Cumplimiento |
|---|---|
| I | `auth` crea la ficha por `FichasPersonal` (contrato en `common`), como el arranque. |
| II | V24 y V25 nuevas; V20, V21 y V23 no se editan. |
| IV | `/api/auth/altas` solo `ADMIN`, en `SecurityConfig`. Desaparece `/api/auth/registros`. |
| V | Tests del alta, del registro sin código, de permisos y de las migraciones. |
| VI | La contraseña provisional solo en la respuesta; nada personal en los logs. |
| VII | Sin tablas nuevas; se elimina una. |
| VIII | Errores `{code, message}`; contrato OpenAPI regenerado. |

## Decisiones

- **D-001 Ruta nueva y no ampliar `POST /api/auth/cuentas`**: esa ruta da acceso
  a una ficha que ya existe y se mantiene. El alta completa es otra operación
  con otro cuerpo; mezclarlas haría opcionales la mitad de los campos.
- **D-002 Transacción**: la contraseña provisional se genera y se cifra fuera de
  la transacción (Argon2 ~110 ms, como en la 002); después, en una sola
  transacción: correo libre, ficha por documento o nueva, cuenta. Un fallo deja
  sin ficha y sin cuenta.
- **D-003 Registro sin código**: el código pasa a ser obligatorio en la
  petición; sin él, `400 VALIDACION`. Con él, igual que en la 005.
- **D-004 Retirada de datos**: `DROP TABLE solicitudes_registro` (sus filas
  pendientes contienen DNI, correo y hash; sin el flujo que las resolvía, no
  tienen finalidad) y `DELETE` de los avisos `REGISTRO_PENDIENTE` antes de
  estrechar el `CHECK` de `notificaciones`. Los tipos `REGISTRO_*` de
  `eventos_seguridad` se conservan: son historial ya escrito.
- **D-005 Recuperación del único `ADMIN`**: el procedimiento de la 005 sacaba el
  hash de una solicitud pendiente. Ahora se genera con la herramienta estándar
  `argon2`, cuyo formato codificado es el que lee el `Argon2PasswordEncoder` de
  la aplicación; `PasswordEncoderTest` lo comprueba con el vector de la
  implementación de referencia.
