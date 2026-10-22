package com.rootcauseplatform.incident;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal HTTP client for exercising the running application in tests. Sends no credentials
 * unless configured with {@link #withHeaders} or {@link #withCredentials} (see {@code TestAuth}).
 */
public class ApiClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final String baseUrl;
    /** Sent with GET requests. */
    private final Map<String, String> readHeaders;
    /** Sent with POST and PUT requests. */
    private final Map<String, String> writeHeaders;

    public ApiClient(int port) {
        this("http://localhost:" + port, Map.of(), Map.of());
    }

    private ApiClient(String baseUrl, Map<String, String> readHeaders, Map<String, String> writeHeaders) {
        this.baseUrl = baseUrl;
        this.readHeaders = readHeaders;
        this.writeHeaders = writeHeaders;
    }

    /** A client that sends {@code headers} with every request. */
    public ApiClient withHeaders(Map<String, String> headers) {
        return new ApiClient(baseUrl, headers, headers);
    }

    /** A client that sends {@code read} with GET and {@code write} with POST/PUT requests. */
    public ApiClient withCredentials(Map<String, String> read, Map<String, String> write) {
        return new ApiClient(baseUrl, read, write);
    }

    public Response get(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        readHeaders.forEach(request::header);
        return send(request.build());
    }

    public Response post(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        writeHeaders.forEach(request::header);
        return send(request.build());
    }

    public Response putJson(String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)));
        writeHeaders.forEach(request::header);
        return send(request.build());
    }

    public Response postJson(String path, Object body) {
        return postRaw(path, jsonMapper.writeValueAsString(body), "application/json");
    }

    public Response postRaw(String path, String body, String contentType) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        writeHeaders.forEach(request::header);
        return send(request.build());
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

        public List<String> headers(String name) {
            return response.headers().allValues(name);
        }
    }
}
