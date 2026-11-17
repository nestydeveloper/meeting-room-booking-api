-- PostgreSQL guarantees room exclusivity: no two ACTIVE reservations of the same room may
-- overlap. The application-level check stays as the friendly 409 path, but this constraint is
-- what actually holds under concurrent requests. Cancelled reservations never block a slot.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE reservations
    ADD CONSTRAINT excl_reservations_no_overlap
        EXCLUDE USING gist (
            room_id WITH =,
            tsrange(start_time, end_time, '[)') WITH &&
        ) WHERE (status = 'ACTIVE');
