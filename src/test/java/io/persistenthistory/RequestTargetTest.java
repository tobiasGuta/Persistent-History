package io.persistenthistory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestTargetTest {
    @Test
    void derivesDefaultHttpsService() {
        RequestTarget target = RequestTarget.fromUrl("https://example.test/api/users?id=1").orElseThrow();
        assertEquals("example.test", target.host());
        assertEquals(443, target.port());
        assertTrue(target.secure());
    }

    @Test
    void derivesDefaultHttpService() {
        RequestTarget target = RequestTarget.fromUrl("http://example.test/path").orElseThrow();
        assertEquals("example.test", target.host());
        assertEquals(80, target.port());
        assertFalse(target.secure());
    }

    @Test
    void preservesExplicitPort() {
        RequestTarget target = RequestTarget.fromUrl("https://example.test:8443/path").orElseThrow();
        assertEquals("example.test", target.host());
        assertEquals(8443, target.port());
        assertTrue(target.secure());
    }

    @Test
    void rejectsUnsupportedOrMalformedUrls() {
        assertTrue(RequestTarget.fromUrl("ftp://example.test/file").isEmpty());
        assertTrue(RequestTarget.fromUrl("/relative/path").isEmpty());
        assertTrue(RequestTarget.fromUrl("not a url").isEmpty());
        assertTrue(RequestTarget.fromUrl(null).isEmpty());
    }
}
