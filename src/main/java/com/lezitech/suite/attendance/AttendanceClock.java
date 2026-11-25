package com.lezitech.suite.attendance;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;

/**
 * The one clock attendance uses: the server's. Times shown and compared are Africa/Nairobi. Tests can freeze it to
 * try hours, grace, code windows and the nightly jobs; the phone's own clock is never trusted.
 */
@Component
public class AttendanceClock {

    public static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private volatile Instant frozen;

    public Instant now() {
        Instant f = frozen;
        return f != null ? f : Instant.now();
    }

    /** For tests: stand still at this moment (null = run normally again). */
    public void freeze(Instant at) {
        this.frozen = at;
    }
}
