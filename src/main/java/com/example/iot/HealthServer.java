package com.example.iot;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Minimal HTTP server that returns HTTP 200 on GET /
 * Used by Kubernetes readinessProbe and livenessProbe.
 */
public class HealthServer {

    private static final int PORT = 8080;

    public static void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", exchange -> {
            byte[] body = "OK".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(null); // use default executor
        server.start();
        System.out.println("[HealthServer] Listening on port " + PORT);
    }
}
