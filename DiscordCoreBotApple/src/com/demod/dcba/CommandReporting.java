package com.demod.dcba;

import java.awt.Color;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedHashMultiset;
import com.google.common.collect.Multiset;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.MessageEmbed.Field;

public class CommandReporting {
	public static class ExceptionWithBlame {
		private final Exception e;
		private final Optional<String> blame;

		public ExceptionWithBlame(Exception e, Optional<String> blame) {
			this.e = e;
			this.blame = blame;
		}

		public Optional<String> getBlame() {
			return blame;
		}

		public Exception getException() {
			return e;
		}
	}

	public static enum Level {
		INFO(Color.gray), WARNING(Color.orange), ERROR(Color.red), ATTENTION(Color.green), DEBUG(Color.magenta);

		private final Color color;

		private Level(Color color) {
			this.color = color;
		}

		public Color getColor() {
			return color;
		}
	}

	private final String author;
	private final String authorIconURL;
	private final Instant commandStart;

	private String command;
	private String imageURL;
	private Level level = Level.INFO;
	private final List<Message> replies = new ArrayList<>();
	private final List<String> warnings = new ArrayList<>();
	private final List<String> debugs = new ArrayList<>();
	private final List<ExceptionWithBlame> exceptions = new ArrayList<>();
	private final List<Field> fields = new ArrayList<>();
	private final List<String> replyImageAttachments = new ArrayList<>();
	private final List<String> replyFileAttachments = new ArrayList<>();
	private boolean suppressed;

	public CommandReporting(String author, String authorIconURL, Instant commandStart) {
		this.author = author;
		this.authorIconURL = authorIconURL;
		this.commandStart = commandStart;
	}

	public CommandReporting(String author, String authorIconURL, String command, Instant commandStart) {
		this(author, authorIconURL, commandStart);
		this.command = command;
	}

	public void addDebug(String message) {
		debugs.add(message);
		elevateLevel(Level.DEBUG);
	}

	public void addException(Exception e) {
		addException(e, null);
	}

	public synchronized void addException(Exception e, String blame) {
		exceptions.add(new ExceptionWithBlame(e, Optional.ofNullable(blame)));
		elevateLevel(Level.ERROR);
	}

	public void addField(Field field) {
		fields.add(field);
	}

	public void addReply(Message message) {
		replies.add(message);
		for (MessageEmbed embed : message.getEmbeds()) {
			if (embed.getImage() != null) {
				replyImageAttachments.add(embed.getImage().getUrl());
			}
		}
		for (Attachment attachment : message.getAttachments()) {
			if (attachment.isImage()) {
				replyImageAttachments.add(attachment.getUrl());
			} else {
				replyFileAttachments.add(attachment.getUrl());
			}
		}
	}

	public void addWarning(String message) {
		warnings.add(message);
		elevateLevel(Level.WARNING);
	}

	public void suppress() {
		suppressed = true;
	}

	public List<MessageEmbed> createEmbeds() {
		if (suppressed) {
			return ImmutableList.of();
		}
		
		EmbedBuilder builder = new EmbedBuilder();
		builder.setAuthor(author, null, authorIconURL);
		if (commandStart != null) {
			builder.setTimestamp(commandStart);
		}

		if (level != Level.INFO) {
			builder.setColor(level.getColor());
		}

		if (command != null) {
			builder.addField("Command", limitContent(250, command), false);
		}

		if (commandStart != null) {
			Duration responseTime = Duration.between(commandStart, Instant.now());
			builder.addField("Response Time", responseTime.toMillis() + "ms", true);
		}

		for (Field field : fields) {
			builder.addField(field);
		}

		if (!warnings.isEmpty()) {
			builder.addField("Warnings", limitContent(1000, joinUnique(warnings)), true);
		}

		if (!debugs.isEmpty()) {
			builder.addField("Debug", limitContent(1000, joinUnique(debugs)), true);
		}

		if (!exceptions.isEmpty()) {
			List<String> exceptionMessages = exceptions
					.stream().map(e -> e.getException().getClass().getSimpleName() + ": "
							+ e.getException().getMessage() + e.getBlame().map(s -> " (" + s + ")").orElse(""))
					.collect(Collectors.toList());
			builder.addField("Exceptions", limitContent(1000, joinUnique(exceptionMessages)), true);
			builder.addField("Stack Trace", limitContent(1000, exceptions.stream().map(e -> {
				try (StringWriter sw = new StringWriter(); PrintWriter pw = new PrintWriter(sw)) {
					if (e.getBlame().isPresent()) {
						pw.print("(" + e.getBlame().get() + ") ");
					}
					e.getException().printStackTrace(pw);
					pw.flush();
					return sw.toString();
				} catch (IOException e1) {
					e1.printStackTrace();
					return "";
				}
			}).distinct().collect(Collectors.joining("\n\n"))), false);
		}

		if (!replies.isEmpty()) {
			builder.addField("Replies",
					replies.stream().map(m -> "[Message](" + m.getJumpUrl() + ")").collect(Collectors.joining("\n")),
					false);
		}

		if (imageURL != null) {
			builder.setImage(imageURL);
		} else if (replyImageAttachments.size() == 1) {
			builder.setImage(replyImageAttachments.remove(0));
		}

		if (builder.isValidLength()) {
			return ImmutableList.of(builder.build());
		}

		// Break up the fields to create multiple valid embeds
		List<MessageEmbed> embeds = new ArrayList<>();
		while (true) {
			List<Field> excessFields = new ArrayList<>();
			while (!builder.isValidLength()) {
				excessFields.add(0, builder.getFields().remove(builder.getFields().size() - 1));
			}
			embeds.add(builder.build());

			builder = new EmbedBuilder();
			excessFields.forEach(builder::addField);

			if (builder.isValidLength()) {
				embeds.add(builder.build());
				break;
			}
		}
		return embeds;
	}

	public List<String> createURLList() {
		if (replyFileAttachments.isEmpty() && replyImageAttachments.isEmpty()) {
			return ImmutableList.of();
		}
		List<String> ret = new ArrayList<>();
		ret.addAll(replyImageAttachments);
		ret.addAll(replyFileAttachments);
		return ret;
	}

	private void elevateLevel(Level level) {
		if (this.level.ordinal() < level.ordinal()) {
			this.level = level;
		}
	}

	public List<Exception> getExceptions() {
		return exceptions.stream().map(ExceptionWithBlame::getException).collect(Collectors.toList());
	}

	public List<ExceptionWithBlame> getExceptionsWithBlame() {
		return exceptions;
	}

	public Level getLevel() {
		return level;
	}

	private String joinUnique(List<String> messages) {
		Multiset<String> unique = LinkedHashMultiset.create(messages);
		return unique.entrySet().stream()
				.map(e -> e.getElement() + (e.getCount() > 1 ? " *(**" + e.getCount() + "** times)*" : ""))
				.collect(Collectors.joining("\n"));
	}

	private String limitContent(int maxLength, String content) {
		if (content.length() <= maxLength) {
			return content;
		} else {
			return content.substring(0, maxLength - 3) + "...";
		}
	}

	public void setAttention() {
		elevateLevel(Level.ATTENTION);
	}

	public void setCommand(String command) {
		this.command = command;
	}

	public void setImageURL(String imageURL) {
		this.imageURL = imageURL;
	}

	public void setLevel(Level level) {
		this.level = level;
	}
}