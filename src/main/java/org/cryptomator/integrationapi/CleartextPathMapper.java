package org.cryptomator.integrationapi;

import org.cryptomator.common.vaults.Vault;
import org.cryptomator.integrations.mount.Mountpoint;
import org.cryptomator.ui.fxapp.FxApplicationScoped;

import javax.inject.Inject;
import javafx.collections.ObservableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.util.Comparator;
import java.util.List;

@FxApplicationScoped
class CleartextPathMapper {

	private final ObservableList<Vault> vaults;

	@Inject
	CleartextPathMapper(ObservableList<Vault> vaults) {
		this.vaults = vaults;
	}

	MappingTarget prepare(Path requestedPath) throws PathMappingException {
		if (!requestedPath.isAbsolute()) {
			throw new PathMappingException(PathMappingException.Code.INVALID_PATH);
		}
		var cleartextPath = requestedPath.normalize();
		var match = vaults.stream() //
				.filter(Vault::isUnlocked) //
				.map(VaultMount::from) //
				.filter(vaultMount -> vaultMount != null && contains(vaultMount.mountPath(), cleartextPath)) //
				.max(Comparator.comparingInt(vaultMount -> vaultMount.mountPath().getNameCount())) //
				.orElseThrow(() -> new PathMappingException(PathMappingException.Code.NO_UNLOCKED_VAULT));
		return new MappingTarget(match.vault(), match.vault().getId(), cleartextPath);
	}

	PathMapping resolve(MappingTarget target) throws IOException {
		return new PathMapping(target.vaultId(), target.vault().getCiphertextPath(target.cleartextPath()));
	}

	List<UnlockedVault> unlockedVaults() {
		return vaults.stream() //
				.filter(Vault::isUnlocked) //
				.map(VaultMount::from) //
				.filter(vaultMount -> vaultMount != null && vaultMount.vault().getPath().isAbsolute()) //
				.map(vaultMount -> new UnlockedVault(vaultMount.vault().getId(), vaultMount.mountPath(), vaultMount.vault().getPath().normalize())) //
				.sorted(Comparator.comparing(vault -> vault.mountPath().toString())) //
				.toList();
	}

	private static boolean contains(Path mountPath, Path cleartextPath) {
		try {
			return cleartextPath.startsWith(mountPath);
		} catch (ProviderMismatchException e) {
			return false;
		}
	}

	private record VaultMount(Vault vault, Path mountPath) {

		private static VaultMount from(Vault vault) {
			if (vault.getMountPoint() instanceof Mountpoint.WithPath mountpoint && mountpoint.path().isAbsolute()) {
				return new VaultMount(vault, mountpoint.path().normalize());
			} else {
				return null;
			}
		}
	}

	record MappingTarget(Vault vault, String vaultId, Path cleartextPath) {}

	record PathMapping(String vaultId, Path ciphertextPath) {}

	record UnlockedVault(String vaultId, Path mountPath, Path ciphertextRootPath) {}
}
