# AGENTS.md

Guidance for AI coding agents working on this repository. Read this before changing code.

## Project overview

REST API for reserving meeting rooms. Rooms can be created, listed and renamed; reservations can
be created, listed (with filters and pagination) and cancelled. The overlap guarantee lives in the
database, not only in application code. Errors follow RFC 9457 (`application/problem+json`).

## Stack

Java 21 · Spring Boot 4.1.2 · Spring Data JPA · PostgreSQL 18 · Flyway · Spring Security (HTTP
Basic) · Caffeine · Testcontainers · JUnit 5 · Lombok.

## Build and test commands

```bash
# Database (required by the app and by the integration tests)
docker compose up -d

# Run the application
./mvnw spring-boot:run

# Run the whole test suite (requires Docker: Testcontainers boots PostgreSQL 18)
./mvnw test

# Run a single test class
./mvnw test -Dtest=ReservationConcurrencyTest

# Run the Postman collection against a running app
npx newman run postman/reservation-api.postman_collection.json --env-var baseUrl=http://localhost:8080
```

Every commit must keep `./mvnw test` green. The integration tests hit real PostgreSQL, so do not
skip them with a headless "it compiles".

## Architecture conventions (mandatory)

- **Package by feature**, never by layer: `room/`, `reservation/`, `security/`, `error/`,
  `common/`.
- **No logic in controllers.** A controller only validates (`@Valid`), delegates to a service and
  builds the `ResponseEntity`. No `if` of business logic, no `try/catch`, no date or overlap math.
- **Logic lives in services** (`@Transactional`), throwing domain exceptions, not HTTP exceptions.
- **Service interfaces + implementation.** `RoomService` / `RoomServiceImpl`,
  `ReservationService` / `ReservationServiceImpl`. Controllers depend on the interface.
- **Constructor injection via Lombok `@RequiredArgsConstructor`** with `final` fields. Never write
  an injection constructor by hand.
- **DTOs are plain records, kept clean** — no `from`/`to` static factories or other methods on
  them.
- **Entities use Lombok** (`@Getter`, `@Setter`, `@NoArgsConstructor`, `@Builder` where useful).
- **Checks and mappings go in private methods with intention-revealing names**, e.g.
  `ensureRoomIsFree`, `findRoomOrFail`, `newActiveReservation`, `toResponse`.
- **KISS and clean code.** No interface exists "just because"; add one only when there is a real
  second implementation. No speculative code or unused constants.

## Error handling

- Domain exceptions: `NotFoundException` (404), `ConflictException` (409),
  `BadRequestException` (400). All under `error/`.
- One `GlobalExceptionHandler extends ResponseEntityExceptionHandler` renders RFC 9457
  `ProblemDetail` bodies with content type `application/problem+json`.
- Bean validation failures add an `errors` array of `{field, message}` as a top-level extension.
- `401` comes from Spring Security (Basic entry point), not from the advice.
- Do not reintroduce a custom error body: `ProblemDetail` is the contract.

## Persistence and migrations

- Flyway owns the schema; `spring.jpa.hibernate.ddl-auto=validate`.
- **Never edit an applied migration.** Add a new `V<n>__description.sql` under
  `src/main/resources/db/migration`.
- `reservations.status` is `ACTIVE` or `CANCELLED` (partial exclusion constraint on `ACTIVE`).
- Timestamps are `LocalDateTime` (no timezone); overlap is half-open `[start, end)`.

## Concurrency

The exclusion constraint `excl_reservations_no_overlap` is the real guarantee. The service keeps a
pre-check only for a friendly 409. When the `saveAndFlush` fails, translate both:

- `DataIntegrityViolationException` with SQLSTATE `23P01` (exclusion violation), and
- `CannotAcquireLockException` with SQLSTATE `40P01` (deadlock on the exclusion index)

to `ConflictException` → 409. They are two exception types, not one. Room name races translate
`23505` the same way in `RoomServiceImpl`.

## Caching

Only the room catalogue is cached (`@Cacheable("rooms")` / `@CacheEvict(allEntries=true)` on
`create` and `update`). **Never cache reservation listings**: they are the availability view, and
a stale answer would show a taken slot as free.

## Testing conventions

- TDD per workflow: write the failing test first (RED), then implement (GREEN), then refactor.
- Tests travel with the feature, never in a separate later commit.
- Levels: Mockito unit tests, `@WebMvcTest` slices, Testcontainers integration, a concurrency test
  (10 threads → exactly one 201 and nine 409), a query-count test and a cache test.
- Spring Boot 4 renamed test packages: `@WebMvcTest` /
  `@AutoConfigureMockMvc` live under `org.springframework.boot.webmvc.test.autoconfigure`.
- Testcontainers 2.x `PostgreSQLContainer` is **not generic**: `new PostgreSQLContainer("postgres:18.0")`.
- `deleteAll()` does **not** reset `BIGSERIAL`; never assert a fixed id in integration tests.

## Git and commit workflow

- **Commits by workflow, not by layer.** One commit ships a working vertical slice with its tests.
- **Before every commit, present `git status --short`, `git diff --cached --stat` and the diff, and
  wait for explicit approval.**
- Stage explicit paths only. **Never** `git add -A`, `git add .` or `git commit -a`.
- Conventional Commit prefixes (`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `perf:`). Use `fix:`
  for a defect, `perf:` only for a pure optimization.

## Security

- HTTP Basic. Reads are public; `POST`/`PUT`/`cancel` require credentials.
- The demo account (`admin` / `admin`) is stored as a `{bcrypt}` hash in `application.yaml`, never
  as plaintext, and compared with `DelegatingPasswordEncoder`. Override with `APP_USER` /
  `APP_PASSWORD`.

## Environment gotchas

- Local Maven repository on this workstation is
  `F:/ERNESTO/JAVA_TOOLS/maven-sts/repository`, not `~/.m2`.
- The PostgreSQL container runs in UTC while the host may be in another timezone; comparing SQL
  `now()` against `LocalDateTime.now()` in Java gives wrong results in manual checks. Use old fixed
  timestamps (e.g. year 2020) to simulate "in the past".
- **Never** `taskkill /IM java.exe` (kills unrelated Java processes). Kill only your own PID.
- API paths are versioned and written literally (`/api/v1/rooms`), not via a constant.
