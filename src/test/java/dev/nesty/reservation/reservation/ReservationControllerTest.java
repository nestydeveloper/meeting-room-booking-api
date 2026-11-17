package dev.nesty.reservation.reservation;

import dev.nesty.reservation.common.PageResponse;
import dev.nesty.reservation.error.ConflictException;
import dev.nesty.reservation.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final LocalDateTime START = LocalDateTime.of(2030, 1, 15, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2030, 1, 15, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    private static String body(LocalDateTime start, LocalDateTime end) {
        return """
                {"roomId":1,"responsible":"ana@example.com","startTime":"%s","endTime":"%s"}
                """.formatted(start, end);
    }

    private static ReservationResponse response() {
        return new ReservationResponse(
                10L, 1L, "Sala A", "ana@example.com", START, END, ReservationStatus.ACTIVE);
    }

    @Test
    void creatingValidReservationReturns201WithLocation() throws Exception {
        given(reservationService.create(any(CreateReservationRequest.class))).willReturn(response());

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(START, END)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/reservations/10")))
                .andExpect(jsonPath("$.roomId").value(1))
                .andExpect(jsonPath("$.roomName").value("Sala A"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void creatingReservationWithMissingFieldsReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field",
                        hasItems("roomId", "responsible", "startTime", "endTime")));

        verify(reservationService, never()).create(any());
    }

    @Test
    void creatingReservationWithEndBeforeStartReturns400() throws Exception {
        given(reservationService.create(any(CreateReservationRequest.class)))
                .willThrow(new dev.nesty.reservation.error.BadRequestException(
                        "endTime must be after startTime"));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(END, START)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("endTime must be after startTime"));
    }

    @Test
    void creatingOverlappingReservationReturns409() throws Exception {
        given(reservationService.create(any(CreateReservationRequest.class)))
                .willThrow(new ConflictException("Room Sala A is already booked"));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(START, END)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Room Sala A is already booked"));
    }

    @Test
    void creatingReservationForUnknownRoomReturns404() throws Exception {
        given(reservationService.create(any(CreateReservationRequest.class)))
                .willThrow(new NotFoundException("Room not found: 1"));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(START, END)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listingReservationsReturnsPagedBody() throws Exception {
        given(reservationService.search(eq(1L), eq(LocalDate.of(2030, 1, 15)), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(response()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/reservations")
                        .param("roomId", "1")
                        .param("date", "2030-01-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void listingReservationsWithInvalidDateReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reservations").param("date", "15/01/2030"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void cancellingReservationReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/reservations/10/cancel"))
                .andExpect(status().isNoContent());

        verify(reservationService).cancel(10L);
    }

    @Test
    void cancellingFinishedReservationReturns409() throws Exception {
        willThrow(new ConflictException("Reservation 10 has already ended and cannot be cancelled"))
                .given(reservationService).cancel(10L);

        mockMvc.perform(post("/api/v1/reservations/10/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Reservation 10 has already ended and cannot be cancelled"));
    }

    @Test
    void cancellingUnknownReservationReturns404() throws Exception {
        willThrow(new NotFoundException("Reservation not found: 99"))
                .given(reservationService).cancel(99L);

        mockMvc.perform(post("/api/v1/reservations/99/cancel"))
                .andExpect(status().isNotFound());
    }
}
