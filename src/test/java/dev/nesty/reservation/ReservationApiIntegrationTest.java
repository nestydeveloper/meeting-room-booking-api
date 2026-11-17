package dev.nesty.reservation;

import dev.nesty.reservation.reservation.ReservationRepository;
import dev.nesty.reservation.room.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test against a real PostgreSQL started by Testcontainers.
 *
 * <p>{@code @ServiceConnection} points the datasource at the container, so the same Flyway
 * migrations, JPA mapping and specification queries used in production run here. This is the only
 * place where the generated SQL and the schema constraints are exercised for real.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReservationApiIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.0");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private RoomRepository roomRepository;

    @BeforeEach
    void cleanDatabase() {
        reservationRepository.deleteAll();
        roomRepository.deleteAll();
    }

    @Test
    void flywayAppliedEveryMigration() {
        List<String> appliedVersions = jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success = true order by installed_rank",
                String.class);

        assertThat(appliedVersions).containsExactly("1", "2");
    }

    @Test
    void roomsAreCreatedListedByNameAndRenamed() throws Exception {
        createRoom("Zulu");
        long alfaId = createRoom("Alfa");

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Alfa"))
                .andExpect(jsonPath("$[1].name").value("Zulu"));

        mockMvc.perform(put("/api/v1/rooms/" + alfaId)
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alfa 2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alfaId))
                .andExpect(jsonPath("$.name").value("Alfa 2"));
    }

    @Test
    void roomValidationDuplicateAndMissingReturnClearErrors() throws Exception {
        createRoom("Sala A");

        mockMvc.perform(post("/api/v1/rooms")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        mockMvc.perform(post("/api/v1/rooms")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Room name already exists: Sala A"));

        mockMvc.perform(put("/api/v1/rooms/999999")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Otra\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void overlappingReservationIsRejectedButAdjacentOneIsAccepted() throws Exception {
        long roomId = createRoom("Sala A");

        createReservation(roomId, "2030-01-15T09:00:00", "2030-01-15T10:00:00");

        mockMvc.perform(post("/api/v1/reservations")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(roomId, "2030-01-15T09:30:00", "2030-01-15T10:30:00")))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail", containsString("Sala A")));

        // Half-open ranges: starting exactly when the previous one ends is allowed.
        mockMvc.perform(post("/api/v1/reservations")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(roomId, "2030-01-15T10:00:00", "2030-01-15T11:00:00")))
                .andExpect(status().isCreated());

        assertThat(reservationRepository.count()).isEqualTo(2);
    }

    @Test
    void invalidReservationReturnsClearErrorsAndStoresNothing() throws Exception {
        long roomId = createRoom("Sala A");

        mockMvc.perform(post("/api/v1/reservations")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(roomId, "2030-01-15T12:00:00", "2030-01-15T11:00:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("endTime must be after startTime"));

        mockMvc.perform(post("/api/v1/reservations")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItems(
                                "roomId", "responsible", "startTime", "endTime")));

        mockMvc.perform(post("/api/v1/reservations")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(999999, "2030-01-15T14:00:00", "2030-01-15T15:00:00")))
                .andExpect(status().isNotFound());

        assertThat(reservationRepository.count()).isZero();
    }

    @Test
    void reservationsAreFilteredByRoomAndDayWithPagination() throws Exception {
        long roomA = createRoom("Sala A");
        long roomB = createRoom("Sala B");

        createReservation(roomA, "2030-01-15T09:00:00", "2030-01-15T10:00:00");
        createReservation(roomA, "2030-01-15T11:00:00", "2030-01-15T12:00:00");
        createReservation(roomB, "2030-01-15T09:00:00", "2030-01-15T10:00:00");
        createReservation(roomA, "2030-01-16T09:00:00", "2030-01-16T10:00:00");

        mockMvc.perform(get("/api/v1/reservations")
                        .param("roomId", String.valueOf(roomA))
                        .param("date", "2030-01-15")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].roomName").value("Sala A"));

        mockMvc.perform(get("/api/v1/reservations").param("date", "2030-01-16"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void cancellingIsIdempotentAndFreesTheSlot() throws Exception {
        long roomId = createRoom("Sala A");
        long reservationId = createReservation(roomId, "2030-01-15T09:00:00", "2030-01-15T10:00:00");

        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel")
                        .with(httpBasic("admin", "admin")))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel")
                        .with(httpBasic("admin", "admin")))
                .andExpect(status().isNoContent());

        // Cancelled reservations are kept for audit but no longer listed.
        mockMvc.perform(get("/api/v1/reservations").param("date", "2030-01-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        assertThat(reservationRepository.count()).isEqualTo(1);

        // The slot is free again.
        createReservation(roomId, "2030-01-15T09:00:00", "2030-01-15T10:00:00");
    }

    @Test
    void cancellingAFinishedReservationReturnsConflict() throws Exception {
        long roomId = createRoom("Sala A");
        long reservationId = createReservation(roomId, "2030-01-15T09:00:00", "2030-01-15T10:00:00");

        // Move the reservation into the past: the API refuses to create one in the past, so the
        // fixture is written directly. 2020 is before "now" in any timezone.
        jdbcTemplate.update(
                "update reservations set start_time = '2020-01-01 09:00', end_time = '2020-01-01 10:00' where id = ?",
                reservationId);

        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel")
                        .with(httpBasic("admin", "admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("has already ended")));

        mockMvc.perform(post("/api/v1/reservations/999999/cancel")
                        .with(httpBasic("admin", "admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    void writeEndpointsRejectAnonymousRequests() throws Exception {
        long roomId = createRoom("Sala A");

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala B\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(roomId, "2030-01-15T09:00:00", "2030-01-15T10:00:00")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/reservations/1/cancel"))
                .andExpect(status().isUnauthorized());

        assertThat(reservationRepository.count()).isZero();
        assertThat(roomRepository.count()).isEqualTo(1);
    }

    private long createRoom(String name) throws Exception {
        String location = mockMvc.perform(post("/api/v1/rooms")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/v1/rooms/\\d+")))
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).isNotNull();
        return idFrom(location);
    }

    private long createReservation(long roomId, String start, String end) throws Exception {
        String location = mockMvc.perform(post("/api/v1/reservations")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(roomId, start, end)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/v1/reservations/\\d+")))
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).isNotNull();
        return idFrom(location);
    }

    private static long idFrom(String location) {
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private static String reservationBody(long roomId, String start, String end) {
        return """
                {"roomId":%d,"responsible":"ana@example.com","startTime":"%s","endTime":"%s"}
                """.formatted(roomId, start, end);
    }
}
