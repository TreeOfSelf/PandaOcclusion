package me.TreeOfSelf.PandaOcclusion;

import java.lang.reflect.Method;
import java.util.UUID;

final class Bedrock {
	private static Object api;
	private static Method isBedrockPlayer;
	private static boolean missing;

	private Bedrock() {
	}

	static Boolean is(UUID uuid) {
		if (missing) return false;
		try {
			if (api == null) {
				Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
				Object instance = apiClass.getMethod("api").invoke(null);
				isBedrockPlayer = apiClass.getMethod("isBedrockPlayer", UUID.class);
				api = instance;
			}
			return (boolean) isBedrockPlayer.invoke(api, uuid);
		} catch (ClassNotFoundException e) {
			missing = true;
			return false;
		} catch (ReflectiveOperationException | RuntimeException e) {
			// GeyserApi.api() throws until Geyser is up
			return null;
		}
	}
}
