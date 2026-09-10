package net.allayfind.paper;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

public final class ApiClient implements AutoCloseable {
    private final HttpClient http;
    private final URI endpoint;
    private final String token;
    private final long serverId;
    private final String edition;

    public ApiClient(String origin, long serverId, String token, String edition, boolean localHttp) {
        URI base = URI.create(origin);
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(Objects.toString(base.getHost(), ""));
        boolean scheme = "https".equals(base.getScheme()) ||
                (localHttp && loopback && "http".equals(base.getScheme()));
        if (!scheme || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null ||
                base.getFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/")) ||
                serverId < 1 || token == null || !token.matches("[A-Za-z0-9_-]{20,128}") ||
                !Set.of("java", "bedrock").contains(edition)) {
            throw new IllegalArgumentException("Check site-url, server-id, api-token and edition in config.yml");
        }
        this.serverId = serverId;
        this.token = token;
        this.edition = edition;
        endpoint = base.resolve("/api/v1/servers/" + serverId + "/votes/");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String scope() { return endpoint.toString(); }

    private JsonObject request(String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json");
        if (body != null) request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), info -> new LimitedBody());
        if (response.statusCode() != 200) throw new HttpStatusException(response.statusCode());
        try { return JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Invalid AllayFind JSON"); }
    }

    public List<VoteEvent> pending() throws IOException, InterruptedException {
        try {
            JsonArray rows = request(null).getAsJsonArray("votes");
            if (rows == null || rows.size() > 100) throw new IllegalArgumentException();
            List<VoteEvent> result = new ArrayList<>();
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                UUID id = UUID.fromString(row.get("id").getAsString());
                long server = row.get("server_id").getAsLong();
                String nickname = row.get("nickname").getAsString();
                String voteEdition = row.get("edition").getAsString();
                if (server != serverId || !edition.equals(voteEdition) ||
                        !nickname.matches("[A-Za-z0-9_.][A-Za-z0-9_. ]{0,31}")) throw new IllegalArgumentException();
                result.add(new VoteEvent(id, server, nickname, voteEdition,
                        Instant.parse(row.get("created_at").getAsString())));
            }
            return result;
        } catch (RuntimeException e) { throw new IOException("Invalid vote batch (ID, server, edition or nickname)"); }
    }

    public void acknowledge(List<VoteEvent> votes) throws IOException, InterruptedException {
        if (votes.isEmpty()) return;
        JsonArray ids = new JsonArray();
        votes.forEach(vote -> ids.add(vote.id().toString()));
        JsonObject body = new JsonObject();
        body.add("ids", ids);
        if (!"ok".equals(request(body.toString()).get("status").getAsString()))
            throw new IOException("Acknowledgement not accepted");
    }

    public void close() { http.close(); }

    public static final class HttpStatusException extends IOException {
        public HttpStatusException(int status) { super("AllayFind HTTP " + status); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<String> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value; value.request(1);
        }
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > 262144) {
                    subscription.cancel(); result.completeExceptionally(new IOException("AllayFind response too large")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)); }
    }
}
