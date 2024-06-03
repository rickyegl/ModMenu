package com.terraformersmc.modmenu.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.api.UpdateChannel;
import com.terraformersmc.modmenu.api.UpdateChecker;
import com.terraformersmc.modmenu.config.ModMenuConfig;
import com.terraformersmc.modmenu.util.mod.Mod;
import com.terraformersmc.modmenu.util.mod.ModrinthUpdateInfo;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class UpdateCheckerUtil {
	public static final Logger LOGGER = LoggerFactory.getLogger("Mod Menu/Update Checker");

	private static boolean modrinthApiV2Deprecated = false;

	private static boolean allowsUpdateChecks(Mod mod) {
		return mod.allowsUpdateChecks();
	}

	public static void checkForUpdates() {
		if (!ModMenuConfig.UPDATE_CHECKER.getValue()) {
			return;
		}

		LOGGER.info("Checking mod updates...");
		Util.getMainWorkerExecutor().execute(UpdateCheckerUtil::checkForModrinthUpdates);
		checkForCustomUpdates();
	}

	public static void checkForCustomUpdates() {
		ModMenu.MODS.values().stream().filter(UpdateCheckerUtil::allowsUpdateChecks).forEach(mod -> {
			UpdateChecker updateChecker = mod.getUpdateChecker();

			if (updateChecker == null) {
				return;
			}

			UpdateCheckerThread.run(mod, () -> {
				var update = updateChecker.checkForUpdates();

				if (update == null) {
					return;
				}

				mod.setUpdateInfo(update);
				LOGGER.info("Update available for '{}@{}'", mod.getId(), mod.getVersion());
			});
		});
	}

	public static void checkForModrinthUpdates() {
		if (modrinthApiV2Deprecated) {
			return;
		}

		Map<String, Set<Mod>> modHashes = new HashMap<>();
		new ArrayList<>(ModMenu.MODS.values()).stream().filter(UpdateCheckerUtil::allowsUpdateChecks).filter(mod -> mod.getUpdateChecker() == null).forEach(mod -> {
			String modId = mod.getId();

			try {
				String hash = mod.getSha512Hash();

				if (hash != null) {
					LOGGER.debug("Hash for {} is {}", modId, hash);
					modHashes.putIfAbsent(hash, new HashSet<>());
					modHashes.get(hash).add(mod);
				}
			} catch (IOException e) {
				LOGGER.error("Error getting mod hash for mod {}: ", modId, e);
			}
		});

		String mcVer = SharedConstants.getGameVersion().getName();
		List<String> loaders = ModMenu.runningQuilt ? List.of("fabric", "quilt") : List.of("fabric");

		List<UpdateChannel> updateChannels;
		UpdateChannel preferredChannel = UpdateChannel.getUserPreference();

		if (preferredChannel == UpdateChannel.RELEASE) {
			updateChannels = List.of(UpdateChannel.RELEASE);
		} else if (preferredChannel == UpdateChannel.BETA) {
			updateChannels = List.of(UpdateChannel.BETA, UpdateChannel.RELEASE);
		} else {
			updateChannels = List.of(UpdateChannel.ALPHA, UpdateChannel.BETA, UpdateChannel.RELEASE);
		}

		String body = ModMenu.GSON_MINIFIED.toJson(new LatestVersionsFromHashesBody(modHashes.keySet(), loaders, mcVer, updateChannels));

		LOGGER.debug("Body: {}", body);
		var latestVersionsRequest = HttpRequest.newBuilder()
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.header("Content-Type", "application/json")
			.uri(URI.create("https://api.modrinth.com/v2/version_files/update"));

		try {
			var latestVersionsResponse = HttpUtil.request(latestVersionsRequest, HttpResponse.BodyHandlers.ofString());

			int status = latestVersionsResponse.statusCode();
			LOGGER.debug("Status: {}", status);
			if (status == 410) {
				modrinthApiV2Deprecated = true;
				LOGGER.warn("Modrinth API v2 is deprecated, unable to check for mod updates.");
			} else if (status == 200) {
				JsonObject responseObject = JsonParser.parseString(latestVersionsResponse.body()).getAsJsonObject();
				LOGGER.debug(String.valueOf(responseObject));
				responseObject.asMap().forEach((lookupHash, versionJson) -> {
					var versionObj = versionJson.getAsJsonObject();
					var projectId = versionObj.get("project_id").getAsString();
					var versionType = versionObj.get("version_type").getAsString();
					var versionNumber = versionObj.get("version_number").getAsString();
					var versionId = versionObj.get("id").getAsString();
					var primaryFile = versionObj.get("files").getAsJsonArray().asList().stream()
						.filter(file -> file.getAsJsonObject().get("primary").getAsBoolean()).findFirst();

					if (primaryFile.isEmpty()) {
						return;
					}

					var updateChannel = UpdateCheckerUtil.getUpdateChannel(versionType);
					var versionHash = primaryFile.get().getAsJsonObject().get("hashes").getAsJsonObject().get("sha512").getAsString();

					if (!Objects.equals(versionHash, lookupHash)) {
						// hashes different, there's an update.
						modHashes.get(lookupHash).forEach(mod -> {
							LOGGER.info("Update available for '{}@{}', (-> {})", mod.getId(), mod.getVersion(), versionNumber);
							mod.setUpdateInfo(new ModrinthUpdateInfo(projectId, versionId, versionNumber, updateChannel));
						});
					}
				});
			}
		} catch (IOException | InterruptedException e) {
			LOGGER.error("Error checking for updates: ", e);
		}
	}

	private static UpdateChannel getUpdateChannel(String versionType) {
		try {
			return UpdateChannel.valueOf(versionType.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException | NullPointerException e) {
			return UpdateChannel.RELEASE;
		}
	}

	public static void triggerV2DeprecatedToast() {
		if (modrinthApiV2Deprecated && ModMenuConfig.UPDATE_CHECKER.getValue()) {
			MinecraftClient.getInstance().getToastManager().add(new SystemToast(
					SystemToast.Type.PERIODIC_NOTIFICATION,
					Text.translatable("modmenu.modrinth.v2_deprecated.title"),
					Text.translatable("modmenu.modrinth.v2_deprecated.description")
			));
		}
	}

	public static class LatestVersionsFromHashesBody {
		public Collection<String> hashes;
		public String algorithm = "sha512";
		public Collection<String> loaders;
		@SerializedName("game_versions")
		public Collection<String> gameVersions;
		@SerializedName("version_types")
		public Collection<String> versionTypes;

		public LatestVersionsFromHashesBody(Collection<String> hashes, Collection<String> loaders, String mcVersion, Collection<UpdateChannel> updateChannels) {
			this.hashes = hashes;
			this.loaders = loaders;
			this.gameVersions = Set.of(mcVersion);
			this.versionTypes = updateChannels.stream().map(value -> value.toString().toLowerCase()).toList();
		}
	}
}
