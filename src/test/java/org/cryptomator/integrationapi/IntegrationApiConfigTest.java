package org.cryptomator.integrationapi;

import org.cryptomator.common.Environment;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.OptionalInt;

public class IntegrationApiConfigTest {

	private static final String VALID_TOKEN = "0123456789abcdef0123456789abcdef";

	@Test
	public void testDisabledWhenNoPropertiesAreSet() {
		var environment = Mockito.mock(Environment.class);
		Mockito.when(environment.getIntegrationApiPort()).thenReturn(OptionalInt.empty());
		Mockito.when(environment.getIntegrationApiToken()).thenReturn(Optional.empty());

		Assertions.assertTrue(IntegrationApiConfig.from(environment).isEmpty());
	}

	@Test
	public void testRequiresPortAndTokenTogether() {
		var environment = Mockito.mock(Environment.class);
		Mockito.when(environment.getIntegrationApiPort()).thenReturn(OptionalInt.of(37_821));
		Mockito.when(environment.getIntegrationApiToken()).thenReturn(Optional.empty());

		Assertions.assertThrows(IllegalArgumentException.class, () -> IntegrationApiConfig.from(environment));
	}

	@Test
	public void testAcceptsValidConfiguration() {
		var environment = Mockito.mock(Environment.class);
		Mockito.when(environment.getIntegrationApiPort()).thenReturn(OptionalInt.of(37_821));
		Mockito.when(environment.getIntegrationApiToken()).thenReturn(Optional.of(VALID_TOKEN));

		var config = IntegrationApiConfig.from(environment).orElseThrow();
		Assertions.assertEquals(37_821, config.port());
		Assertions.assertEquals(VALID_TOKEN, config.token());
	}

	@Test
	public void testRejectsShortToken() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new IntegrationApiConfig(37_821, "too-short"));
	}

	@Test
	public void testRejectsInvalidPort() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new IntegrationApiConfig(0, VALID_TOKEN));
	}
}
