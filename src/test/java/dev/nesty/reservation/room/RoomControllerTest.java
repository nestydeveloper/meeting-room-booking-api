package dev.nesty.reservation.room;

import dev.nesty.reservation.error.ConflictException;
import dev.nesty.reservation.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RoomController.class)
class RoomControllerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomService roomService;

    @Test
    void creatingRoomWithBlankNameReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        verify(roomService, never()).create(any());
    }

    @Test
    void creatingRoomWithMissingNameReturns400() throws Exception {
        mockMvc.perform(post("/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void creatingValidRoomReturns201WithLocation() throws Exception {
        given(roomService.create(any(RoomRequest.class))).willReturn(new RoomResponse(1L, "Sala A"));

        mockMvc.perform(post("/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala A\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/rooms/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Sala A"));
    }

    @Test
    void creatingDuplicateRoomReturns409ProblemDetail() throws Exception {
        given(roomService.create(any(RoomRequest.class)))
                .willThrow(new ConflictException("Room name already exists: Sala A"));

        mockMvc.perform(post("/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala A\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Room name already exists: Sala A"));
    }

    @Test
    void listingRoomsReturns200WithArray() throws Exception {
        given(roomService.findAll()).willReturn(List.of(new RoomResponse(1L, "Sala A")));

        mockMvc.perform(get("/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Sala A"));
    }

    @Test
    void updatingRoomReturns200WithUpdatedBody() throws Exception {
        given(roomService.update(eq(1L), any(RoomRequest.class)))
                .willReturn(new RoomResponse(1L, "Sala A - Planta 2"));

        mockMvc.perform(put("/rooms/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala A - Planta 2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sala A - Planta 2"));
    }

    @Test
    void updatingRoomWithBlankNameReturns400() throws Exception {
        mockMvc.perform(put("/rooms/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        verify(roomService, never()).update(any(), any());
    }

    @Test
    void updatingUnknownRoomReturns404() throws Exception {
        given(roomService.update(eq(99L), any(RoomRequest.class)))
                .willThrow(new NotFoundException("Room not found: 99"));

        mockMvc.perform(put("/rooms/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala B\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Room not found: 99"));
    }

    @Test
    void unreadableJsonBodyReturns400ProblemDetail() throws Exception {
        mockMvc.perform(post("/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }
}
