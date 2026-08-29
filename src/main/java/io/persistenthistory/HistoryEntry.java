package io.persistenthistory;

public record HistoryEntry(
        long id,
        long capturedAt,
        String tool,
        String method,
        String url,
        int status,
        byte[] request,
        byte[] response
) {}
