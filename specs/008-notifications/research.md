# Research: Notificaciones

Las decisiones de diseño (D-001 a D-005) están en [plan.md](./plan.md#decisiones):
no había incógnitas técnicas que investigar fuera del propio código. La única
comprobación previa fue el comportamiento de `@TransactionalEventListener`:

- Sin transacción activa en quien publica, el oyente **no** se ejecuta salvo con
  `fallbackExecution = true`. El registro de la 005 publica fuera de transacción
  (cifra la contraseña sin conexión retenida), así que hace falta.
- En `AFTER_COMMIT`, la transacción original ya está confirmada pero sus
  recursos siguen ligados al hilo: escribir exige `REQUIRES_NEW`.
- Una excepción en el oyente no deshace lo ya confirmado; el oyente la captura y
  la registra, para que tampoco llegue a quien hizo la petición.
