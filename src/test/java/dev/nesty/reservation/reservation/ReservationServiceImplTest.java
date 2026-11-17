package dev.nesty.reservation.reservation;

import dev.nesty.reservation.common.PageResponse;
import dev.nesty.reservation.error.BadRequestException;
import dev.nesty.reservation.error.ConflictException;
import dev.nesty.reservation.error.NotFoundException;
import dev.nesty.reservation.room.Room;
import dev.nesty.reservation.room.RoomRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    private static final LocalDateTime START = LocalDateTime.of(2030, 1, 15, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2030, 1, 15, 10, 0);
    private static final Room ROOM = new Room(1L, "Sala A");

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private RoomRepository roomRepository;

    @InjectMocks
    private ReservationServiceImpl reservationService;

    private static CreateReservationRequest request(LocalDateTime start, LocalDateTime end) {
        return new CreateReservationRequest(1L, "ana@example.com", start, end);
    }

    @Test
    void creatingReservationWhenRoomIsBusyThrowsConflict() {
        when(roomRepository.findById(1L)).thenReturn(Optional.of(ROOM));
        when(reservationRepository.existsActiveOverlapping(1L, START, END)).thenReturn(true);

        assertThatThrownBy(() -> reservationService.create(request(START, END)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Sala A");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    void creatingReservationWithEndBeforeStartThrowsBadRequest() {
        assertThatThrownBy(() -> reservationService.create(request(END, START)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("endTime must be after startTime");

        verify(roomRepository, never()).findById(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void creatingReservationWithEndEqualToStartThrowsBadRequest() {
        assertThatThrownBy(() -> reservationService.create(request(START, START)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void creatingReservationForUnknownRoomThrowsNotFound() {
        when(roomRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.create(request(START, END)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("1");
    }

    @Test
    void creatingFreeReservationPersistsItAsActive() {
        when(roomRepository.findById(1L)).thenReturn(Optional.of(ROOM));
        when(reservationRepository.existsActiveOverlapping(1L, START, END)).thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.setId(10L);
            return reservation;
        });

        ReservationResponse response = reservationService.create(
                new CreateReservationRequest(1L, "  ana@example.com  ", START, END));

        assertThat(response).isEqualTo(new ReservationResponse(
                10L, 1L, "Sala A", "ana@example.com", START, END, ReservationStatus.ACTIVE));
    }

    @Test
    void cancellingUnknownReservationThrowsNotFound() {
        when(reservationRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.cancel(5L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("5");
    }

    @Test
    void cancellingActiveReservationMarksItCancelled() {
        Reservation reservation = activeReservation();
        when(reservationRepository.findById(5L)).thenReturn(Optional.of(reservation));

        reservationService.cancel(5L);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(reservationRepository).save(reservation);
    }

    @Test
    void cancellingAlreadyCancelledReservationIsIdempotent() {
        Reservation reservation = activeReservation();
        reservation.setStatus(ReservationStatus.CANCELLED);
        when(reservationRepository.findById(5L)).thenReturn(Optional.of(reservation));

        reservationService.cancel(5L);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void cancellingFinishedReservationThrowsConflict() {
        Reservation reservation = activeReservation();
        reservation.setStartTime(LocalDateTime.now().minusHours(3));
        reservation.setEndTime(LocalDateTime.now().minusHours(2));
        when(reservationRepository.findById(5L)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.cancel(5L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already ended");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void searchingReservationsReturnsPagedBody() {
        Pageable pageable = PageRequest.of(0, 20);
        when(reservationRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(activeReservation()), pageable, 1));

        PageResponse<ReservationResponse> response = reservationService.search(
                null, LocalDate.of(2030, 1, 15), pageable);

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.page()).isZero();
        assertThat(response.content().getFirst().roomName()).isEqualTo("Sala A");
    }

    private static Reservation activeReservation() {
        return Reservation.builder()
                .id(5L)
                .room(ROOM)
                .responsible("ana@example.com")
                .startTime(START)
                .endTime(END)
                .status(ReservationStatus.ACTIVE)
                .build();
    }
}
