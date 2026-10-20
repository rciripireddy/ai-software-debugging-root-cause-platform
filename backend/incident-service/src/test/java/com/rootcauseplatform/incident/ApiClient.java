package com.rootcauseplatform.incident;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal HTTP client for exercising the running application in tests. */
public class ApiClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final String baseUrl;

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    public Response postJson(String path, Object body) {
        return postRaw(path, jsonMapper.writeValueAsString(body), "application/json");
    }

    public Response postRaw(String path, String body, String contentType) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    public Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public final class Response {

        private final HttpResponse<String> response;

        private Response(HttpResponse<String> response) {
            this.response = response;
        }

        public int status() {
            return response.statusCode();
        }

        public String body() {
            return response.body();
        }

        public JsonNode json() {
            return jsonMapper.readTree(response.body());
        }

        public String header(String name) {
            return response.headers().firstValue(name).orElse(null);
        }
    }
}
