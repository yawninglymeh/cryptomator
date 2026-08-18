package org.cryptomator.integrationapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

class IntegrationApiServer implements AutoCloseable {

	static final int MAX_PATHS = 256;
	static final int MAX_VAULTS = 256;
	private static final int MAX_REQUEST_BYTES = 64 * 1024;
	private static final String RESOLVE_PATH = "/v1/resolve";
	private static final String VAULTS_PATH = "/v1/vaults";
	private static final ObjectMapper JSON = new ObjectMapper();

	private final HttpServer server;
	private final String expectedAuthorization;
	private final PathResolver resolver;
	private final VaultLister vaultLister;

	IntegrationApiServer(int port, String token, PathResolver resolver, VaultLister vaultLister, Executor executor) throws IOException {
		var loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
		this.server = HttpServer.create(new InetSocketAddress(loopback, port), 0);
		this.server.setExecutor(executor);
		this.server.createContext(RESOLVE_PATH, this::handleResolve);
		this.server.createContext(VAULTS_PATH, this::handleVaults);
		this.expectedAuthorization = "Bearer " + token;
		this.resolver = resolver;
		this.vaultLister = vaultLister;
	}

	void start() {
		server.start();
	}

	InetSocketAddress address() {
		return server.getAddress();
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handleResolve(HttpExchange exchange) throws IOException {
		try {
			if (rejectInvalidRequest(exchange, RESOLVE_PATH)) {
				return;
			}
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.getResponseHeaders().set("Allow", "POST");
				sendError(exchange, 405, "method_not_allowed");
			} else if (!hasJsonContentType(exchange)) {
				sendError(exchange, 415, "unsupported_media_type");
			} else {
				handleAuthorizedRequest(exchange);
			}
		} finally {
			exchange.close();
		}
	}

	private void handleVaults(HttpExchange exchange) throws IOException {
		try {
			if (rejectInvalidRequest(exchange, VAULTS_PATH)) {
				return;
			}
			if (!"GET".equals(exchange.getRequestMethod())) {
				exchange.getResponseHeaders().set("Allow", "GET");
				sendError(exchange, 405, "method_not_allowed");
			} else {
				handleAuthorizedVaultListing(exchange);
			}
		} finally {
			exchange.close();
		}
	}

	private boolean rejectInvalidRequest(HttpExchange exchange, String expectedPath) throws IOException {
		if (!expectedPath.equals(exchange.getRequestURI().getPath())) {
			sendError(exchange, 404, "not_found");
			return true;
		} else if (!isValidHost(exchange)) {
			sendError(exchange, 400, "invalid_host");
			return true;
		} else if (exchange.getRequestHeaders().containsKey("Origin")) {
			sendError(exchange, 403, "browser_origin_forbidden");
			return true;
		} else if (!isAuthorized(exchange)) {
			exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
			sendError(exchange, 401, "unauthorized");
			return true;
		} else {
			return false;
		}
	}

	private void handleAuthorizedRequest(HttpExchange exchange) throws IOException {
		try {
			var body = readRequestBody(exchange);
			var request = JSON.readValue(body, ResolveRequest.class);
			if (request == null || request.paths() == null || request.paths().isEmpty()) {
				sendError(exchange, 400, "paths_required");
			} else if (request.paths().size() > MAX_PATHS) {
				sendError(exchange, 413, "too_many_paths");
			} else {
				var results = resolver.resolve(request.paths());
				sendJson(exchange, 200, new ResolveResponse(results));
			}
		} catch (RequestTooLargeException e) {
			sendError(exchange, 413, "request_too_large");
		} catch (JsonProcessingException e) {
			sendError(exchange, 400, "invalid_json");
		} catch (TimeoutException | RejectedExecutionException e) {
			sendError(exchange, 503, "temporarily_unavailable");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			sendError(exchange, 503, "temporarily_unavailable");
		} catch (Exception e) {
			sendError(exchange, 500, "mapping_failed");
		}
	}

	private void handleAuthorizedVaultListing(HttpExchange exchange) throws IOException {
		try {
			var vaults = vaultLister.list();
			if (vaults.size() > MAX_VAULTS) {
				sendError(exchange, 413, "too_many_vaults");
			} else {
				sendJson(exchange, 200, new VaultsResponse(vaults));
			}
		} catch (TimeoutException | RejectedExecutionException e) {
			sendError(exchange, 503, "temporarily_unavailable");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			sendError(exchange, 503, "temporarily_unavailable");
		} catch (Exception e) {
			sendError(exchange, 500, "vault_listing_failed");
		}
	}

	private byte[] readRequestBody(HttpExchange exchange) throws IOException, RequestTooLargeException {
		var contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
		if (contentLength != null) {
			try {
				if (Long.parseLong(contentLength) > MAX_REQUEST_BYTES) {
					throw new RequestTooLargeException();
				}
			} catch (NumberFormatException e) {
				throw new IOException("Invalid Content-Length header.");
			}
		}
		var body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
		if (body.length > MAX_REQUEST_BYTES) {
			throw new RequestTooLargeException();
		}
		return body;
	}

	private boolean isValidHost(HttpExchange exchange) {
		var host = exchange.getRequestHeaders().getFirst("Host");
		var port = server.getAddress().getPort();
		return ("127.0.0.1:" + port).equalsIgnoreCase(host) || ("localhost:" + port).equalsIgnoreCase(host);
	}

	private boolean isAuthorized(HttpExchange exchange) {
		var authorization = exchange.getRequestHeaders().getFirst("Authorization");
		return authorization != null && MessageDigest.isEqual(expectedAuthorization.getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8));
	}

	private boolean hasJsonContentType(HttpExchange exchange) {
		var contentType = exchange.getRequestHeaders().getFirst("Content-Type");
		return contentType != null && "application/json".equals(contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT));
	}

	private void sendError(HttpExchange exchange, int status, String error) throws IOException {
		sendJson(exchange, status, new ErrorResponse(error));
	}

	private void sendJson(HttpExchange exchange, int status, Object response) throws IOException {
		var responseBytes = JSON.writeValueAsBytes(response);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		exchange.sendResponseHeaders(status, responseBytes.length);
		exchange.getResponseBody().write(responseBytes);
	}

	@FunctionalInterface
	interface PathResolver {

		List<MappingResult> resolve(List<String> paths) throws Exception;
	}

	@FunctionalInterface
	interface VaultLister {

		List<VaultResult> list() throws Exception;
	}

	record ResolveRequest(List<String> paths) {}

	record ResolveResponse(List<MappingResult> results) {}

	record VaultsResponse(List<VaultResult> vaults) {}

	record VaultResult(String vaultId, String mountPath, String ciphertextRootPath) {}

	record MappingResult(String status, String vaultId, String ciphertextPath, String error) {

		static MappingResult mapped(CleartextPathMapper.PathMapping mapping) {
			return new MappingResult("mapped", mapping.vaultId(), mapping.ciphertextPath().toString(), null);
		}

		static MappingResult error(String error) {
			return new MappingResult("error", null, null, error);
		}
	}

	record ErrorResponse(String error) {}

	private static class RequestTooLargeException extends Exception {}
}
