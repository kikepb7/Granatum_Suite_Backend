# Feature Specification: Endurecimiento y despliegue

**Feature Branch**: `backlog-feature`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Endurecimiento completo y despliegue. Límite de peticiones por dirección de origen en el inicio de sesión y en las rutas públicas (renovación, cierre de sesión, registro), y un límite general más holgado en toda la API; protección frente a la enumeración de correos; cabeceras de seguridad en todas las respuestas; CORS explícito y configurable por entorno; que un despliegue que olvide el perfil no quede en modo desarrollo; que los errores nunca lleven trazas; imagen de contenedor de la aplicación, sin privilegios y con comprobación de salud; y la integración continua también en ramas de feature, construyendo la imagen. Sin infraestructura nueva (ni Redis ni mensajería): un solo proceso."

## Contexto

Las features 002 y 005 aplazaron aquí lo que no era suyo: **limitar el ritmo por
dirección de origen**. Hoy lo único que acota el inicio de sesión es el semáforo
que reserva memoria para Argon2, y el registro solo tiene un tope de solicitudes
pendientes. Con eso, un atacante puede probar contraseñas a la velocidad que
permita el hash, y probar códigos de arranque sin freno.

Al preparar esta feature apareció un riesgo mayor: **el perfil por defecto de la
aplicación es `dev`**. Un despliegue que olvide fijar el perfil arranca en modo
desarrollo, con la ruta que emite tokens de cualquier rol sin credencial. Es una
suplantación total de `ADMIN` a un olvido de distancia.

Y falta todo lo necesario para desplegar: no hay imagen de la aplicación, y la
integración continua solo corre en `main`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Un atacante no puede probar contraseñas ni códigos a ritmo de máquina (Priority: P1)

Desde una misma dirección, las rutas públicas que reciben credenciales o
códigos —inicio de sesión, renovación, cierre de sesión y registro— admiten un
número limitado de peticiones por minuto. Superado, la respuesta dice que se
espere y cuánto. Las demás direcciones no se ven afectadas.

**Why this priority**: Es la protección que la 002 y la 005 dejaron
explícitamente para esta feature. Sin ella, el bloqueo por cuenta frena a quien
ataca una cuenta, pero no a quien prueba una contraseña común contra muchas, ni a
quien intenta adivinar el código de arranque.

**Independent Test**: Superar el límite de inicio de sesión desde una dirección
y comprobar que la siguiente petición recibe la respuesta de "demasiadas
peticiones" con el tiempo de espera, mientras otra dirección sigue entrando.

**Acceptance Scenarios**:

1. **Given** una dirección que ha agotado su cupo de inicios de sesión, **When** intenta otro, **Then** se rechaza indicando cuántos segundos esperar, sin llegar a comprobar la contraseña.
2. **Given** esa misma situación, **When** otra dirección inicia sesión, **Then** entra con normalidad.
3. **Given** una dirección limitada, **When** pasa el tiempo indicado, **Then** vuelve a poder intentarlo.
4. **Given** una dirección que agota el cupo de registro, **When** intenta registrarse otra vez, **Then** se rechaza igual, también con el código de arranque.
5. **Given** cualquier petición limitada, **When** se revisan los registros de la aplicación, **Then** no aparece la dirección de origen.

---

### User Story 2 - Desplegar mal no deja la puerta abierta (Priority: P1)

Un despliegue que no fija el perfil arranca como producción, no como
desarrollo. Ninguna respuesta de error contiene trazas internas. Un código de
arranque demasiado corto impide arrancar.

**Why this priority**: La ruta de desarrollo emite tokens de `ADMIN` sin
credencial. Que su ausencia dependa de que alguien recuerde una variable es el
tipo de error de una línea que el principio VI quiere imposible.

**Independent Test**: Arrancar la aplicación sin perfil y comprobar que la ruta
de desarrollo no existe.

**Acceptance Scenarios**:

1. **Given** una aplicación arrancada sin perfil explícito, **When** se llama a la ruta de desarrollo, **Then** no existe.
2. **Given** el arranque local de desarrollo, **When** se arranca sin indicar perfil, **Then** sigue arrancando en desarrollo, como hasta ahora.
3. **Given** un error inesperado en cualquier ruta, **When** se devuelve la respuesta, **Then** no contiene traza ni nombres de clases internas.
4. **Given** un código de arranque configurado con menos de 24 caracteres, **When** la aplicación arranca, **Then** se niega a arrancar diciendo qué variable corregir.

---

### User Story 3 - Las respuestas se defienden solas en el navegador (Priority: P2)

Todas las respuestas llevan cabeceras de seguridad: no se pueden incrustar en
otra página, no se interpretan con otro tipo de contenido, no se cachean si
tienen datos, y no permiten cargar recursos activos. Las peticiones desde
navegador solo se aceptan de los orígenes configurados.

**Why this priority**: La API se consumirá desde una aplicación web y una móvil.
Sin CORS explícito, o se abre a todos los orígenes o la aplicación web no
funciona; sin cabeceras, un fichero descargado podría interpretarse como página.

**Independent Test**: Hacer una petición de comprobación previa desde un origen
permitido y otro no permitido, e inspeccionar las cabeceras de una respuesta.

**Acceptance Scenarios**:

1. **Given** cualquier respuesta de la API, **When** se inspeccionan sus cabeceras, **Then** incluyen las de seguridad acordadas.
2. **Given** un origen configurado como permitido, **When** el navegador hace la comprobación previa, **Then** se autoriza con los métodos y cabeceras que la API usa.
3. **Given** un origen no configurado, **When** hace la comprobación previa, **Then** se rechaza.
4. **Given** ningún origen configurado, **When** llega cualquier comprobación previa, **Then** se rechaza: por defecto no se abre a nadie.

---

### User Story 4 - Una imagen lista para desplegar y una integración que vigila todas las ramas (Priority: P2)

La aplicación se empaqueta en una imagen de contenedor que arranca como
producción, sin privilegios de administrador del sistema, y que informa de su
salud. La integración continua se ejecuta en cada rama y en cada propuesta de
cambio, y construye la imagen.

**Why this priority**: Sin imagen no hay despliegue reproducible; sin
integración en las ramas, un fallo solo se ve al llegar a `main`, que es justo lo
que la constitución quiere evitar ("nada se fusiona con la CI en rojo").

**Independent Test**: Construir la imagen, arrancarla contra una base de datos y
comprobar su salud y el usuario con el que corre.

**Acceptance Scenarios**:

1. **Given** el repositorio, **When** se construye la imagen, **Then** se obtiene una imagen que arranca la aplicación como producción.
2. **Given** la imagen en marcha, **When** se consulta quién ejecuta el proceso, **Then** no es el administrador del sistema.
3. **Given** la imagen en marcha, **When** se consulta su salud, **Then** responde "en marcha".
4. **Given** un cambio en cualquier rama, **When** se publica, **Then** la integración continua compila, prueba y construye la imagen.
5. **Given** la imagen, **When** se inspecciona su contenido, **Then** no contiene el fichero de secretos local ni el código fuente.

---

### User Story 5 - Un límite general frente al abuso (Priority: P3)

Toda la API admite un número holgado de peticiones por minuto y dirección, para
que un cliente desbocado o un raspado masivo no degrade el servicio a los demás.

**Why this priority**: Es una red de seguridad, no la defensa principal. Un uso
legítimo nunca debería alcanzarlo.

**Independent Test**: Superar el límite general con peticiones autenticadas
desde una dirección.

**Acceptance Scenarios**:

1. **Given** una dirección que supera el límite general, **When** hace otra petición a cualquier ruta, **Then** recibe la respuesta de "demasiadas peticiones".
2. **Given** la comprobación de salud, **When** se consulta repetidamente, **Then** nunca se limita: la usa la plataforma de despliegue.

### Edge Cases

- **Detrás de un proxy o balanceador**: todas las peticiones llegan desde la
  dirección del proxy. La dirección real del cliente solo se toma de las
  cabeceras de reenvío si el despliegue lo indica expresamente; si se confiara
  siempre, cualquiera podría inventarse una dirección por petición y saltarse
  el límite.
- **Muchas direcciones distintas** (ataque distribuido o falsificación):
  la memoria del limitador está acotada; al llenarse se olvidan las direcciones
  más antiguas, nunca crece sin límite.
- **Varias instancias**: cada una limita por su cuenta. Se documenta; el límite
  efectivo es el configurado por el número de instancias.
- **Reinicio**: los contadores empiezan de cero. Aceptable: el límite frena el
  ritmo, no es un registro.
- **Descargas largas** (exportación, facturas): cuentan como una petición.

## Requirements *(mandatory)*

### Functional Requirements

**Límite por origen**

- **FR-001**: El sistema MUST limitar por dirección de origen las peticiones al inicio de sesión (10 por minuto por defecto), a la renovación y al cierre de sesión (30 por minuto), y al registro (5 por hora), todos configurables.
- **FR-002**: El sistema MUST limitar por dirección de origen el total de peticiones a la API (300 por minuto por defecto, configurable), salvo la comprobación de salud.
- **FR-003**: Una petición limitada MUST rechazarse antes de cualquier trabajo costoso (comprobación de contraseña, escritura), con un código estable y el tiempo de espera en segundos.
- **FR-004**: El límite de una dirección MUST NOT afectar a otra.
- **FR-005**: La dirección real del cliente MUST tomarse de las cabeceras de reenvío solo si el despliegue lo configura; por defecto, se usa la dirección de la conexión.
- **FR-006**: El sistema MUST NOT registrar en los logs ni guardar en la base de datos las direcciones de origen; el limitador las mantiene solo en memoria, con un número máximo de entradas (10 000 por defecto).

**Despliegue seguro por defecto**

- **FR-007**: Sin perfil explícito, la aplicación MUST arrancar con el perfil de producción.
- **FR-008**: El arranque local de desarrollo MUST seguir usando el perfil de desarrollo sin configuración adicional.
- **FR-009**: Ninguna respuesta de error MUST incluir trazas, mensajes de excepción internos ni nombres de clases.
- **FR-010**: Un código de arranque configurado con menos de 24 caracteres MUST impedir el arranque, con un mensaje que nombre la variable.

**Cabeceras y CORS**

- **FR-011**: Toda respuesta MUST llevar: prohibición de incrustarse en marcos, prohibición de reinterpretar el tipo de contenido, política de contenido que no permite cargar nada, política de referente que no envía nada, y política de permisos que no concede ninguno. Con conexión segura, MUST además exigir conexión segura en adelante.
- **FR-012**: Los orígenes de navegador admitidos MUST configurarse por entorno; sin configuración, ningún origen MUST ser admitido.
- **FR-013**: Para un origen admitido, la API MUST permitir los métodos que usa y las cabeceras de autorización y de tipo de contenido, y MUST exponer las cabeceras que los clientes necesitan leer (nombre del fichero descargado, tiempo de espera).

**Imagen e integración continua**

- **FR-014**: El repositorio MUST incluir la definición de una imagen de contenedor que arranque la aplicación con el perfil de producción, con un usuario sin privilegios y una comprobación de salud.
- **FR-015**: La imagen MUST NOT contener el fichero de secretos local, el código fuente ni los resultados de compilación intermedios.
- **FR-016**: El entorno local MUST poder arrancar la aplicación en contenedor junto a la base de datos, sin cambiar el arranque actual de solo base de datos.
- **FR-017**: La integración continua MUST ejecutarse en todas las ramas y propuestas de cambio, y MUST construir la imagen.

### Key Entities

Ninguna persistente. El limitador guarda, solo en memoria y acotado, un contador
por dirección y ruta.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Desde una dirección, como máximo 10 intentos de inicio de sesión por minuto llegan a comprobar una contraseña.
- **SC-002**: Una segunda dirección no nota ninguna diferencia mientras la primera está limitada (0 rechazos).
- **SC-003**: Una aplicación arrancada sin perfil no expone la ruta de desarrollo (100% de los intentos responden que no existe).
- **SC-004**: El 100% de las respuestas de error revisadas carece de trazas.
- **SC-005**: El 100% de las respuestas lleva las cabeceras de seguridad.
- **SC-006**: La imagen arranca y responde a la comprobación de salud en menos de 60 segundos, con un usuario distinto del administrador del sistema.
- **SC-007**: Una rama con un test en rojo muestra la integración continua en rojo antes de proponer el cambio.
- **SC-008**: Ninguna dirección de origen aparece en los logs con el nivel de detalle máximo.

## Assumptions

- Un solo proceso de aplicación (sin infraestructura compartida para contadores),
  por la regla de simplicidad deliberada de la constitución.
- Valores por defecto de los límites (10/min, 30/min, 5/h, 300/min) pensados para
  una plantilla de decenas de personas detrás de unas pocas direcciones (la red de
  la tienda): holgados para el uso real.
- La plataforma de despliegue termina TLS delante de la aplicación; la cabecera
  de conexión segura se emite cuando la petición llega como segura.

## Dependencies

- Feature 002: rutas de sesión y el semáforo de Argon2, que siguen igual.
- Feature 005: registro y código de arranque.

## Out of Scope

- Cortafuegos de aplicación, listas de bloqueo o reputación de direcciones.
- Contadores compartidos entre instancias.
- Detección de anomalías o alertas.
- Despliegue concreto en una plataforma (manifiestos de Kubernetes, etc.): la
  imagen es el punto de entrega.
