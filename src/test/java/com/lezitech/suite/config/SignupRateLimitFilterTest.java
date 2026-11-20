package com.lezitech.suite.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Signing up is the one open door anyone can push: a script must not be able to create businesses all day. */
class SignupRateLimitFilterTest {

    private static MockHttpServletRequest signup(String from) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/signup");
        req.setRemoteAddr(from);
        return req;
    }

    private static int statusAfter(SignupRateLimitFilter filter, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, mock(FilterChain.class));
        return res.getStatus();
    }

    @Test
    void aFewSignUpsFromOneAddressGoThroughAndTheRestWait() throws Exception {
        SignupRateLimitFilter filter = new SignupRateLimitFilter(2);
        assertThat(statusAfter(filter, signup("41.90.1.1"))).isEqualTo(200);
        assertThat(statusAfter(filter, signup("41.90.1.1"))).isEqualTo(200);

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(signup("41.90.1.1"), third, mock(FilterChain.class));
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getContentAsString()).contains("talk to us").doesNotContain("Exception");
    }

    @Test
    void anotherPlaceHasItsOwnAllowance() throws Exception {
        SignupRateLimitFilter filter = new SignupRateLimitFilter(1);
        assertThat(statusAfter(filter, signup("41.90.1.2"))).isEqualTo(200);
        assertThat(statusAfter(filter, signup("41.90.1.2"))).isEqualTo(429);
        assertThat(statusAfter(filter, signup("197.248.3.4"))).isEqualTo(200);
    }

    @Test
    void theFakedFirstForwardedAddressDoesNotBuyMoreSignUps() throws Exception {
        SignupRateLimitFilter filter = new SignupRateLimitFilter(1);
        MockHttpServletRequest first = signup("10.0.0.9");
        first.addHeader("X-Forwarded-For", "1.1.1.1, 41.90.5.5");
        assertThat(statusAfter(filter, first)).isEqualTo(200);
        // Same real caller, a different made-up first entry: still counted as the same address.
        MockHttpServletRequest again = signup("10.0.0.9");
        again.addHeader("X-Forwarded-For", "2.2.2.2, 41.90.5.5");
        assertThat(statusAfter(filter, again)).isEqualTo(429);
    }

    @Test
    void everyOtherRequestPassesStraightThrough() throws Exception {
        SignupRateLimitFilter filter = new SignupRateLimitFilter(1);
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/auth/login");
        login.setRemoteAddr("41.90.1.3");
        assertThat(statusAfter(filter, login)).isEqualTo(200);
        assertThat(statusAfter(filter, login)).isEqualTo(200);
        MockHttpServletRequest reading = new MockHttpServletRequest("GET", "/api/auth/signup");
        reading.setRemoteAddr("41.90.1.3");
        assertThat(statusAfter(filter, reading)).isEqualTo(200);
    }
}
