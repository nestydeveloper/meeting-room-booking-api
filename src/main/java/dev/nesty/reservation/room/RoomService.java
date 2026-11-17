package dev.nesty.reservation.room;

import java.util.List;

public interface RoomService {

    List<RoomResponse> findAll();

    RoomResponse create(RoomRequest request);

    RoomResponse update(Long id, RoomRequest request);
}
