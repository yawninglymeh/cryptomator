package org.cryptomator.integrationapi;

import org.cryptomator.common.vaults.Vault;
import org.cryptomator.integrations.mount.Mountpoint;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javafx.collections.FXCollections;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public class CleartextPathMapperTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	public void testMapsPathInsideUnlockedVault() throws Exception {
		var mountPath = temporaryDirectory.resolve("vault").toAbsolutePath();
		var cleartextPath = mountPath.resolve("Documents").resolve("notes.txt");
		var ciphertextPath = temporaryDirectory.resolve("ciphertext").resolve("notes.c9r");
		var vault = unlockedVault("vault-1", mountPath, cleartextPath, ciphertextPath);
		var mapper = new CleartextPathMapper(FXCollections.observableArrayList(vault));

		var target = mapper.prepare(cleartextPath);
		Mockito.verify(vault, Mockito.never()).getCiphertextPath(Mockito.any());
		var result = mapper.resolve(target);

		Assertions.assertEquals("vault-1", result.vaultId());
		Assertions.assertEquals(ciphertextPath, result.ciphertextPath());
	}

	@Test
	public void testNormalizesPathBeforeMapping() throws Exception {
		var mountPath = temporaryDirectory.resolve("vault").toAbsolutePath();
		var requestedPath = mountPath.resolve("Documents").resolve("..").resolve("notes.txt");
		var normalizedPath = mountPath.resolve("notes.txt");
		var ciphertextPath = temporaryDirectory.resolve("ciphertext").resolve("notes.c9r");
		var vault = unlockedVault("vault-1", mountPath, normalizedPath, ciphertextPath);
		var mapper = new CleartextPathMapper(FXCollections.observableArrayList(vault));

		var target = mapper.prepare(requestedPath);
		mapper.resolve(target);

		Mockito.verify(vault).getCiphertextPath(normalizedPath);
	}

	@Test
	public void testRejectsRelativePath() {
		var mapper = new CleartextPathMapper(FXCollections.observableArrayList());

		var error = Assertions.assertThrows(PathMappingException.class, () -> mapper.prepare(Path.of("relative.txt")));

		Assertions.assertEquals(PathMappingException.Code.INVALID_PATH, error.code());
	}

	@Test
	public void testRejectsPathOutsideMount() throws IOException {
		var mountPath = temporaryDirectory.resolve("vault").toAbsolutePath();
		var vault = Mockito.mock(Vault.class);
		Mockito.when(vault.isUnlocked()).thenReturn(true);
		Mockito.when(vault.getMountPoint()).thenReturn(Mountpoint.forPath(mountPath));
		var mapper = new CleartextPathMapper(FXCollections.observableArrayList(vault));

		var error = Assertions.assertThrows(PathMappingException.class, () -> mapper.prepare(temporaryDirectory.resolve("outside.txt").toAbsolutePath()));

		Assertions.assertEquals(PathMappingException.Code.NO_UNLOCKED_VAULT, error.code());
		Mockito.verify(vault, Mockito.never()).getCiphertextPath(Mockito.any());
	}

	@Test
	public void testIgnoresLockedAndUriMountedVaults() {
		var lockedVault = Mockito.mock(Vault.class);
		Mockito.when(lockedVault.isUnlocked()).thenReturn(false);
		var uriMountedVault = Mockito.mock(Vault.class);
		Mockito.when(uriMountedVault.isUnlocked()).thenReturn(true);
		Mockito.when(uriMountedVault.getMountPoint()).thenReturn(Mountpoint.forUri(URI.create("https://example.com/vault")));
		var mapper = new CleartextPathMapper(FXCollections.observableArrayList(lockedVault, uriMountedVault));

		var error = Assertions.assertThrows(PathMappingException.class, () -> mapper.prepare(temporaryDirectory.resolve("file.txt").toAbsolutePath()));

		Assertions.assertEquals(PathMappingException.Code.NO_UNLOCKED_VAULT, error.code());
	}

	@Test
	public void testChoosesMostSpecificNestedMount() throws Exception {
		var outerMount = temporaryDirectory.resolve("outer").toAbsolutePath();
		var innerMount = outerMount.resolve("inner");
		var cleartextPath = innerMount.resolve("file.txt");
		var outerVault = unlockedVault("outer", outerMount, cleartextPath, temporaryDirectory.resolve("outer.c9r"));
		var innerCiphertextPath = temporaryDirectory.resolve("inner.c9r");
		var innerVault = unlockedVault("inner", innerMount, cleartextPath, innerCiphertextPath);
		var mapper = new CleartextPathMapper(FXCollections.observableArrayList(outerVault, innerVault));

		var target = mapper.prepare(cleartextPath);
		var result = mapper.resolve(target);

		Assertions.assertEquals("inner", result.vaultId());
		Assertions.assertEquals(innerCiphertextPath, result.ciphertextPath());
		Mockito.verify(outerVault, Mockito.never()).getCiphertextPath(Mockito.any());
	}

	@Test
	public void testListsOnlyUnlockedPathMountedVaults() throws Exception {
		var mountPath = temporaryDirectory.resolve("vault").toAbsolutePath();
		var ciphertextRoot = temporaryDirectory.resolve("ciphertext").toAbsolutePath();
		var unlockedVault = unlockedVault("vault-1", mountPath, mountPath.resolve("file.txt"), ciphertextRoot.resolve("file.c9r"));
		Mockito.when(unlockedVault.getPath()).thenReturn(ciphertextRoot);
		var lockedVault = Mockito.mock(Vault.class);
		Mockito.when(lockedVault.isUnlocked()).thenReturn(false);
		var uriMountedVault = Mockito.mock(Vault.class);
		Mockito.when(uriMountedVault.isUnlocked()).thenReturn(true);
		Mockito.when(uriMountedVault.getMountPoint()).thenReturn(Mountpoint.forUri(URI.create("https://example.com/vault")));
		var mapper = new CleartextPathMapper(FXCollections.observableArrayList(lockedVault, uriMountedVault, unlockedVault));

		var result = mapper.unlockedVaults();

		Assertions.assertEquals(1, result.size());
		Assertions.assertEquals("vault-1", result.getFirst().vaultId());
		Assertions.assertEquals(mountPath, result.getFirst().mountPath());
		Assertions.assertEquals(ciphertextRoot, result.getFirst().ciphertextRootPath());
	}

	private Vault unlockedVault(String id, Path mountPath, Path cleartextPath, Path ciphertextPath) throws IOException {
		var vault = Mockito.mock(Vault.class);
		Mockito.when(vault.isUnlocked()).thenReturn(true);
		Mockito.when(vault.getId()).thenReturn(id);
		Mockito.when(vault.getMountPoint()).thenReturn(Mountpoint.forPath(mountPath));
		Mockito.when(vault.getCiphertextPath(cleartextPath)).thenReturn(ciphertextPath);
		return vault;
	}
}
