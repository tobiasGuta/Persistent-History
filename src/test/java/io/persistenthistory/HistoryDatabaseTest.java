package io.persistenthistory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryDatabaseTest {
    @TempDir
    Path tempDir;

    @Test
    void storesBinaryResponseByteExactly() throws Exception {
        byte[] request = "GET /binary HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes();
        byte[] response = new byte[] {
                'H','T','T','P','/','1','.','1',' ','2','0','0',' ','O','K','\r','\n',
                'C','o','n','t','e','n','t','-','L','e','n','g','t','h',':',' ','8','\r','\n','\r','\n',
                0x00, 0x01, 0x02, 0x7f, (byte) 0x80, (byte) 0xff, 0x00, 0x42
        };

        try (HistoryDatabase db = new HistoryDatabase(tempDir.resolve("binary.sqlite3"))) {
            long id = db.insert(System.currentTimeMillis(), "Proxy", "GET", "https://example.test/binary", 200, request, response);
            HistoryEntry stored = db.get(id);
            assertNotNull(stored);
            assertArrayEquals(request, stored.request());
            assertArrayEquals(response, stored.response());
        }
    }

    @Test
    void storesLargeResponseByteExactly() throws Exception {
        int bodySize = 8 * 1024 * 1024;
        byte[] body = new byte[bodySize];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 31);
        byte[] headers = ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes();
        byte[] response = Arrays.copyOf(headers, headers.length + body.length);
        System.arraycopy(body, 0, response, headers.length, body.length);
        byte[] request = "GET /large HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes();

        try (HistoryDatabase db = new HistoryDatabase(tempDir.resolve("large.sqlite3"))) {
            long id = db.insert(System.currentTimeMillis(), "Proxy", "GET", "https://example.test/large", 200, request, response);
            HistoryEntry stored = db.get(id);
            assertNotNull(stored);
            assertArrayEquals(response, stored.response());
        }
    }

    @Test
    void metadataSearchDoesNotMaterializeMessageBodies() throws Exception {
        byte[] request = new byte[1024 * 1024];
        byte[] response = new byte[4 * 1024 * 1024];

        try (HistoryDatabase db = new HistoryDatabase(tempDir.resolve("metadata.sqlite3"))) {
            db.insert(System.currentTimeMillis(), "Repeater", "POST", "https://example.test/oauth", 403, request, response);
            List<HistoryEntry> rows = db.searchMetadata("oauth", 100);
            assertEquals(1, rows.size());
            assertNull(rows.getFirst().request());
            assertNull(rows.getFirst().response());
            assertEquals("Repeater", rows.getFirst().tool());
            assertEquals(403, rows.getFirst().status());
        }
    }

    @Test
    void metadataPathRemainsFastWithFiveThousandRows() throws Exception {
        byte[] request = "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes();
        byte[] response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes();

        try (HistoryDatabase db = new HistoryDatabase(tempDir.resolve("scale.sqlite3"))) {
            for (int i = 0; i < 5000; i++) {
                db.insert(i, i % 2 == 0 ? "Proxy" : "Repeater", "GET", "https://example.test/item/" + i, 200, request, response);
            }

            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                List<HistoryEntry> rows = db.searchMetadata("", 5000);
                assertEquals(5000, rows.size());
                assertTrue(rows.stream().allMatch(e -> e.request() == null && e.response() == null));
            });
            assertEquals(5000, db.count());
        }
    }
}
