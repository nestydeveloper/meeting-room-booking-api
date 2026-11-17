package dev.nesty.reservation.reservation;

import dev.nesty.reservation.room.Room;
import dev.nesty.reservation.room.RoomRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the listing query count. Without a fetch graph, every distinct room in the page costs
 * one extra SELECT, so a page of 20 reservations in 20 rooms fires 22 queries.
 */
@Testcontainers
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class ReservationQueryCountTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2030, 4, 20, 9, 0);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.0");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

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
    void listingReservationsFetchesRoomsWithThePage() throws Exception {
        IntStream.rangeClosed(1, 3).forEach(index -> {
            Room room = roomRepository.save(new Room(null, "Sala " + index));
            reservationRepository.save(Reservation.builder()
                    .room(room)
                    .responsible("ana@example.com")
                    .startTime(DAY.plusHours(index))
                    .endTime(DAY.plusHours(index).plusMinutes(30))
                    .status(ReservationStatus.ACTIVE)
                    .build());
        });

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(get("/api/v1/reservations")
                        .param("date", "2030-04-20")
                        .param("size", "3"))
                .andExpect(status().isOk());

        // One page query with the rooms joined in, plus one count query.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }
}
