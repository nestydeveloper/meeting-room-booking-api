package dev.nesty.reservation.reservation;

import dev.nesty.reservation.room.Room;
import dev.nesty.reservation.room.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the database, not only the application check, prevents a double booking.
 *
 * <p>Ten requests hit the same room and slot at the same time over real HTTP: the pre-check cannot
 * save them, so the exclusion constraint arbitrates. Exactly one must win with 201 and the rest
 * must be rejected with 409, never 500.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 10;
    private static final String SLOT = """
            {"roomId":%d,"responsible":"ana@example.com",
             "startTime":"2030-08-20T09:00:00","endTime":"2030-08-20T10:00:00"}
            """;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.0");

    @LocalServerPort
    private int port;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @BeforeEach
    void cleanDatabase() {
        reservationRepository.deleteAll();
        roomRepository.deleteAll();
    }

    @Test
    void onlyOneOfManyConcurrentRequestsBooksTheSlot() throws Exception {
        Room room = roomRepository.save(new Room(null, "Sala Concurrida"));
        String body = SLOT.formatted(room.getId());

        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);

        try {
            List<Callable<Integer>> attempts = IntStream.range(0, CONCURRENT_REQUESTS)
                    .<Callable<Integer>>mapToObj(ignored -> () -> {
                        startTogether.await();
                        return book(body);
                    })
                    .toList();

            List<Future<Integer>> pending = attempts.stream()
                    .map(pool::submit)
                    .toList();

            startTogether.countDown();

            List<Integer> statuses = pending.stream()
                    .map(ReservationConcurrencyTest::statusOf)
                    .toList();

            assertThat(statuses).as("statuses devueltos: %s", statuses).filteredOn(status -> status == 201).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(CONCURRENT_REQUESTS - 1);
            assertThat(statuses).noneMatch(status -> status >= 500);
        } finally {
            pool.shutdownNow();
        }

        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    private static int statusOf(Future<Integer> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a booking attempt", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Booking attempt failed", ex.getCause());
        }
    }

    private int book(String body) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/reservations"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
