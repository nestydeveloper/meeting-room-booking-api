package dev.nesty.reservation.security;

import dev.nesty.reservation.common.PageResponse;
import dev.nesty.reservation.reservation.CreateReservationRequest;
import dev.nesty.reservation.reservation.ReservationResponse;
import dev.nesty.reservation.reservation.ReservationService;
import dev.nesty.reservation.reservation.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(dev.nesty.reservation.reservation.ReservationController.class)
@Import(SecurityConfig.class)
class ReservationSecurityTest {

    private static final LocalDateTime START = LocalDateTime.of(2030, 1, 15, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2030, 1, 15, 10, 0);

    private static final String BODY = """
            {"roomId":1,"responsible":"ana@example.com",
             "startTime":"2030-01-15T09:00:00","endTime":"2030-01-15T10:00:00"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @Test
    void listingReservationsWorksWithoutCredentials() throws Exception {
        given(reservationService.search(isNull(), isNull(), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/reservations").param("date", "2030-01-15"))
                .andExpect(status().isOk());
    }

    @Test
    void creatingReservationWithoutCredentialsReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")));
    }

    @Test
    void creatingReservationWithValidCredentialsIsAllowed() throws Exception {
        given(reservationService.create(any(CreateReservationRequest.class)))
                .willReturn(new ReservationResponse(
                        10L, 1L, "Sala A", "ana@example.com", START, END, ReservationStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/reservations")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void cancellingReservationWithoutCredentialsReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/reservations/10/cancel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancellingReservationWithValidCredentialsIsAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/reservations/10/cancel").with(httpBasic("admin", "admin")))
                .andExpect(status().isNoContent());
    }
}
