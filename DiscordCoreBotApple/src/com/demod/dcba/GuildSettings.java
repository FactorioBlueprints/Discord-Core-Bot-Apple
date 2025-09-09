package com.demod.dcba;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Scanner;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GuildSettings {
	private static final Logger LOGGER = LoggerFactory.getLogger(GuildSettings.class);
	private static final String GUILDS_FILE = "guilds.json";

	private static JSONObject guildsJson;

	public static synchronized JSONObject get(String guildId) {
		if (guildsJson == null) {
			guildsJson = loadGuilds();
		}

		if (guildsJson.has(guildId)) {
			return guildsJson.getJSONObject(guildId);
		} else {
			return new JSONObject();
		}
	}

	private static JSONObject loadGuilds() {
		try (Scanner scanner = new Scanner(new FileInputStream(GUILDS_FILE), "UTF-8")) {
			scanner.useDelimiter("\\A");
			return new JSONObject(scanner.next());
		} catch (JSONException | IOException e) {
			LOGGER.info(GUILDS_FILE + " was not found!");
			return new JSONObject();
		}
	}

	public static synchronized void save(String guildId, JSONObject guildJson) {
		guildsJson.put(guildId, guildJson);

		try (FileWriter fw = new FileWriter(GUILDS_FILE)) {
			fw.write(guildsJson.toString(2));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private GuildSettings() {
	}
}
