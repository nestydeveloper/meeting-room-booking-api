package dev.nesty.reservation.security;

import dev.nesty.reservation.room.RoomRequest;
import dev.nesty.reservation.room.RoomResponse;
import dev.nesty.reservation.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(dev.nesty.reservation.room.RoomController.class)
@Import(SecurityConfig.class)
class RoomSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomService roomService;

    @Test
    void listingRoomsWorksWithoutCredentials() throws Exception {
        given(roomService.findAll()).willReturn(List.of(new RoomResponse(1L, "Sala A")));

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isOk());
    }

    @Test
    void creatingRoomWithoutCredentialsReturns401WithBasicChallenge() throws Exception {
        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala A\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")));
    }

    @Test
    void creatingRoomWithWrongCredentialsReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/rooms")
                        .with(httpBasic("admin", "wrong-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala A\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creatingRoomWithValidCredentialsIsAllowed() throws Exception {
        given(roomService.create(any(RoomRequest.class))).willReturn(new RoomResponse(1L, "Sala A"));

        mockMvc.perform(post("/api/v1/rooms")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala A\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void updatingRoomWithoutCredentialsReturns401() throws Exception {
        mockMvc.perform(put("/api/v1/rooms/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala B\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updatingRoomWithValidCredentialsIsAllowed() throws Exception {
        given(roomService.update(eq(1L), any(RoomRequest.class)))
                .willReturn(new RoomResponse(1L, "Sala B"));

        mockMvc.perform(put("/api/v1/rooms/1")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala B\"}"))
                .andExpect(status().isOk());
    }
}
