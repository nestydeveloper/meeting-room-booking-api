package dev.nesty.reservation.room;

import dev.nesty.reservation.error.ConflictException;
import dev.nesty.reservation.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {

    private final RoomRepository roomRepository;

    @Override
    @Transactional(readOnly = true)
    public List<RoomResponse> findAll() {
        return roomRepository.findAll(Sort.by("name")).stream()
                .map(RoomServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public RoomResponse create(RoomRequest request) {
        String name = normalizedName(request);
        ensureNameIsAvailable(name);

        return toResponse(roomRepository.save(new Room(null, name)));
    }

    @Override
    @Transactional
    public RoomResponse update(Long id, RoomRequest request) {
        Room room = findRoomOrFail(id);

        String name = normalizedName(request);
        ensureNameIsAvailableForRoom(name, id);

        room.setName(name);
        return toResponse(roomRepository.save(room));
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
