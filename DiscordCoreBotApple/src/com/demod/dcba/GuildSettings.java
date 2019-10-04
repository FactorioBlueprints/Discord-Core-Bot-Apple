package com.demod.dcba;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class GuildSettings {
	private static final String GUILDS_FILE = "guilds.json";

	private static ObjectNode guildsJson = null;

	public static synchronized ObjectNode get(String guildId) {
		if (guildsJson == null) {
			guildsJson = loadGuilds();
		}

		if (guildsJson.has(guildId)) {
			return (ObjectNode) guildsJson.path(guildId);
		} else {
			return guildsJson.putObject(guildId);
		}
	}

	private static ObjectNode loadGuilds() {
		ObjectMapper objectMapper = new ObjectMapper();
		try (Scanner scanner = new Scanner(new FileInputStream(GUILDS_FILE), "UTF-8")) {
			scanner.useDelimiter("\\A");
			String string = scanner.next();
			return (ObjectNode) objectMapper.readTree(string);
		} catch (IOException e) {
			System.out.println(GUILDS_FILE + " was not found!");
			return objectMapper.createObjectNode();
		}
	}

	public static synchronized void save() {
		ObjectMapper objectMapper = new ObjectMapper();
		ObjectWriter objectWriter = objectMapper.writerWithDefaultPrettyPrinter();
		try (FileWriter fw = new FileWriter(GUILDS_FILE)) {
			String string = objectWriter.writeValueAsString(guildsJson);
			fw.write(string);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private GuildSettings() {
	}
}
