CREATE TABLE rooms (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE reservations (
    id          BIGSERIAL PRIMARY KEY,
    room_id     BIGINT       NOT NULL REFERENCES rooms (id),
    responsible VARCHAR(150) NOT NULL,
    start_time  TIMESTAMP    NOT NULL,
    end_time    TIMESTAMP    NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT chk_reservations_time_range CHECK (end_time > start_time),
    CONSTRAINT chk_reservations_status CHECK (status IN ('ACTIVE', 'CANCELLED'))
);

-- Supported access paths: reservations of a room, and reservations of a day.
CREATE INDEX idx_reservations_room_start ON reservations (room_id, start_time);
CREATE INDEX idx_reservations_start_end ON reservations (start_time, end_time);
