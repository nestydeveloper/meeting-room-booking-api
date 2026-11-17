package dev.nesty.reservation.room;

import dev.nesty.reservation.error.ConflictException;
import dev.nesty.reservation.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceImplTest {

    @Mock
    private RoomRepository roomRepository;

    @InjectMocks
    private RoomServiceImpl roomService;

    @Test
    void creatingRoomWithExistingNameThrowsConflict() {
        when(roomRepository.existsByName("Sala A")).thenReturn(true);

        assertThatThrownBy(() -> roomService.create(new RoomRequest("Sala A")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Sala A");

        verify(roomRepository, never()).save(any());
    }

    @Test
    void creatingRoomPersistsTrimmedName() {
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            room.setId(7L);
            return room;
        });

        RoomResponse response = roomService.create(new RoomRequest("  Sala A  "));

        ArgumentCaptor<Room> captor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Sala A");
        assertThat(response).isEqualTo(new RoomResponse(7L, "Sala A"));
    }

    @Test
    void updatingUnknownRoomThrowsNotFound() {
        when(roomRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.update(99L, new RoomRequest("Sala B")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("99");

        verify(roomRepository, never()).save(any());
    }

    @Test
    void updatingRoomToNameOfAnotherRoomThrowsConflict() {
        when(roomRepository.findById(1L)).thenReturn(Optional.of(new Room(1L, "Sala A")));
        when(roomRepository.existsByNameAndIdNot("Sala B", 1L)).thenReturn(true);

        assertThatThrownBy(() -> roomService.update(1L, new RoomRequest("Sala B")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Sala B");
    }

    @Test
    void updatingRoomKeepsItsOwnNameWithoutConflict() {
        Room room = new Room(1L, "Sala A");
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(roomRepository.existsByNameAndIdNot("Sala A", 1L)).thenReturn(false);
        when(roomRepository.save(room)).thenReturn(room);

        RoomResponse response = roomService.update(1L, new RoomRequest(" Sala A "));

        assertThat(response).isEqualTo(new RoomResponse(1L, "Sala A"));
    }

    @Test
    void listingRoomsReturnsEveryRoomOrderedByName() {
        when(roomRepository.findAll(any(Sort.class)))
                .thenReturn(List.of(new Room(1L, "Sala A"), new Room(2L, "Sala B")));

        assertThat(roomService.findAll())
                .containsExactly(new RoomResponse(1L, "Sala A"), new RoomResponse(2L, "Sala B"));

        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(roomRepository).findAll(sort.capture());
        assertThat(sort.getValue().toString()).contains("name");
    }
}
