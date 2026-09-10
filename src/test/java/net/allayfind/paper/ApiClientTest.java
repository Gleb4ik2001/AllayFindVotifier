package net.allayfind.paper;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiClientTest {
    HttpServer server;
    String origin;
    String token = "test_api_token_with_enough_entropy";
    String id = UUID.randomUUID().toString();
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> acknowledged = new AtomicReference<>();
    AtomicInteger status = new AtomicInteger(200);
    String response;
    @TempDir Path folder;

    @BeforeEach void start() throws Exception {
        response = "{\"votes\":[{\"id\":\"" + id + "\",\"server_id\":7,\"nickname\":\"Player_1\",\"edition\":\"java\",\"created_at\":\"2026-09-11T00:00:00+00:00\"}]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v1/servers/7/votes/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String text = response;
            if (exchange.getRequestMethod().equals("POST")) {
                acknowledged.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                text = "{\"status\":\"ok\"}";
            }
            if (status.get() == 302) exchange.getResponseHeaders().add("Location", "/trap");
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    ApiClient client() { return new ApiClient(origin, 7, token, "java", true); }

    @Test void realHttpPollPersistThenAcknowledgeAndRedelivery() throws Exception {
        ApiClient api = client();
        try (Inbox inbox = new Inbox(folder.resolve("votes.db"), api.scope())) {
            var votes = api.pending();
            assertEquals("Bearer " + token, auth.get());
            assertNull(acknowledged.get());
            inbox.save(votes, List.of("give {player} diamond 1"));
            api.acknowledge(votes);
            assertTrue(acknowledged.get().contains(id));
            inbox.save(api.pending(), List.of("give {player} diamond 1"));
            assertEquals(1, inbox.pending("Player_1").size());
        }
    }

    @Test void refusesWrongServerEditionAndCommandInjection() {
        for (String changed : List.of(response.replace("\"server_id\":7", "\"server_id\":8"),
                response.replace("\"java\"", "\"bedrock\""),
                response.replace("Player_1", "Player;op"))) {
            response = changed;
            assertThrows(IOException.class, () -> client().pending());
        }
    }

    @Test void redirectsAndUnauthorizedAreNotAccepted() {
        status.set(302);
        assertThrows(IOException.class, () -> client().pending());
        status.set(401);
        IOException error = assertThrows(IOException.class, () -> client().pending());
        assertFalse(error.toString().contains(token));
        assertTrue(error.getMessage().contains("401"));
    }

    @Test void productionRequiresHttpsAndOriginWithoutCredentials() {
        assertThrows(IllegalArgumentException.class, () -> new ApiClient(origin, 7, token, "java", false));
        assertThrows(IllegalArgumentException.class, () -> new ApiClient("http://example.com", 7, token, "java", true));
        assertThrows(IllegalArgumentException.class, () -> new ApiClient("https://user:pass@example.com", 7, token, "java", false));
        assertThrows(IllegalArgumentException.class, () -> new ApiClient("https://example.com/path", 7, token, "java", false));
    }

    @Test void rejectsOversizedResponseBeforeParsing() {
        response = "x".repeat(300000);
        assertThrows(IOException.class, () -> client().pending());
    }
}
