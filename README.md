# Reservation API

API REST para reservar salas de reuniones: crear, listar y renombrar salas; crear, listar y
cancelar reservas. Incluye validación de datos, detección de solapamientos con garantía a nivel
de base de datos, filtros con paginación, autenticación en las escrituras y caché para el
catálogo de salas.

- **Stack**: Java 21 · Spring Boot 4.1.2 · Spring Data JPA · PostgreSQL 18 · Flyway · Spring Security (HTTP Basic) · Caffeine · Testcontainers.
- **Tests**: 63 automatizados (unitarios, de capa web e integración contra PostgreSQL real) + 24 requests en Postman.

## Cómo ejecutarlo

### Requisitos

- JDK 21
- Docker Desktop (para la base de datos y los tests de integración)

### Pasos

```bash
# 1. Base de datos (PostgreSQL 18 en localhost:5432)
docker compose up -d

# 2. Aplicación (Flyway aplica las migraciones al arrancar)
./mvnw spring-boot:run
```

La API queda en `http://localhost:8080`. Comprobación rápida:

```bash
curl -s localhost:8080/api/v1/rooms
```

### Configuración por variables de entorno

| Variable | Default | Uso |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/reservation` | URL JDBC |
| `DB_USER` | `postgres` | Usuario de base de datos |
| `DB_PASSWORD` | `postgres` | Password de base de datos |
| `APP_USER` | `admin` | Usuario de HTTP Basic |
| `APP_PASSWORD` | `{bcrypt}$2a$10$...` | Password de HTTP Basic (hash bcrypt) |

`docker compose` toma `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` y `POSTGRES_PORT`
con los mismos valores por defecto.

### Ejecutar los tests

```bash
./mvnw test
```

Requiere Docker: los tests de integración levantan un PostgreSQL real con Testcontainers
(imagen `postgres:18.0`) y le aplican las mismas migraciones de Flyway que producción.

### Probar la API con Postman

La colección está en `postman/reservation-api.postman_collection.json`: 24 requests con 51
aserciones, autenticación Basic a nivel de colección y variables que se guardan solas al crear
la sala y la reserva.

```bash
# Importarla en Postman, o correrla desde la terminal (API ya levantada)
npx newman run postman/reservation-api.postman_collection.json --env-var baseUrl=http://localhost:8080
```

## Autenticación

HTTP Basic. Las consultas son públicas; crear, renombrar y cancelar requieren credenciales.

| Endpoint | Auth |
| --- | --- |
| `GET /api/v1/rooms` | público |
| `POST /api/v1/rooms` | requiere Basic |
| `PUT /api/v1/rooms/{id}` | requiere Basic |
| `GET /api/v1/reservations` | público |
| `POST /api/v1/reservations` | requiere Basic |
| `POST /api/v1/reservations/{id}/cancel` | requiere Basic |

Credenciales por defecto: `admin` / `admin`. Sin credenciales, o con credenciales incorrectas,
la respuesta es `401` con el header `WWW-Authenticate: Basic`.

## Endpoints

### Salas

```bash
# Listar (público), ordenado por nombre
curl -s localhost:8080/api/v1/rooms

# Crear (autenticado) -> 201 Created + Location: /api/v1/rooms/{id}
curl -s -u admin:admin -X POST localhost:8080/api/v1/rooms \
  -H 'Content-Type: application/json' \
  -d '{"name":"Sala A"}'

# Renombrar (autenticado) -> 200
curl -s -u admin:admin -X PUT localhost:8080/api/v1/rooms/1 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Sala A - Planta 2"}'
```

### Reservas

```bash
# Crear (autenticado) -> 201 Created + Location: /api/v1/reservations/{id}
curl -s -u admin:admin -X POST localhost:8080/api/v1/reservations \
  -H 'Content-Type: application/json' \
  -d '{
        "roomId": 1,
        "responsible": "ana@example.com",
        "startTime": "2030-01-15T09:00:00",
        "endTime": "2030-01-15T10:00:00"
      }'

# Listar (público) con filtros y paginación
curl -s "localhost:8080/api/v1/reservations?roomId=1&date=2030-01-15&page=0&size=20"

# Cancelar (autenticado) -> 204 No Content
curl -s -u admin:admin -X POST localhost:8080/api/v1/reservations/1/cancel
```

Parámetros de `GET /api/v1/reservations`:

| Parámetro | Ejemplo | Semántica |
| --- | --- | --- |
| `roomId` | `1` | Solo reservas de esa sala (opcional) |
| `date` | `2030-01-15` | Reservas que **solapan** ese día completo (opcional) |
| `page` / `size` | `0` / `20` | Paginación (size por defecto 20) |
| `sort` | `startTime,desc` | Orden por propiedad (por defecto `startTime` asc) |

Respuesta paginada:

```json
{
  "content": [
    {
      "id": 10,
      "roomId": 1,
      "roomName": "Sala A",
      "responsible": "ana@example.com",
      "startTime": "2030-01-15T09:00:00",
      "endTime": "2030-01-15T10:00:00",
      "status": "ACTIVE"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

## Errores

Todas las respuestas de error siguen **RFC 9457** (`application/problem+json`):

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed for one or more fields",
  "instance": "/api/v1/rooms",
  "errors": [
    { "field": "name", "message": "name must not be blank" }
  ]
}
```

`errors` es una extensión del problem detail: aparece solo en errores de validación, con un
elemento por campo inválido.

| Código | Cuándo |
| --- | --- |
| `400` | Datos inválidos (Bean Validation, JSON ilegible, parámetro con formato inválido, `endTime` no posterior a `startTime`) |
| `401` | Falta autenticación o credenciales incorrectas en un endpoint protegido |
| `404` | Sala o reserva inexistente |
| `409` | La sala ya está ocupada en ese horario, el nombre de sala ya existe, o la reserva ya terminó |
| `500` | Error inesperado (se loguea el stacktrace, no se exponen detalles internos) |

## Reglas de negocio

1. **Solapamiento con rango semiabierto** `[start, end)`: una reserva que termina a las 10:00
   **no** choca con otra que empieza a las 10:00. Se expresa como
   `existing.start < new.end AND existing.end > new.start`.
2. **Cancelación lógica**: una reserva tiene estado `ACTIVE` o `CANCELLED`. Cancelar no borra la
   fila; la marca `CANCELLED` para conservar historial y auditoría.
3. **Las canceladas son invisibles** en el listado (que devuelve solo `ACTIVE`) y **liberan su
   horario**: el slot puede volver a reservarse.
4. **Cancelar es idempotente**: cancelar una reserva ya cancelada responde `204`, no un error.
5. **No se cancela lo ya terminado**: si `endTime <= now`, la respuesta es `409`.
6. **Fechas sin zona horaria** (`LocalDateTime`, ISO-8601): el horario se interpreta en la hora
   local del servidor.

## Concurrencia

Un chequeo "¿está ocupada?" seguido de un `INSERT` tiene una condición de carrera: dos requests
simultáneos pueden pasar ambos. La garantía real vive en la base de datos:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE reservations
    ADD CONSTRAINT excl_reservations_no_overlap
        EXCLUDE USING gist (
            room_id WITH =,
            tsrange(start_time, end_time, '[)') WITH &&
        ) WHERE (status = 'ACTIVE');
```

- La aplicación conserva el pre-check para dar un `409` amigable (con nombre de sala y horario).
- El constraint arbitra la carrera: el perdedor recibe una violación de exclusión (`23P01`) o un
  deadlock sobre el índice (`40P01`), y ambos se traducen a `409`, nunca a `500`.
- Un test lanza **10 requests simultáneos** al mismo slot: exactamente **1×201 y 9×409**.

El mismo patrón de "traducir la violación a conflicto" cubre el nombre de sala duplicado
(`23505`), que está respaldado por el `UNIQUE` de la V1.

## Decisiones

- **Solo PostgreSQL, sin H2.** Los tests usan Testcontainers con el mismo motor que producción,
  así que migraciones y SQL se validan de verdad. H2 habría sido más rápido pero con otro dialecto.
- **Flyway dueño del esquema** y `ddl-auto=validate`: si una entidad no coincide con la migración,
  el arranque falla en vez de "arreglar" el esquema en silencio.
- **HTTP Basic en lugar de JWT**: para el alcance pedido alcanza con "crear y cancelar requieren
  autenticación", con menos código y menos dependencias.
- **Contraseña como hash bcrypt**, nunca en texto plano, y comparación con el
  `DelegatingPasswordEncoder` de Spring Security.
- **`ProblemDetail` (RFC 9457)** en lugar de un body de error propio, con los errores de campo en
  la extensión `errors`.
- **`Specification` para filtros dinámicos** (sala y fecha son opcionales) y **`@EntityGraph`**
  para traer la sala en la misma query de la página: así el listado no dispara una consulta por
  cada sala distinta.
- **Caché solo en salas**: es catálogo de referencia que cambia poco. El listado de reservas se
  deja **sin cachear** a propósito: es la vista de disponibilidad, y un dato atrasado mostraría
  libre un slot que se acaba de tomar (o viceversa).
- **Sin `DELETE /rooms`** y **sin tabla de usuarios**: el enunciado no los pide; agregarlos
  sumaría alcance.

## Tests

| Nivel | Qué cubre |
| --- | --- |
| Unitarios (Mockito) | reglas de negocio y traducción de violaciones de base |
| MVC slice (`@WebMvcTest`) | contratos de los endpoints, validación y seguridad |
| Integración (Testcontainers) | flujo completo contra PostgreSQL real, migraciones, filtros, cancelación |
| Concurrencia | 10 requests simultáneos → 1×201 + 9×409 |
| Query count | el listado no dispara N+1 (2 statements exactos) |
| Caché | la lectura se sirve del cache y la escritura lo invalida |
| Postman / newman | contrato público end-to-end (24 requests, 51 aserciones) |

## Qué haría con más tiempo

- **JWT** con usuarios en base, roles y expiración.
- **Retry con backoff** para el `40P01` (deadlock), en lugar de devolver `409` directamente.
- **Normalizar el responsable**: hoy es un `string` en la reserva; con una tabla de responsables (o de usuarios) se podría validar que exista y evitar erratas.
- **Purga programada**: un job (`@Scheduled`) que borre físicamente las reservas `CANCELLED` pasado un período de retención (por ejemplo 90 días), para mantener la base limpia y que la cancelación lógica no haga crecer la tabla sin límite.
- **Skills y comandos propios** para que el asistente sea más fiel a mis pautas: skills del proyecto (por ejemplo "nuevo endpoint con TDD", "revisar antes de commitear") y comandos reutilizables, en lugar de repetir el prompt de convenciones en cada sesión.
- **Creación en lote**: `POST /api/v1/bookings/batch` con hasta N reservas (configurable, p. ej. 50) y **resultado por ítem** (`201`/`409`/`400`) en un `200` (no `207`, que es de WebDAV), donde un ítem inválido no tumba el resto. Hay que decidir y documentar si es parcial (recomendado) o todo‑o‑nada, e idempotente con `Idempotency-Key`. **Trampa**: en PostgreSQL una violación del constraint de exclusión aborta la transacción completa, así que el modo parcial exige una transacción o savepoint por ítem, lo que anula el beneficio del batching JDBC; o se elige parcial por ítem (más útil, sin inserts batcheados) o todo‑o‑nada (aprovecha el batching, un conflicto tumba el lote). Verificar el comportamiento real antes de afirmarlo.
- **Command objects por caso de uso**: que los servicios reciban un `CreateReservationCommand` / `RenameRoomCommand` en lugar del DTO web o de primitivos sueltos, para expresar intención y no atar el dominio al transporte HTTP (hoy eso también obliga a que `Pageable` viaje hasta el servicio). Devolver DTO **ya se hace**: `RoomResponse`, `ReservationResponse` y `PageResponse` son records y ninguna entidad sale del controller.
- **MapStruct para los mapeos entidad→DTO**, cuando las respuestas crezcan o se aniden y los mapeos privados empiecen a repetirse; con dos o tres records simples, los métodos privados actuales son más claros. Gotcha: MapStruct y Lombok requieren `lombok-mapstruct-binding` y ordenar los annotation processors para que vea los getters generados.
- **OpenAPI/Swagger** para documentar el contrato automáticamente.
- **Dockerfile** y `docker compose` que levanten API y base juntas.
- **CI** con GitHub Actions corriendo `./mvnw test` (los tests ya funcionan sin base externa).
- **`recordStats`** en Caffeine para métricas de hit/miss.
- **ETag** (`ShallowEtagHeaderFilter`) en las lecturas públicas.
- **Zonas horarias** (`Instant`/`OffsetDateTime`) si la API sirve a usuarios en distintos husos.

## Cómo usé la IA

Trabajé con un agente de código, pero **las decisiones de diseño fueron mías**: la IA me daba
opciones, ejecutaba y verificaba; yo fijaba el rumbo y revisaba cada cambio antes de aprobarlo.

**Decisiones que tomé frente a las propuestas del asistente:**

| Propuesta inicial | Mi decisión |
| --- | --- |
| JWT | **Basic HTTP** — tenía claras ambas opciones; por alcance y tiempo, Basic era lo suficiente |
| H2 + PostgreSQL | **Solo PostgreSQL**, con Testcontainers para los tests |
| Commits por capa | **Commits por flujo de trabajo** |
| Validación de fechas con constraint | **En el servicio** |
| Borrado físico de reservas | **Estados (`ACTIVE`/`CANCELLED`)** con cancelación lógica |
| Tests sobre H2 | **Tests de integración contra PostgreSQL real** |
| Fetch join en la Specification | **`@EntityGraph`** sobre `findAll` |
| Body de error propio | **`ProblemDetail`** (RFC 9457) |
| — | **Prefijo `/api/v1`**, para poder versionar los endpoints |
| — | **Caché solo en el catálogo de salas** |
| — | **Concurrencia y caché antes que la colección Postman** |

**Cómo trabajamos en conjunto**

- **Diferenciamos el modelo según la tarea**: en la fase inicial (analizar el enunciado, comparar opciones y decidir el diseño) usamos **deepseek-v4-pro**, más fuerte en razonamiento; para la implementación y los ciclos de TDD usamos **deepseek-v4.1-flash**, más rápido.
- La especificación inicial la entregué **acompañada de un prompt con mis pautas de programación** (clean code, KISS, sin lógica en los controllers, Lombok, records limpios, interfaces de servicio, verificaciones en métodos privados con nombres sugerentes). El asistente trabajó dentro de esas reglas, y cuando se desvió lo corregí.
- En cada flujo usé **TDD**: primero el test que fija la regla (rojo) y después la implementación
  (verde). Yo definía qué comportamiento se pineaba; la IA lo bajaba a código.
- Ante cada discrepancia técnica, la resolvíamos contra la **documentación oficial** con el MCP
  **context7** (Spring Boot, Spring Data JPA, Spring Framework), nunca por opinión ni "de memoria".
- Revisé el diff **antes de cada commit** y corregí el rumbo en varios puntos: tipo de commit
  (`fix:` en lugar de `perf:`), orden de las entregas, estructura de paquetes.

**La solución no salió a la primera, y eso es lo esperable.** Hubo partes que requirieron varias
iteraciones hasta quedar en verde contra la base real: el filtro por fecha escrito como JPQL con
parámetros opcionales fallaba en PostgreSQL y se reescribió con `Specification`; el
`PostgreSQLContainer` de Testcontainers cambió de API en la versión usada; una condición de
carrera se manifestaba como deadlock. El valor no estuvo en un "one-shot", sino en iterar y
verificar cada vez.

El modelo fue: **yo decidía el qué y el porqué; el asistente ejecutaba el cómo, y ambos
verificábamos contra la base real.**
