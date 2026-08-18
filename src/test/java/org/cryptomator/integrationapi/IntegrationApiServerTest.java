package org.cryptomator.integrationapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class IntegrationApiServerTest {

	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final ObjectMapper JSON = new ObjectMapper();
	private final AtomicInteger resolverCalls = new AtomicInteger();
	private final AtomicInteger vaultListerCalls = new AtomicInteger();
	private final AtomicReference<List<IntegrationApiServer.VaultResult>> listedVaults = new AtomicReference<>();
	private ExecutorService executor;
	private IntegrationApiServer server;
	private HttpClient client;

	@BeforeEach
	public void setUp() throws Exception {
		listedVaults.set(List.of(new IntegrationApiServer.VaultResult("vault-1", "/vault", "/encrypted")));
		executor = Executors.newCachedThreadPool();
		server = new IntegrationApiServer(0, TOKEN, paths -> {
			resolverCalls.incrementAndGet();
			return paths.stream().map(path -> new IntegrationApiServer.MappingResult("mapped", "vault-1", "/encrypted/item.c9r", null)).toList();
		}, () -> {
			vaultListerCalls.incrementAndGet();
			return listedVaults.get();
		}, executor);
		server.start();
		client = HttpClient.newHttpClient();
	}

	@AfterEach
	public void tearDown() {
		server.close();
		executor.shutdownNow();
	}

	@Test
	public void testBindsOnlyToLoopback() {
		Assertions.assertTrue(server.address().getAddress().isLoopbackAddress());
		Assertions.assertEquals("127.0.0.1", server.address().getAddress().getHostAddress());
	}

	@Test
	public void testAuthorizedMappingRequest() throws Exception {
		var response = client.send(requestBuilder().POST(HttpRequest.BodyPublishers.ofString("{\"paths\":[\"/vault/file.txt\"]}")).build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(200, response.statusCode());
		var responseJson = JSON.readTree(response.body());
		Assertions.assertEquals("mapped", responseJson.at("/results/0/status").asText());
		Assertions.assertEquals("vault-1", responseJson.at("/results/0/vaultId").asText());
		Assertions.assertEquals("/encrypted/item.c9r", responseJson.at("/results/0/ciphertextPath").asText());
		Assertions.assertEquals(1, resolverCalls.get());
	}

	@Test
	public void testRejectsWrongToken() throws Exception {
		var response = client.send(requestBuilder("wrong-token").POST(HttpRequest.BodyPublishers.ofString("{\"paths\":[\"/vault/file.txt\"]}")).build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(401, response.statusCode());
		Assertions.assertEquals(0, resolverCalls.get());
	}

	@Test
	public void testRejectsBrowserOrigin() throws Exception {
		var response = client.send(requestBuilder().header("Origin", "https://example.com").POST(HttpRequest.BodyPublishers.ofString("{\"paths\":[\"/vault/file.txt\"]}")).build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(403, response.statusCode());
		Assertions.assertEquals(0, resolverCalls.get());
	}

	@Test
	public void testRejectsGetRequests() throws Exception {
		var response = client.send(requestBuilder().GET().build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(405, response.statusCode());
		Assertions.assertEquals("POST", response.headers().firstValue("Allow").orElseThrow());
	}

	@Test
	public void testRejectsWrongContentType() throws Exception {
		var request = HttpRequest.newBuilder(endpoint()).header("Authorization", "Bearer " + TOKEN).header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{}"));
		var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(415, response.statusCode());
	}

	@Test
	public void testRejectsMalformedJson() throws Exception {
		var response = client.send(requestBuilder().POST(HttpRequest.BodyPublishers.ofString("not-json")).build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(400, response.statusCode());
		Assertions.assertEquals("invalid_json", JSON.readTree(response.body()).path("error").asText());
	}

	@Test
	public void testRejectsNullJsonDocument() throws Exception {
		var response = client.send(requestBuilder().POST(HttpRequest.BodyPublishers.ofString("null")).build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(400, response.statusCode());
		Assertions.assertEquals("paths_required", JSON.readTree(response.body()).path("error").asText());
	}

	@Test
	public void testLimitsBatchSize() throws Exception {
		var body = JSON.writeValueAsString(new IntegrationApiServer.ResolveRequest(Collections.nCopies(IntegrationApiServer.MAX_PATHS + 1, "/vault/file.txt")));
		var response = client.send(requestBuilder().POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(413, response.statusCode());
		Assertions.assertEquals(0, resolverCalls.get());
	}

	@Test
	public void testListsUnlockedVaults() throws Exception {
		var response = client.send(vaultsRequestBuilder().GET().build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(200, response.statusCode());
		var responseJson = JSON.readTree(response.body());
		Assertions.assertEquals("vault-1", responseJson.at("/vaults/0/vaultId").asText());
		Assertions.assertEquals("/vault", responseJson.at("/vaults/0/mountPath").asText());
		Assertions.assertEquals("/encrypted", responseJson.at("/vaults/0/ciphertextRootPath").asText());
		Assertions.assertEquals(1, vaultListerCalls.get());
	}

	@Test
	public void testRejectsVaultListingsAboveProtocolLimit() throws Exception {
		listedVaults.set(Collections.nCopies(IntegrationApiServer.MAX_VAULTS + 1, new IntegrationApiServer.VaultResult("vault", "/vault", "/encrypted")));

		var response = client.send(vaultsRequestBuilder().GET().build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(413, response.statusCode());
		Assertions.assertEquals("too_many_vaults", JSON.readTree(response.body()).path("error").asText());
		Assertions.assertEquals(1, vaultListerCalls.get());
	}

	@Test
	public void testVaultListingRequiresAuthenticationAndGet() throws Exception {
		var unauthorized = client.send(vaultsRequestBuilder("wrong-token").GET().build(), HttpResponse.BodyHandlers.ofString());
		var browserOrigin = client.send(vaultsRequestBuilder().header("Origin", "https://example.com").GET().build(), HttpResponse.BodyHandlers.ofString());
		var wrongMethod = client.send(vaultsRequestBuilder().POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

		Assertions.assertEquals(401, unauthorized.statusCode());
		Assertions.assertEquals(403, browserOrigin.statusCode());
		Assertions.assertEquals(405, wrongMethod.statusCode());
		Assertions.assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElseThrow());
		Assertions.assertEquals(0, vaultListerCalls.get());
	}

	private HttpRequest.Builder requestBuilder() {
		return requestBuilder(TOKEN);
	}

	private HttpRequest.Builder requestBuilder(String token) {
		return HttpRequest.newBuilder(endpoint()).header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
	}

	private HttpRequest.Builder vaultsRequestBuilder() {
		return vaultsRequestBuilder(TOKEN);
	}

	private HttpRequest.Builder vaultsRequestBuilder(String token) {
		return HttpRequest.newBuilder(vaultsEndpoint()).header("Authorization", "Bearer " + token);
	}

	private URI endpoint() {
		return URI.create("http://127.0.0.1:" + server.address().getPort() + "/v1/resolve");
	}

	private URI vaultsEndpoint() {
		return URI.create("http://127.0.0.1:" + server.address().getPort() + "/v1/vaults");
	}
}
