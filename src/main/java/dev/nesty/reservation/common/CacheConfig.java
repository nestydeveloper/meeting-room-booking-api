package dev.nesty.reservation.common;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on the annotation-driven cache.
 *
 * <p>Only the room catalogue is cached: it is reference data that changes rarely and is read on
 * every client page load. Reservation listings are deliberately left uncached, because they are
 * the availability view: a stale answer would either show a slot as free that was just taken or
 * hide one that was just released.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
