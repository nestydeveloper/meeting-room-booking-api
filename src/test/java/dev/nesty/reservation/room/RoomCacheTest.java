package dev.nesty.reservation.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The room catalogue is cached, so a write must invalidate it.
 *
 * <p>The cache is observed from the outside: a row removed behind the service's back is still
 * served from the cache, which proves the read is cached, and the next write brings the listing
 * back to the database state, which proves the eviction.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RoomCacheTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.0");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void cleanDatabaseAndCache() {
        roomRepository.deleteAll();
        cacheManager.getCache("rooms").clear();
    }

    @Test
    void roomsCacheIsConfigured() {
        assertThat(cacheManager.getCache("rooms")).isNotNull();
    }

    @Test
    void listingIsServedFromTheCacheUntilAWriteEvictsIt() throws Exception {
        long firstRoomId = createRoom("Sala A");

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Sala A"));

        // Remove the row behind the service's back: a cached listing must still show it.
        roomRepository.deleteById(firstRoomId);

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Sala A"))
                .andExpect(jsonPath("$.length()").value(1));

        // A write evicts the cache, so the next read goes to the database again.
        createRoom("Sala B");

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Sala B"));
    }

    @Test
    void updatingARoomEvictsTheCache() throws Exception {
        long roomId = createRoom("Sala A");

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(jsonPath("$[0].name").value("Sala A"));

        mockMvc.perform(put("/api/v1/rooms/" + roomId)
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sala Renombrada\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(jsonPath("$[0].name").value("Sala Renombrada"));
    }

    private long createRoom(String name) throws Exception {
        String location = mockMvc.perform(post("/api/v1/rooms")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }
}
