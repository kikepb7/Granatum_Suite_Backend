# Feature Specification: Inicio de sesión real (auth)

**Feature Branch**: `auth-feature`

**Created**: 2026-10-05

**Status**: Draft

**Input**: User description: "Login real para Granatum. Un empleado activo inicia sesión con su email y contraseña y recibe un access token de vida corta y un refresh token. Puede renovar la sesión con el refresh token y cerrar sesión (el refresh token queda invalidado). Tras 5 intentos fallidos seguidos, la cuenta se bloquea temporalmente. Un ADMIN crea empleados con una contraseña temporal que deben cambiar en el primer acceso, y puede restablecer contraseñas. Los empleados inactivos no pueden entrar. Fuera de alcance: registro público, verificación de email, recuperación por email, MFA."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Iniciar sesión y usar la aplicación (Priority: P1)

Una persona empleada abre la aplicación, introduce su correo y su contraseña, y
entra. A partir de ese momento puede fichar y consultar sus jornadas sin volver
a escribir la contraseña, hasta que su sesión caduca.

**Why this priority**: Es la razón de existir de la feature y el requisito que
bloquea la producción. Hoy la identidad se obtiene de un emisor de tokens de
desarrollo que entrega cualquier rol sin credencial, así que **ningún dato del
registro horario es atribuible de forma fiable a una persona**. Sin esto, el
registro de jornada no sostiene su propio valor probatorio.

**Independent Test**: Con unas credenciales válidas, iniciar sesión y usar el
token obtenido para una operación real de fichaje. Entrega valor por sí sola:
sustituye por completo al emisor de desarrollo.

**Acceptance Scenarios**:

1. **Given** una persona empleada activa con credenciales válidas, **When** inicia sesión con su correo y contraseña, **Then** recibe un token de acceso de vida corta y un token de renovación.
2. **Given** un token de acceso válido, **When** se usa en una operación de fichaje, **Then** la operación se atribuye a esa persona y a su rol.
3. **Given** una contraseña incorrecta, **When** se intenta iniciar sesión, **Then** se rechaza sin revelar si el correo existe.
4. **Given** un correo que no existe, **When** se intenta iniciar sesión, **Then** se rechaza con el **mismo** mensaje y el **mismo** tiempo de respuesta que una contraseña incorrecta.
5. **Given** una persona empleada marcada como inactiva, **When** intenta iniciar sesión con credenciales correctas, **Then** se rechaza.
6. **Given** un token de acceso caducado, **When** se usa en una operación, **Then** se rechaza indicando que la sesión expiró, de forma distinguible de unas credenciales inválidas.

---

### User Story 2 - Mantener y cerrar la sesión (Priority: P1)

La aplicación móvil renueva la sesión en segundo plano con su token de
renovación, sin pedir la contraseña otra vez. Cuando la persona cierra sesión,
ese token deja de servir de inmediato.

**Why this priority**: Un token de acceso de vida corta sin renovación obligaría
a escribir la contraseña cada pocos minutos, lo que en la práctica empuja a
alargar su vida — y eso es justo lo que se quiere evitar. El cierre de sesión es
la única forma de revocar el acceso de un dispositivo perdido.

**Independent Test**: Renovar con el token de renovación y comprobar que se
obtiene un nuevo par; cerrar sesión y comprobar que el mismo token de renovación
ya no sirve.

**Acceptance Scenarios**:

1. **Given** un token de renovación válido, **When** se renueva la sesión, **Then** se obtiene un nuevo token de acceso y un nuevo token de renovación.
2. **Given** un token de renovación ya usado, **When** se intenta renovar con él otra vez, **Then** se rechaza: cada token de renovación sirve **una sola vez**.
3. **Given** una sesión abierta, **When** la persona cierra sesión, **Then** su token de renovación queda invalidado y no sirve para renovar.
4. **Given** un token de renovación caducado, **When** se intenta renovar, **Then** se rechaza y hay que iniciar sesión de nuevo.
5. **Given** una persona empleada que se marca como inactiva mientras tiene sesión abierta, **When** intenta renovar, **Then** se rechaza.

---

### User Story 3 - Resistir un ataque de fuerza bruta (Priority: P2)

Alguien intenta adivinar la contraseña de una cuenta probando una tras otra.
Tras unos pocos intentos fallidos la cuenta queda bloqueada temporalmente, y los
intentos siguientes se rechazan aunque la contraseña sea correcta.

**Why this priority**: Sin esto, una contraseña débil es una contraseña
adivinable, y el acceso da entrada al registro horario de la persona. Va después
de P1 porque el inicio de sesión es utilizable antes de estar endurecido, pero
**no debe llegar a producción sin ello**.

**Independent Test**: Fallar el inicio de sesión cinco veces seguidas y
comprobar que el sexto intento se rechaza incluso con la contraseña correcta, y
que tras el periodo de bloqueo vuelve a funcionar.

**Acceptance Scenarios**:

1. **Given** una cuenta sin intentos fallidos, **When** se falla la contraseña 5 veces seguidas, **Then** la cuenta queda bloqueada temporalmente.
2. **Given** una cuenta bloqueada, **When** se intenta iniciar sesión con la contraseña **correcta**, **Then** se rechaza mientras dure el bloqueo.
3. **Given** una cuenta bloqueada, **When** pasa el periodo de bloqueo, **Then** se puede volver a iniciar sesión con la contraseña correcta.
3a. **Given** una cuenta que ya cumplió un bloqueo de 1 minuto, **When** vuelve a fallar 5 veces, **Then** el nuevo bloqueo dura 5 minutos; el siguiente 15 y los posteriores 60.
3b. **Given** una cuenta bloqueada, **When** se falla el inicio de sesión **durante** el bloqueo, **Then** el bloqueo **no** se prolonga: expira cuando le correspondía.
3c. **Given** una cuenta que arrastra nivel de bloqueo, **When** la persona inicia sesión correctamente, **Then** el nivel vuelve a cero y un bloqueo futuro empezará otra vez por 1 minuto.
4. **Given** una cuenta con 4 intentos fallidos, **When** se acierta la contraseña, **Then** se entra y el contador de fallos vuelve a cero.
5. **Given** una cuenta bloqueada, **When** se consulta el motivo del rechazo, **Then** no se revela si la contraseña probada era correcta.

---

### User Story 4 - Alta con contraseña temporal y primer acceso (Priority: P2)

Una persona con rol `ADMIN` da de alta a una incorporación y le entrega una
contraseña temporal. En su primer acceso, la persona tiene que cambiarla antes
de poder hacer nada más.

**Why this priority**: Es la única vía de entrada al sistema —no hay registro
público— así que sin ella no se puede incorporar a nadie. Y forzar el cambio es
lo que evita que una contraseña conocida por el administrador quede como
contraseña permanente de la persona.

**Independent Test**: Dar de alta unas credenciales con contraseña temporal,
iniciar sesión con ella, comprobar que toda operación distinta de cambiar la
contraseña se rechaza, cambiarla, y comprobar que entonces sí se puede operar.

**Acceptance Scenarios**:

1. **Given** una persona con rol `ADMIN` y una persona empleada ya registrada, **When** da acceso aportando su identificador y su correo, **Then** se crea la cuenta con una contraseña temporal marcada como pendiente de cambio, en **una sola operación**.
1a. **Given** un identificador de persona empleada que no corresponde a nadie, **When** se intenta dar acceso, **Then** se rechaza.
1b. **Given** una persona empleada que ya tiene cuenta, **When** se intenta darle acceso otra vez, **Then** se rechaza: la vía es restablecer.
2. **Given** unas credenciales pendientes de cambio, **When** la persona inicia sesión, **Then** entra, y la respuesta indica que debe cambiar la contraseña.
3. **Given** una sesión con credenciales pendientes de cambio, **When** se intenta cualquier operación que no sea cambiar la contraseña, **Then** se rechaza.
4. **Given** una sesión pendiente de cambio, **When** la persona cambia su contraseña aportando la actual, **Then** deja de estar pendiente y puede operar con normalidad.
5. **Given** una contraseña nueva que no cumple la política, **When** se intenta el cambio, **Then** se rechaza explicando qué requisito falla, sin revelar la contraseña en ningún mensaje.
6. **Given** una contraseña actual incorrecta, **When** se intenta el cambio, **Then** se rechaza.

---

### User Story 5 - Restablecer una contraseña olvidada (Priority: P3)

Alguien olvida su contraseña. Como no hay recuperación por correo, se lo dice a
su administrador, que le asigna una nueva contraseña temporal; en el siguiente
acceso tendrá que cambiarla.

**Why this priority**: Es el camino de vuelta cuando falla todo lo demás, y es
el mismo mecanismo que el alta, así que su coste es bajo. Va al final porque
mientras no haya muchas cuentas se puede resolver recreando las credenciales.

**Independent Test**: Un `ADMIN` restablece la contraseña de alguien, esa persona
entra con la temporal, se le exige cambiarla, y sus sesiones anteriores ya no
sirven.

**Acceptance Scenarios**:

1. **Given** una persona con rol `ADMIN`, **When** restablece la contraseña de una persona empleada, **Then** la cuenta recibe una contraseña temporal pendiente de cambio.
2. **Given** una cuenta con la contraseña restablecida, **When** se intenta renovar con un token de renovación anterior al restablecimiento, **Then** se rechaza: restablecer cierra todas las sesiones abiertas.
3. **Given** una cuenta bloqueada por intentos fallidos, **When** un `ADMIN` restablece su contraseña, **Then** el bloqueo se levanta.
4. **Given** una persona sin rol `ADMIN`, **When** intenta restablecer la contraseña de otra, **Then** se rechaza.

---

### Edge Cases

- **Correo que no existe frente a contraseña incorrecta**: el rechazo debe ser
  indistinguible en mensaje, código y tiempo de respuesta. Si no, el inicio de
  sesión se convierte en un oráculo que revela qué correos están registrados.
- **Varios dispositivos a la vez**: una persona puede tener sesión en el móvil y
  en el navegador. Cerrar sesión en uno no debe cerrar el otro, pero
  restablecer la contraseña sí cierra todos.
- **Token de renovación robado y reutilizado**: un token de renovación sirve una
  sola vez, así que si el legítimo ya lo usó, el robado falla — y ese fallo es
  una señal de que algo va mal.
- **Persona desactivada con sesión abierta**: su token de acceso sigue siendo
  criptográficamente válido hasta que caduca. El límite de ese hueco es la vida
  del token de acceso, y la renovación se rechaza inmediatamente.
- **Bloqueo durante el bloqueo**: fallar mientras la cuenta está bloqueada no
  debe prolongar el bloqueo indefinidamente; si lo hiciera, bastaría seguir
  intentando para dejar a alguien fuera para siempre.
- **Cambio de contraseña con la sesión pendiente de cambio**: es la única
  operación permitida en ese estado, y debe seguir funcionando aunque la
  contraseña actual sea la temporal.
- **Alta de credenciales para alguien que ya las tiene**: se rechaza; el camino
  es restablecer, no volver a crear.
- **Correo duplicado**: dos personas no pueden compartir correo, porque es la
  clave con la que se identifican al entrar.

## Requirements *(mandatory)*

### Functional Requirements

**Inicio de sesión**

- **FR-001**: El sistema MUST permitir a una persona empleada **activa** iniciar sesión aportando su correo y su contraseña, devolviendo un token de acceso y un token de renovación.
- **FR-002**: El sistema MUST rechazar el inicio de sesión de una persona empleada inactiva, aun con credenciales correctas.
- **FR-003**: El sistema MUST responder de forma **indistinguible** —mismo código, mismo mensaje y sin diferencia de tiempo medible— ante un correo inexistente y ante una contraseña incorrecta.
- **FR-004**: El sistema MUST almacenar las contraseñas con un hash adaptativo y salado, y MUST hacer imposible recuperar la contraseña original a partir de lo almacenado.
- **FR-005**: El sistema MUST incluir en el token de acceso la identidad de la persona y su rol, de modo que las features existentes lo consuman sin cambios.
- **FR-006**: El sistema MUST rechazar un token de acceso caducado de forma distinguible de unas credenciales inválidas, para que la aplicación sepa que debe renovar en lugar de pedir la contraseña.

**Sesión**

- **FR-007**: El sistema MUST permitir renovar la sesión aportando un token de renovación válido, devolviendo un nuevo par de tokens.
- **FR-008**: El sistema MUST invalidar un token de renovación en el momento en que se usa, de modo que **cada uno sirva una sola vez**.
- **FR-009**: El sistema MUST permitir cerrar sesión, invalidando el token de renovación de esa sesión y solo esa.
- **FR-010**: El sistema MUST rechazar la renovación si la persona empleada está inactiva en ese momento, con independencia de que el token de renovación siga vigente.
- **FR-011**: El sistema MUST almacenar los tokens de renovación de forma que su valor no sea recuperable desde la base de datos.
- **FR-012**: El sistema MUST permitir que una persona tenga varias sesiones simultáneas en distintos dispositivos, y que cerrar una no afecte a las demás.

**Protección frente a fuerza bruta**

- **FR-013**: El sistema MUST bloquear temporalmente una cuenta tras **5 intentos fallidos consecutivos**.
- **FR-014**: El sistema MUST rechazar el inicio de sesión de una cuenta bloqueada incluso con la contraseña correcta.
- **FR-015**: El sistema MUST poner a cero el contador de intentos fallidos tras un inicio de sesión correcto.
- **FR-016**: El sistema MUST levantar el bloqueo automáticamente al cumplirse su plazo, sin intervención manual.
- **FR-016a**: El sistema MUST aplicar un bloqueo **creciente**: el primero dura 1 minuto, el segundo consecutivo 5, el tercero 15 y el cuarto y siguientes 60. Castiga poco el error honesto y encarece rápido el ataque sostenido.
- **FR-016b**: El sistema MUST poner a cero el nivel de bloqueo tras un inicio de sesión correcto, de modo que quien se equivoca un día no arrastre penalización semanas después.
- **FR-016c**: Un intento fallido **durante** un bloqueo activo MUST NOT prolongarlo. Solo asciende de nivel un bloqueo nuevo tras otros 5 fallos consecutivos una vez expirado el anterior. Sin esta regla bastaría seguir intentando para dejar a alguien fuera indefinidamente sin conocer su contraseña, que es una denegación de servicio gratuita — y en este producto significa impedirle fichar.
- **FR-017**: El sistema MUST registrar los eventos de seguridad —inicio correcto, inicio fallido, bloqueo, cambio y restablecimiento de contraseña— **sin incluir la contraseña, el token ni ningún otro secreto**, y sin incluir datos personales más allá del identificador de la cuenta.

**Credenciales y contraseñas**

- **FR-018**: El sistema MUST permitir únicamente a `ADMIN` crear las credenciales de una persona empleada, con una contraseña temporal.
- **FR-019**: El sistema MUST marcar unas credenciales creadas con contraseña temporal como **pendientes de cambio**.
- **FR-020**: El sistema MUST impedir a una sesión con credenciales pendientes de cambio cualquier operación que no sea cambiar su propia contraseña.
- **FR-021**: El sistema MUST permitir a una persona cambiar su contraseña aportando la actual y la nueva, y MUST retirar entonces la marca de pendiente de cambio.
- **FR-022**: El sistema MUST rechazar un cambio de contraseña si la actual no es correcta.
- **FR-023**: El sistema MUST exigir que una contraseña tenga **al menos 8 caracteres** e incluya **al menos una mayúscula, una minúscula, un dígito y un símbolo**, y MUST explicar al rechazarla qué requisito incumple **sin reproducir la contraseña** en el mensaje ni en ningún registro.
- **FR-023a**: El sistema MUST aplicar la misma política a la contraseña temporal que genera o acepta un `ADMIN`, de modo que no exista una vía para introducir una contraseña más débil que la que se exige a la persona.
- **FR-023b**: El sistema MUST NOT imponer un máximo de longitud por debajo de 64 caracteres, para no impedir el uso de una frase larga o de un gestor de contraseñas a quien quiera algo más fuerte que el mínimo.
- **FR-024**: El sistema MUST permitir únicamente a `ADMIN` restablecer la contraseña de otra persona, asignando una temporal pendiente de cambio.
- **FR-025**: El sistema MUST invalidar **todas** las sesiones abiertas de una cuenta al restablecer su contraseña.
- **FR-026**: El sistema MUST levantar el bloqueo por intentos fallidos al restablecer la contraseña.
- **FR-027**: El sistema MUST impedir crear credenciales para una cuenta que ya las tiene; la vía para esa situación es restablecer.

**Identidad de la cuenta**

- **FR-028**: El sistema MUST identificar cada cuenta por un correo **único**, normalizado de forma que dos escrituras del mismo correo no produzcan dos cuentas.
- **FR-029**: El sistema MUST vincular cada cuenta al identificador de la persona empleada cuya jornada se registra, de modo que la identidad del token coincida con la de sus fichajes.
- **FR-029a**: El correo MUST vivir en la cuenta de acceso y no en los datos laborales de la persona. Es un dato de identidad para entrar, no de empleo, y mantenerlo aquí es lo que permite que esta feature no dependa de la de jornada (principio I).
- **FR-029b**: El sistema MUST ofrecer **una sola operación** para dar acceso a alguien, que recibe el identificador de la persona empleada y su correo y crea la cuenta con una contraseña temporal. Para quien la usa es "dar acceso", no la segunda mitad de un alta.
- **FR-029c**: El sistema MUST rechazar la creación de una cuenta cuyo identificador de persona empleada no corresponda a nadie registrado, y MUST detectar las cuentas huérfanas —referidas a una persona que no existe— porque al no haber clave ajena entre módulos la base de datos no puede impedirlas por sí sola.
- **FR-029d**: El sistema MUST impedir que dos cuentas apunten a la misma persona empleada.

**Retirada del emisor de desarrollo**

- **FR-030**: El sistema MUST conservar la imposibilidad de que el emisor de tokens de desarrollo exista con el perfil de producción, tal como ya está garantizado.
- **FR-031**: El sistema MUST hacer innecesario el emisor de desarrollo para ejercitar la aplicación, de modo que pueda retirarse sin perder capacidad de prueba.

### Key Entities *(include if feature involves data)*

- **CuentaAcceso**: las credenciales de una persona. Correo (único), contraseña almacenada de forma irreversible, si está pendiente de cambio, contador de intentos fallidos y hasta cuándo está bloqueada. Vinculada a la persona empleada cuya jornada se registra.
- **SesionRenovacion**: una sesión abierta en un dispositivo. Valor del token de renovación almacenado de forma irreversible, cuándo caduca, si ya se usó o se invalidó, y a qué cuenta pertenece. Una cuenta puede tener varias.
- **EventoSeguridad**: constancia de un intento de acceso o de un cambio de credenciales. Qué ocurrió, cuándo, y sobre qué cuenta. Nunca contiene contraseñas ni tokens.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Una persona empleada activa inicia sesión y, con el token obtenido, completa una jornada de fichaje sin usar el emisor de tokens de desarrollo.
- **SC-002**: Un correo inexistente y una contraseña incorrecta producen respuestas indistinguibles: mismo código, mismo cuerpo y una diferencia de tiempo que no permite distinguirlos de forma fiable.
- **SC-003**: Ninguna contraseña es recuperable desde lo almacenado: el 100% de los valores guardados son hashes salados, y dos personas con la misma contraseña tienen hashes distintos.
- **SC-004**: Un token de renovación usado dos veces se rechaza en el 100% de los casos.
- **SC-005**: Tras 5 intentos fallidos consecutivos, el 100% de los intentos posteriores se rechazan durante el bloqueo, incluso con la contraseña correcta.
- **SC-006**: Cerrar sesión invalida el token de renovación de esa sesión y **solo** el de esa sesión: las demás sesiones de la misma persona siguen funcionando.
- **SC-007**: Restablecer una contraseña invalida el 100% de las sesiones abiertas de esa cuenta.
- **SC-008**: Una sesión con credenciales pendientes de cambio no puede realizar ninguna operación salvo cambiar la contraseña: el 100% del resto se rechaza.
- **SC-009**: Ningún registro de seguridad contiene una contraseña ni un token, verificable sobre la salida completa de un ciclo de inicio de sesión, cambio y restablecimiento.
- **SC-010**: Una persona empleada inactiva no puede entrar ni renovar: el 100% de los intentos se rechazan.
- **SC-011**: Iniciar sesión tarda menos de 2 segundos, incluido el coste deliberado del hash de la contraseña.
- **SC-012**: El bloqueo crece según lo previsto: el primero dura 1 minuto y el cuarto consecutivo 60, y un fallo durante un bloqueo activo no altera su expiración.
- **SC-013**: Dar acceso a una persona empleada es **una sola operación**, y el 100% de los intentos de crear una cuenta para alguien que no existe, o una segunda cuenta para quien ya la tiene, se rechazan.
- **SC-014**: Ninguna contraseña que incumpla la política entra en el sistema, ni por cambio de la persona ni por restablecimiento de un `ADMIN`: el 100% se rechazan.

## Assumptions

Decisiones tomadas por defecto ante detalles no especificados:

- **Vida de los tokens: 15 minutos el de acceso y 30 días el de renovación.**
  **Confirmado por el responsable del producto el 2026-10-06**, con el criterio
  de igualarlos a Squadfy_Backend: 15 min de acceso, 30 días de renovación, y en
  el perfil `dev` un valor por defecto de 1000 min que la variable
  `JWT_EXPIRATION_MINUTES` puede sobreescribir. Mantiene el token de acceso lo
  bastante corto para que su robo tenga ventana pequeña. Son configuración, no
  diseño: se ajustan sin tocar código.
- **El correo se normaliza a minúsculas y sin espacios** antes de comprobar su
  unicidad, por el mismo motivo que el documento de identidad en la feature de
  jornada: sin normalizar, dos escrituras del mismo correo serían dos cuentas.
- **El bloqueo es creciente (1, 5, 15, 60 minutos) y no se prolonga con los
  fallos que ocurren durante él.** Decisión del responsable del producto. Lo
  segundo es lo que evita convertir la protección en una denegación de
  servicio: en este producto dejar a alguien bloqueado es impedirle fichar, y
  eso genera un hueco en un registro con valor legal.
- **La política de contraseña es 8 caracteres con mayúscula, minúscula, dígito y
  símbolo.** Decisión del responsable del producto, tomada conociendo la
  alternativa: la recomendación actual del NIST es exigir longitud (12+) y no
  composición, porque 8 caracteres con reglas tienen menos entropía que 12
  libres y las reglas tienden a producir la misma contraseña en todas las
  cuentas. Se recoge aquí para que la elección quede visible como deliberada y
  no como un descuido. Tiene una consecuencia técnica para el plan: con un
  espacio de contraseñas más pequeño, **el coste del hash adaptativo importa
  más**, así que sus parámetros deben elegirse con cuidado y no por defecto.
- **El correo vive en la cuenta de acceso, no en los datos laborales.** Decisión
  del responsable del producto. Es lo que permite que `auth` sea un módulo
  realmente independiente, apoyándose en que el sujeto del token ya **es** el
  identificador de la persona empleada. Contrapartida aceptada: sin clave ajena
  entre módulos, la base de datos no puede impedir una cuenta huérfana, y de ahí
  FR-029c.
- **El bloqueo es por cuenta, no por dirección de origen.** Limitar por origen es
  una protección distinta y complementaria, y pertenece a la feature de
  endurecimiento, no a esta.
- **Cerrar sesión afecta solo a la sesión que lo pide.** Un cierre global es una
  función útil pero distinta, y no estaba en el alcance.
- **No hay registro público ni recuperación por correo.** Del encargo: la única
  vía de entrada es que un `ADMIN` cree las credenciales, y la única vuelta de
  una contraseña olvidada es que un `ADMIN` la restablezca.
- **El token de acceso sigue siendo autocontenido y sin estado**, como ya lo es.
  Validarlo contra la base de datos en cada petición permitiría revocarlo al
  instante, pero convertiría cada llamada en una consulta y es un cambio de
  arquitectura que no pide el encargo.

## Dependencies

- **Identidad de la persona empleada**: esta feature necesita vincular una cuenta
  con la persona cuya jornada se registra. La feature de jornada ya decidió que
  **el sujeto del token es el identificador de la persona empleada**, lo que deja
  la vinculación servida sin inventar un segundo identificador.
- **Roles compartidos**: usa los cuatro roles ya definidos en el módulo común.
- **Retirada del emisor de desarrollo**: una vez exista el inicio de sesión real,
  el emisor de tokens de desarrollo deja de ser necesario para ejercitar la
  aplicación. Retirarlo es una decisión aparte y no está en este alcance.

## Out of Scope

- Registro público: nadie se da de alta por su cuenta. *(Revertido por la
  feature 005, a petición del responsable del producto: cada persona se
  registra, pero el registro no da acceso hasta que un `ADMIN` lo aprueba. Ver
  [`specs/005-staff-registration/`](../005-staff-registration/spec.md).)*
- Verificación de la dirección de correo.
- Recuperación de contraseña por correo electrónico.
- Doble factor de autenticación.
- Inicio de sesión con proveedores externos.
- Límite de intentos por dirección de origen, que pertenece a la feature de
  endurecimiento.
- Expulsión inmediata de un token de acceso ya emitido: su ventana es su vida.
- Permisos más finos que los cuatro roles existentes.
