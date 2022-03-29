package com.demod.dcba;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.json.JSONException;
import org.json.JSONObject;

import com.demod.dcba.SlashCommandDefinition.Restriction;
import com.google.common.util.concurrent.AbstractIdleService;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.ChannelType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.PrivateChannel;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveAllEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

public class DiscordBot extends AbstractIdleService {

	private static final String COMMAND_INFO = "info";

	private final Map<String, MessageCommandDefinition> commandMessage = new LinkedHashMap<>();
	private final Map<String, SlashCommandDefinition> commandSlash = new LinkedHashMap<>();
	private final Map<String, SlashCommandDefinition> commandLegacy = new LinkedHashMap<>();

	private final InfoDefinition info = new InfoDefinition();

	private final ExecutorService commandService = Executors.newSingleThreadExecutor();

	private Optional<String> commandPrefix = Optional.empty();
	private Optional<TextWatcher> textWatcher = Optional.empty();
	private Optional<ReactionWatcher> reactionWatcher = Optional.empty();
	private boolean ignorePrivateChannels = false;

	private ExceptionHandler exceptionHandler;

	private final JSONObject configJson;

	private JDA jda;

	private final LocalDateTime botStarted = LocalDateTime.now();

	private Function<JDABuilder, JDABuilder> customSetup = null;

	DiscordBot() {
		configJson = loadConfig();

		exceptionHandler = new ExceptionHandler() {

			@Override
			public void handleMessageCommandException(MessageCommandDefinition command, MessageCommandEvent event,
					Exception e) {
				e.printStackTrace();
				event.reply("Unhandled Error: [" + e.getClass().getSimpleName() + "] "
						+ ((e.getMessage() != null) ? e.getMessage() : ""));
			}

			@Override
			public void handleSlashCommandException(SlashCommandDefinition command, SlashCommandEvent event,
					Exception e) {
				e.printStackTrace();
				event.reply("Unhandled Error: [" + e.getClass().getSimpleName() + "] "
						+ ((e.getMessage() != null) ? e.getMessage() : ""));
			}
		};
	}

	public void addCommand(MessageCommandDefinition command) {
		commandMessage.put(command.getName(), command);
	}

	public void addCommand(SlashCommandDefinition command) {
		commandSlash.put(command.getPath(), command);
		command.getLegacies().forEach(l -> commandLegacy.put(l.toLowerCase(), command));
	}

	// Hold my beer
	@SuppressWarnings("unchecked")
	private void buildUpdateCommands(CommandListUpdateAction updateCommands) {
		Map<String, Object> root = new LinkedHashMap<>();

		for (SlashCommandDefinition command : commandSlash.values()) {
			String[] pathSplit = command.getPath().split("/");
			Map<String, Object> group = root;
			for (int i = 0; i < pathSplit.length; i++) {
				String name = pathSplit[i];
				if (i == pathSplit.length - 1) {
					group.put(name, command);
				} else {
					Object subGroup = group.get(name);
					if (subGroup == null) {
						group.put(name, subGroup = new LinkedHashMap<String, Object>());
					}
					group = (Map<String, Object>) subGroup;
				}
			}
		}

		for (Entry<String, Object> rootEntry : root.entrySet()) {
			SlashCommandData commandData;
			if (rootEntry.getValue() instanceof SlashCommandDefinition) {
				SlashCommandDefinition commandDefinition = (SlashCommandDefinition) rootEntry.getValue();
				commandData = Commands.slash(rootEntry.getKey(), commandDefinition.getDescription());
				for (SlashCommandOptionDefinition option : commandDefinition.getOptions()) {
					commandData = commandData.addOption(option.getType(), option.getName(), option.getDescription(),
							option.isRequired());
				}
			} else {
				Map<String, Object> sub = (Map<String, Object>) rootEntry.getValue();
				commandData = Commands.slash(rootEntry.getKey(),
						sub.keySet().stream().collect(Collectors.joining(", ")));
				for (Entry<String, Object> subEntry : sub.entrySet()) {
					if (subEntry.getValue() instanceof SlashCommandDefinition) {
						SlashCommandDefinition commandDefinition = (SlashCommandDefinition) subEntry.getValue();
						SubcommandData subcommandData = new SubcommandData(subEntry.getKey(),
								commandDefinition.getDescription());
						for (SlashCommandOptionDefinition option : commandDefinition.getOptions()) {
							subcommandData = subcommandData.addOption(option.getType(), option.getName(),
									option.getDescription(), option.isRequired());
						}
						commandData = commandData.addSubcommands(subcommandData);
					} else {
						Map<String, SlashCommandDefinition> subSub = (Map<String, SlashCommandDefinition>) subEntry
								.getValue();
						SubcommandGroupData subcommandGroupData = new SubcommandGroupData(subEntry.getKey(),
								subSub.keySet().stream().collect(Collectors.joining(", ")));
						for (Entry<String, SlashCommandDefinition> subSubEntry : subSub.entrySet()) {
							SubcommandData subcommandData = new SubcommandData(subSubEntry.getKey(),
									subSubEntry.getValue().getDescription());
							for (SlashCommandOptionDefinition option : subSubEntry.getValue().getOptions()) {
								subcommandData = subcommandData.addOption(option.getType(), option.getName(),
										option.getDescription(), option.isRequired());
							}
							subcommandGroupData = subcommandGroupData.addSubcommands(subcommandData);
						}
						commandData = commandData.addSubcommandGroups(subcommandGroupData);
					}
				}
			}
			updateCommands = updateCommands.addCommands(commandData);
		}

		for (MessageCommandDefinition commandDefinition : commandMessage.values()) {
			CommandData commandData = Commands.message(commandDefinition.getName());
			updateCommands = updateCommands.addCommands(commandData);
		}
	}

	private boolean checkPermitted(MessageChannel channel, Member member, SlashCommandDefinition commandDefinition) {
		boolean isPermitted = true;
		if (commandDefinition.hasRestriction(Restriction.ADMIN_ONLY)) {
			if (member != null) {
				isPermitted = member.hasPermission(Permission.ADMINISTRATOR);
			} else {
				isPermitted = false;
			}
		}

		if (channel.getType() != ChannelType.TEXT && commandDefinition.hasRestriction(Restriction.GUILD_CHANNEL_ONLY)) {
			isPermitted = false;
		}

		if (channel.getType() != ChannelType.PRIVATE
				&& commandDefinition.hasRestriction(Restriction.PRIVATE_CHANNEL_ONLY)) {
			isPermitted = false;
		}

		return isPermitted;
	}

	private SlashCommandDefinition createCommandInfo() {
		return new SlashCommandDefinition(COMMAND_INFO, "Shows information about this bot.", new SlashCommandHandler() {
			@Override
			public void handleCommand(SlashCommandEvent event) throws Exception {
				EmbedBuilder builder = new EmbedBuilder();
				info.getSupportMessage().ifPresent(s -> builder.addField("Support", s, false));
				info.getBotName().ifPresent(n -> builder.addField("Bot Name", n, true));
				info.getVersion().ifPresent(v -> builder.addField("Bot Version", v, true));
				if (info.isAllowInvite()) {
					builder.addField("Server Invite", "[Link](" + jda.getInviteUrl(info.getInvitePermissions()) + ")",
							true);
				}
				builder.addField("Technologies", info.getTechnologies().stream().collect(Collectors.joining("\n")),
						false);
				for (String group : info.getCredits().keySet()) {
					builder.addField(group, info.getCredits().get(group).stream().collect(Collectors.joining("\n")),
							false);
				}

				int guildCount = jda.getGuilds().size();
				String uptimeFormatted = getDurationFormatted(botStarted, LocalDateTime.now());
				long ping = jda.getGatewayPing();
				builder.addField("Total Servers", guildCount + " servers", true);
				builder.addField("Uptime", uptimeFormatted, true);
				builder.addField("Ping to Discord", ping + " ms", true);
				for (Entry<String, String> entry : info.getCustomFields()) {
					builder.addField(entry.getKey(), entry.getValue(), true);
				}

				event.replyEmbed(builder.build());
			}
		});
	}

	public Optional<String> getCommandPrefix() {
		return commandPrefix;
	}

	private String getDurationFormatted(LocalDateTime then, LocalDateTime now) {
		List<ChronoUnit> units = Arrays.asList(//
				ChronoUnit.YEARS, //
				ChronoUnit.MONTHS, //
				ChronoUnit.DAYS, //
				ChronoUnit.HOURS, //
				ChronoUnit.DAYS, //
				ChronoUnit.MINUTES, //
				ChronoUnit.SECONDS);

		List<String> result = new ArrayList<>();
		LocalDateTime accumulator = then;
		for (ChronoUnit chronoUnit : units) {
			long duration = chronoUnit.between(accumulator, now);
			if (duration > 0) {
				String label = chronoUnit.name().toLowerCase().substring(0, chronoUnit.name().length() - 1);
				result.add(duration + " " + label + (duration > 1 ? "s" : ""));
				accumulator = accumulator.plus(chronoUnit.getDuration().multipliedBy(duration));
			}
		}

		return result.stream().collect(Collectors.joining(", "));
	}

	public InfoDefinition getInfo() {
		return info;
	}

	public JDA getJDA() {
		return jda;
	}

	private Optional<String> getOldCommandPrefix(MessageReceivedEvent event) {
		Optional<String> effectivePrefix = commandPrefix;
		if (event.getChannelType() == ChannelType.TEXT) {
			JSONObject guildJson = GuildSettings.get(event.getGuild().getId());
			if (guildJson.has("prefix")) {
				effectivePrefix = Optional.of(guildJson.getString("prefix"));
			}
		}
		return effectivePrefix;
	}

	void initialize() {
		if (!commandSlash.containsKey(COMMAND_INFO)) {
			addCommand(createCommandInfo());
		}
	}

	private JSONObject loadConfig() {
		try (Scanner scanner = new Scanner(new FileInputStream("config.json"), "UTF-8")) {
			scanner.useDelimiter("\\A");
			return new JSONObject(scanner.next()).getJSONObject("discord");
		} catch (JSONException | IOException e) {
			e.printStackTrace();
			System.err.println("################################");
			System.err.println("Missing or bad config.json file!");
			System.err.println("################################");
			System.exit(0);
			return null;
		}
	}

	public void setCommandPrefix(Optional<String> commandPrefix) {
		this.commandPrefix = commandPrefix;
	}

	public void setCustomSetup(Function<JDABuilder, JDABuilder> customSetup) {
		this.customSetup = customSetup;
	}

	public void setExceptionHandler(ExceptionHandler exceptionHandler) {
		this.exceptionHandler = exceptionHandler;
	}

	public void setIgnorePrivateChannels(boolean ignorePrivateChannels) {
		this.ignorePrivateChannels = ignorePrivateChannels;
	}

	public void setReactionWatcher(Optional<ReactionWatcher> reactionWatcher) {
		this.reactionWatcher = reactionWatcher;
	}

	public void setTextWatcher(Optional<TextWatcher> textWatcher) {
		this.textWatcher = textWatcher;
	}

	@Override
	protected void shutDown() {
		jda.shutdown();
	}

	@Override
	protected void startUp() throws Exception {
		info.addTechnology("[DCBA](https://github.com/demodude4u/Discord-Core-Bot-Apple)", Optional.empty(),
				"Discord Core Bot Apple");
		info.addTechnology("[JDA](https://github.com/DV8FromTheWorld/JDA)", Optional.of("5.0 alpha"),
				"Java Discord API");

		if (configJson.has("command_prefix")) {
			setCommandPrefix(Optional.of(configJson.getString("command_prefix")));
		}

		JDABuilder builder = JDABuilder.createDefault(configJson.getString("bot_token"))//
				.setEnableShutdownHook(false)//
				.addEventListeners(new ListenerAdapter() {
					@Override
					public void onMessageContextInteraction(MessageContextInteractionEvent event) {
						MessageCommandDefinition commandDefinition = commandMessage.get(event.getName());
						boolean ephemeral = commandDefinition
								.hasRestriction(MessageCommandDefinition.Restriction.EPHEMERAL);

						InteractionHook hook = event.deferReply(ephemeral).complete();
						MessageCommandEvent commandEvent = new MessageCommandEvent(event, hook, ephemeral);

						commandService.submit(() -> {
							try {
								commandDefinition.getHandler().handleCommand(commandEvent);
							} catch (Exception e) {
								exceptionHandler.handleMessageCommandException(commandDefinition, commandEvent, e);
							} finally {
								if (!commandEvent.hasReplied()) {
									hook.deleteOriginal().complete();
								}
							}
						});
					}

					@Override
					public void onMessageDelete(MessageDeleteEvent event) {
						if (textWatcher.isPresent()) {
							textWatcher.get().deletedMessage(event);
						}
					}

					@Override
					public void onMessageReactionAdd(MessageReactionAddEvent event) {
						if (reactionWatcher.isPresent()) {
							reactionWatcher.get().seenReaction(event);
						}
					}

					@Override
					public void onMessageReactionRemove(MessageReactionRemoveEvent event) {
						if (reactionWatcher.isPresent()) {
							reactionWatcher.get().seenReactionRemoved(event);
						}
					}

					@Override
					public void onMessageReactionRemoveAll(MessageReactionRemoveAllEvent event) {
						if (reactionWatcher.isPresent()) {
							reactionWatcher.get().seenAllReactionRemoved(event);
						}
					}

					@Override
					public void onMessageReceived(MessageReceivedEvent event) {
						if (textWatcher.isPresent()) {
							textWatcher.get().seenMessage(event);
						}

						Message message = event.getMessage();
						MessageChannel channel = message.getChannel();
						String rawContent = message.getContentRaw().trim();

						String mentionMe = "<@!" + event.getJDA().getSelfUser().getId() + ">";
						// String mentionMe = event.getJDA().getSelfUser().getAsMention();

						boolean isPrivateChannel = channel instanceof PrivateChannel;
						if (isPrivateChannel && ignorePrivateChannels) {
							return;
						}

						boolean startsWithMentionMe = message.getMentionedUsers().stream()
								.anyMatch(u -> u.getIdLong() == event.getJDA().getSelfUser().getIdLong())
								&& rawContent.startsWith(mentionMe);
						if (startsWithMentionMe) {
							rawContent = rawContent.substring(mentionMe.length()).trim();
						}

						Optional<String> effectivePrefix = getOldCommandPrefix(event);
						boolean startsWithCommandPrefix = effectivePrefix.isPresent()
								&& rawContent.startsWith(effectivePrefix.get());
						if (startsWithCommandPrefix) {
							rawContent = rawContent.substring(effectivePrefix.get().length()).trim();
						}

						if (!event.getAuthor().isBot()
								&& (isPrivateChannel || startsWithMentionMe || startsWithCommandPrefix)) {
							String[] split = rawContent.split("\\s+");
							if (split.length > 0) {
								String command = split[0];
								SlashCommandDefinition commandDefinition = commandLegacy.get(command.toLowerCase());
								if (commandDefinition != null) {
									boolean isPermitted = checkPermitted(channel, event.getMember(), commandDefinition);

									if (isPermitted) {
										event.getChannel()
												.sendMessageEmbeds(new EmbedBuilder()
														.appendDescription("Please use /"
																+ commandDefinition.getPath().replace("/", " "))
														.build())
												.complete();
									}
								}
							}
						}
					}

					@Override
					public void onMessageUpdate(MessageUpdateEvent event) {
						if (textWatcher.isPresent()) {
							textWatcher.get().editedMessage(event);
						}
					}

					@Override
					public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
						SlashCommandDefinition commandDefinition = commandSlash.get(event.getCommandPath());
						boolean ephemeral = commandDefinition
								.hasRestriction(SlashCommandDefinition.Restriction.EPHEMERAL);

						InteractionHook hook = event.deferReply(ephemeral).complete();
						SlashCommandEvent commandEvent = new SlashCommandEvent(event, hook, ephemeral);

						commandService.submit(() -> {
							try {
								commandDefinition.getHandler().handleCommand(commandEvent);
							} catch (Exception e) {
								exceptionHandler.handleSlashCommandException(commandDefinition, commandEvent, e);
							} finally {
								if (!commandEvent.hasReplied()) {
									hook.deleteOriginal().complete();
								}
							}
						});
					}
				});
		if (customSetup != null) {
			builder = customSetup.apply(builder);
		}
		jda = builder.build().awaitReady();
		jda.setRequiredScopes("bot", "applications.commands");

		if (configJson.has("debug_guild_commands")) {
			String guildId = configJson.getString("debug_guild_commands");
			Guild guild = jda.getGuildById(guildId);
			CommandListUpdateAction updateCommands = guild.updateCommands();
			buildUpdateCommands(updateCommands);
			updateCommands.queue();
		}

		CommandListUpdateAction updateCommands = jda.updateCommands();
		buildUpdateCommands(updateCommands);
		updateCommands.queue();
	}
}
