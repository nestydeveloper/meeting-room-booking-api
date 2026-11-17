package dev.nesty.reservation.reservation;

import dev.nesty.reservation.common.PageResponse;
import dev.nesty.reservation.error.BadRequestException;
import dev.nesty.reservation.error.ConflictException;
import dev.nesty.reservation.error.NotFoundException;
import dev.nesty.reservation.room.Room;
import dev.nesty.reservation.room.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private final ReservationRepository reservationRepository;
    private final RoomRepository roomRepository;

    @Override
    @Transactional
    public ReservationResponse create(CreateReservationRequest request) {
        ensureEndIsAfterStart(request);

        Room room = findRoomOrFail(request.roomId());
        ensureRoomIsFree(room, request);

        Reservation reservation = newActiveReservation(room, request);
        return toResponse(reservationRepository.save(reservation));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReservationResponse> search(Long roomId, LocalDate date, Pageable pageable) {
        Page<Reservation> page = reservationRepository.findAll(activeOnly(roomId, date), pageable);
        return toPageResponse(page.map(ReservationServiceImpl::toResponse));
    }

    @Override
    @Transactional
    public void cancel(Long id) {
        Reservation reservation = findReservationOrFail(id);
        if (isAlreadyCancelled(reservation)) {
            return;
        }

        ensureIsNotFinished(reservation);

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);
    }

    private static Reservation newActiveReservation(Room room, CreateReservationRequest request) {
        return Reservation.builder()
                .room(room)
                .responsible(request.responsible().trim())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .status(ReservationStatus.ACTIVE)
                .build();
    }

    private static void ensureEndIsAfterStart(CreateReservationRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new BadRequestException("endTime must be after startTime");
        }
    }

    private Room findRoomOrFail(Long roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found: " + roomId));
    }

    private void ensureRoomIsFree(Room room, CreateReservationRequest request) {
        boolean overlapping = reservationRepository.existsActiveOverlapping(
                room.getId(), request.startTime(), request.endTime());

        if (overlapping) {
            throw new ConflictException("Room %s is already booked between %s and %s"
                    .formatted(room.getName(), request.startTime(), request.endTime()));
        }
    }

    private Reservation findReservationOrFail(Long id) {
        return reservationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Reservation not found: " + id));
    }

    private static boolean isAlreadyCancelled(Reservation reservation) {
        return reservation.getStatus() == ReservationStatus.CANCELLED;
    }

    private static void ensureIsNotFinished(Reservation reservation) {
        if (!reservation.getEndTime().isAfter(LocalDateTime.now())) {
            throw new ConflictException("Reservation %d has already ended and cannot be cancelled"
                    .formatted(reservation.getId()));
        }
    }

    private static Specification<Reservation> activeOnly(Long roomId, LocalDate date) {
        List<Specification<Reservation>> filters = new ArrayList<>();
        filters.add((root, query, builder) ->
                builder.equal(root.get("status"), ReservationStatus.ACTIVE));

        if (roomId != null) {
            filters.add((root, query, builder) ->
                    builder.equal(root.get("room").get("id"), roomId));
        }

        if (date != null) {
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
            filters.add((root, query, builder) -> builder.and(
                    builder.lessThan(root.get("startTime"), dayEnd),
                    builder.greaterThan(root.get("endTime"), dayStart)));
        }

        return Specification.allOf(filters);
    }

    private static ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getRoom().getId(),
                reservation.getRoom().getName(),
                reservation.getResponsible(),
                reservation.getStartTime(),
                reservation.getEndTime(),
                reservation.getStatus());
    }

    private static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
