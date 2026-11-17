package dev.nesty.reservation.room;

import dev.nesty.reservation.error.ConflictException;
import dev.nesty.reservation.error.NotFoundException;
import dev.nesty.reservation.error.SqlStateErrors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {

    private final RoomRepository roomRepository;

    @Override
    @Cacheable("rooms")
    @Transactional(readOnly = true)
    public List<RoomResponse> findAll() {
        return roomRepository.findAll(Sort.by("name")).stream()
                .map(RoomServiceImpl::toResponse)
                .toList();
    }

    @Override
    @CacheEvict(value = "rooms", allEntries = true)
    @Transactional
    public RoomResponse create(RoomRequest request) {
        String name = normalizedName(request);
        ensureNameIsAvailable(name);

        return toResponse(insert(new Room(null, name)));
    }

    @Override
    @CacheEvict(value = "rooms", allEntries = true)
    @Transactional
    public RoomResponse update(Long id, RoomRequest request) {
        Room room = findRoomOrFail(id);

        String name = normalizedName(request);
        ensureNameIsAvailableForRoom(name, id);

        room.setName(name);
        return toResponse(saveWithUniqueName(room));
    }

    private Room insert(Room room) {
        try {
            return roomRepository.saveAndFlush(room);
        } catch (DataIntegrityViolationException ex) {
            throw translatedUniqueViolation(ex, room.getName());
        }
    }

    private Room saveWithUniqueName(Room room) {
        try {
            return roomRepository.saveAndFlush(room);
        } catch (DataIntegrityViolationException ex) {
            throw translatedUniqueViolation(ex, room.getName());
        }
    }

    private static RuntimeException translatedUniqueViolation(DataIntegrityViolationException ex, String name) {
        // Two concurrent requests can both pass ensureNameIsAvailable; the unique index rejects
        // the loser, which must be reported as the same conflict as the friendly path.
        if (SqlStateErrors.is(ex, SqlStateErrors.UNIQUE_VIOLATION)) {
            return new ConflictException("Room name already exists: " + name);
        }
        return ex;
    }

    private static String normalizedName(RoomRequest request) {
        return request.name().trim();
    }

    private void ensureNameIsAvailable(String name) {
        if (roomRepository.existsByName(name)) {
            throw new ConflictException("Room name already exists: " + name);
        }
    }

    private void ensureNameIsAvailableForRoom(String name, Long roomId) {
        if (roomRepository.existsByNameAndIdNot(name, roomId)) {
            throw new ConflictException("Room name already exists: " + name);
        }
    }

    private Room findRoomOrFail(Long id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Room not found: " + id));
    }

    private static RoomResponse toResponse(Room room) {
        return new RoomResponse(room.getId(), room.getName());
    }
}
