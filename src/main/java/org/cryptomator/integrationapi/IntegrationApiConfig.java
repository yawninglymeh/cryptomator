package org.cryptomator.integrationapi;

import org.cryptomator.common.Environment;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

record IntegrationApiConfig(int port, String token) {

	private static final int MIN_TOKEN_BYTES = 32;
	private static final int MAX_TOKEN_BYTES = 256;

	IntegrationApiConfig {
		if (port < 1 || port > 65_535) {
			throw new IllegalArgumentException("Integration API port must be between 1 and 65535.");
		}
		var tokenBytes = token.getBytes(StandardCharsets.US_ASCII);
		if (tokenBytes.length < MIN_TOKEN_BYTES || tokenBytes.length > MAX_TOKEN_BYTES || !token.matches("[\\x21-\\x7e]+")) {
			throw new IllegalArgumentException("Integration API token must contain 32 to 256 printable ASCII characters without spaces.");
		}
	}

	static Optional<IntegrationApiConfig> from(Environment environment) {
		var port = environment.getIntegrationApiPort();
		var token = environment.getIntegrationApiToken();
		if (port.isEmpty() && token.isEmpty()) {
			return Optional.empty();
		} else if (port.isEmpty() || token.isEmpty()) {
			throw new IllegalArgumentException("Integration API port and token must both be configured.");
		} else {
			return Optional.of(new IntegrationApiConfig(port.getAsInt(), token.orElseThrow()));
		}
	}
}
