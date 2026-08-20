package dev.tim9h.rcp.media.service.gsmtc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

import dev.tim9h.rcp.event.CcEvent;
import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.media.service.bean.CurrentTrackProperties;
import dev.tim9h.rcp.media.service.lastfm.LastFmWatcher;
import javafx.application.Platform;

@Singleton
public class MediaBridgeClient {

	private static final String MEDIA_BRIDGE_EXE = "MediaBridge.exe";

	private static final String MEDIA_BRIDGE_RESOURCE = "/native/windows/" + MEDIA_BRIDGE_EXE;

	private static final String EVENT_MEDIA_CHANGED = "mediaChanged";

	private static final String EVENT_PLAYBACK_CHANGED = "playbackChanged";

	private static final String EVENT_VOLUME_CHANGED = "volumeChanged";

	private static final String EVENT_COMMAND_RESULT = "commandResult";

	private static final String COMMAND_PREVIOUS = "previous";

	private static final String COMMAND_STOP = "stop";

	private static final String COMMAND_TOGGLE_PLAY_PAUSE = "togglePlayPause";

	private static final String COMMAND_NEXT = "next";

	private static final String COMMAND_VOLUME_UP = "vol+";

	private static final String COMMAND_VOLUME_DOWN = "vol-";

	private static final String COMMAND_TOGGLE_MUTE = "toggleMute";

	private static final String COMMAND_SET_VOLUME = "setVolume";

	@InjectLogger
	private Logger logger;

	@Inject
	private EventManager eventManager;

	@Inject
	private CurrentTrackProperties currentTrack;

	@Inject
	private LastFmWatcher watcher;

	private final Gson gson = new Gson();

	private final AtomicBoolean running = new AtomicBoolean(false);

	private final AtomicLong requestIdCounter = new AtomicLong();

	/*
	 * MediaBridge currently processes commands sequentially and may omit a
	 * correlationId in responses. These queues preserve the matching order.
	 */
	private final ConcurrentLinkedQueue<String> pendingCommandResults = new ConcurrentLinkedQueue<>();

	private final ConcurrentLinkedQueue<String> pendingMediaEvents = new ConcurrentLinkedQueue<>();

	private final ConcurrentLinkedQueue<String> pendingVolumeEvents = new ConcurrentLinkedQueue<>();

	private volatile double lastVolume = 0.5;

	private Process process;

	private Thread readerThread;

	private Thread stderrThread;

	private BufferedReader reader;

	private BufferedWriter writer;

	@Inject
	public MediaBridgeClient(Injector injector) {
		injector.injectMembers(this);
		startMediaBridge();
	}

	private void startMediaBridge() {
		try {
			var executable = extractMediaBridge();
			if (executable == null) {
				logger.error(() -> "Failed to extract " + MEDIA_BRIDGE_EXE);
				return;
			}

			logger.info(() -> "Starting MediaBridge from: " + executable);
			process = new ProcessBuilder(executable.toString()).start();
			reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

			running.set(true);
			readerThread = startDaemonThread(this::readMediaEvents, "MediaBridgeEventReader");
			stderrThread = startDaemonThread(() -> readStreamErrors(process.getErrorStream()),
					"MediaBridgeStderrReader");

			logger.info(() -> "MediaBridge started successfully");
		} catch (IOException e) {
			logger.error(() -> "Failed to start MediaBridge", e);
		}
	}

	private Thread startDaemonThread(Runnable task, String name) {
		var thread = new Thread(task, name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	private Path extractMediaBridge() {
		try {
			var rcpDirectory = Paths.get(System.getProperty("user.home"), "rcp");
			var executable = rcpDirectory.resolve(MEDIA_BRIDGE_EXE);

			Files.createDirectories(rcpDirectory);

			if (Files.notExists(executable)) {
				try (var resource = getClass().getResourceAsStream(MEDIA_BRIDGE_RESOURCE)) {
					if (resource == null) {
						logger.error(() -> MEDIA_BRIDGE_EXE + " not found in resources");
						return null;
					}
					Files.copy(resource, executable, StandardCopyOption.REPLACE_EXISTING);
					logger.info("Extracted " + MEDIA_BRIDGE_EXE + " to " + executable);
				}
			} else {
				logger.info(() -> MEDIA_BRIDGE_EXE + " already exists at: " + executable);
			}

			return executable.toAbsolutePath();
		} catch (IOException e) {
			logger.error(() -> "Error extracting " + MEDIA_BRIDGE_EXE, e);
			return null;
		}
	}

	private void readMediaEvents() {
		try {
			String line;
			while (running.get() && (line = reader.readLine()) != null) {
				processMediaEvent(line);
			}
		} catch (IOException e) {
			if (running.get()) {
				logger.error(() -> "Error reading media events", e);
			}
		} finally {
			if (running.get()) {
				logger.warn(() -> "MediaBridge output stream closed unexpectedly");
				running.set(false);
			}
		}
	}

	private void readStreamErrors(InputStream input) {
		try (var stderr = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			String line;
			while ((line = stderr.readLine()) != null) {
				logger.warn("MediaBridge stderr: " + line);
			}
		} catch (IOException e) {
			if (running.get()) {
				logger.error(() -> "Error reading MediaBridge stderr", e);
			}
		}
	}

	private void processMediaEvent(String jsonLine) {
		try {
			var element = JsonParser.parseString(jsonLine);
			if (!element.isJsonObject()) {
				logger.warn(() -> "Invalid JSON event format: {}" + jsonLine);
				return;
			}

			var json = element.getAsJsonObject();
			logger.debug(() -> "MediaBridge: " + json);

			var eventElement = json.get("event");
			if (eventElement == null || eventElement.isJsonNull()) {
				logger.warn(() -> "Missing 'event' field in JSON: " + jsonLine);
				return;
			}

			var correlationId = getOptionalString(json, "correlationId");
			switch (eventElement.getAsString()) {
			case EVENT_MEDIA_CHANGED -> handleMediaChanged(json, correlationId);
			case EVENT_PLAYBACK_CHANGED -> handlePlaybackChanged(json, correlationId);
			case EVENT_VOLUME_CHANGED -> handleVolumeChanged(json, correlationId);
			case EVENT_COMMAND_RESULT -> handleCommandResult(json, correlationId);
			default -> logger.debug(() -> "Unknown event type: " + eventElement.getAsString());
			}
		} catch (Exception e) {
			logger.error(() -> "Error processing media event: " + jsonLine, e);
		}
	}

	private String getOptionalString(JsonObject json, String property) {
		var value = json.get(property);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}

	private void handleMediaChanged(JsonObject json, String correlationId) {
		String responseId = resolveEventCorrelationId(correlationId, pendingMediaEvents, EVENT_MEDIA_CHANGED);
		try {
			var event = gson.fromJson(json, MediaChangedEvent.class);
			var title = event.title();
			var artist = event.artist();
			var album = event.album();
			var playing = "Playing".equalsIgnoreCase(event.state());

			logger.debug(() -> "Media changed: " + title + " - " + artist);
			updateCurrentTrack(title, artist, album, playing);

			if (responseId != null) {
				eventManager.postResponse(responseId, "success", title, artist, album, playing);
			}
		} catch (Exception e) {
			logger.error(() -> "Error handling mediaChanged event", e);
			postError(responseId, e);
		}
	}

	private void handlePlaybackChanged(JsonObject json, String correlationId) {
		var responseId = resolveEventCorrelationId(correlationId, pendingMediaEvents, EVENT_PLAYBACK_CHANGED);
		try {
			var event = gson.fromJson(json, PlaybackChangedEvent.class);
			var playing = "Playing".equalsIgnoreCase(event.state());

			logger.debug(() -> "Playback changed: " + event.state());
			updatePlaybackState(playing);

			if (responseId != null) {
				eventManager.postResponse(responseId, "success", currentTrack.getTitleProperty().get(),
						currentTrack.getArtistProperty().get(), currentTrack.getAlbumProperty().get(), playing);
			}
		} catch (Exception e) {
			logger.error(() -> "Error handling playbackChanged event", e);
			postError(responseId, e);
		}
	}

	private void handleVolumeChanged(JsonObject json, String correlationId) {
		var responseId = resolveEventCorrelationId(correlationId, pendingVolumeEvents, EVENT_VOLUME_CHANGED);
		try {
			lastVolume = json.get("volume").getAsDouble();
			var muted = json.get("muted").getAsBoolean();

			logger.debug(() -> "Volume changed: " + Math.round(lastVolume * 100) + "%, muted=" + muted);
			if (responseId != null) {
				eventManager.postResponse(responseId, "success", lastVolume, muted);
			}
		} catch (Exception e) {
			logger.error(() -> "Error handling volumeChanged event", e);
			postError(responseId, e);
		}
	}

	private String resolveEventCorrelationId(String suppliedId, ConcurrentLinkedQueue<String> pendingEvents,
			String eventName) {
		if (suppliedId != null) {
			pendingEvents.remove(suppliedId);
			return suppliedId;
		}

		var queuedId = pendingEvents.poll();
		if (queuedId != null) {
			logger.debug(() -> "Matched " + eventName + " to pending request with correlation ID: " + queuedId);
		}
		return queuedId;
	}

	private void handleCommandResult(JsonObject json, String correlationId) {
		var responseId = resolveCommandResultCorrelationId(correlationId);
		try {
			var success = json.get("success").getAsBoolean();
			var command = json.get("command").getAsString();
			logger.debug(() -> "Command '" + command + "' completed: " + success);

			if (responseId == null) {
				logger.debug(() -> "Ignoring untracked command result for " + command);
				return;
			}

			if (!success) {
				removePendingEvent(responseId);
				eventManager.postResponse(responseId, "failed", command);
				return;
			}

			if (COMMAND_STOP.equals(command)) {
				eventManager.postResponse(responseId, "success", currentTrack.getTitleProperty().get(),
						currentTrack.getArtistProperty().get(), currentTrack.getAlbumProperty().get(), false);
				return;
			}

			/*
			 * Media and volume commands were registered before they were sent. Their
			 * corresponding state-change event completes the request.
			 */
			if (!isMediaCommand(command) && !isVolumeCommand(command)) {
				eventManager.postResponse(responseId, "success", command);
			}
		} catch (Exception e) {
			logger.error(() -> "Error handling commandResult", e);
			removePendingEvent(responseId);
			postError(responseId, e);
		}
	}

	private String resolveCommandResultCorrelationId(String suppliedId) {
		if (suppliedId != null) {
			pendingCommandResults.remove(suppliedId);
			return suppliedId;
		}

		var queuedId = pendingCommandResults.poll();
		if (queuedId != null) {
			logger.debug(() -> "Matched commandResult to pending request with correlation ID: " + queuedId);
		}
		return queuedId;
	}

	private void updateCurrentTrack(String title, String artist, String album, boolean playing) {
		Platform.runLater(() -> {
			currentTrack.getTitleProperty().set(title);
			currentTrack.getArtistProperty().set(artist);
			currentTrack.getAlbumProperty().set(album);
			currentTrack.getNowPlayingProperty().set(playing);
			eventManager.post(new CcEvent("np", title, artist, album, playing));
		});
	}

	private void updatePlaybackState(boolean playing) {
		Platform.runLater(() -> {
			currentTrack.getNowPlayingProperty().set(playing);
			eventManager.post(new CcEvent("np", currentTrack.getTitleProperty().get(),
					currentTrack.getArtistProperty().get(), currentTrack.getAlbumProperty().get(), playing));
		});
	}

	private void postError(String correlationId, Exception error) {
		if (correlationId != null) {
			eventManager.postResponse(correlationId, "error", error.getMessage());
		}
	}

	private static boolean isMediaCommand(String command) {
		return COMMAND_NEXT.equals(command) || COMMAND_PREVIOUS.equals(command)
				|| COMMAND_TOGGLE_PLAY_PAUSE.equals(command) || COMMAND_STOP.equals(command);
	}

	private static boolean isVolumeCommand(String command) {
		return COMMAND_VOLUME_UP.equals(command) || COMMAND_VOLUME_DOWN.equals(command)
				|| COMMAND_TOGGLE_MUTE.equals(command) || COMMAND_SET_VOLUME.equals(command);
	}

	private void sendCommand(String command) {
		sendCommand(command, null, null);
	}

	private void sendCommand(String command, Double value) {
		sendCommand(command, value, null);
	}

	private synchronized void sendCommand(String command, Double value, String correlationId) {
		if (!running.get() || writer == null || process == null || !process.isAlive()) {
			logger.warn(() -> "MediaBridge is not running");
			if (correlationId != null) {
				eventManager.postResponse(correlationId, "error", "MediaBridge is not running");
			}
			return;
		}

		var json = new JsonObject();
		json.addProperty("command", command);
		if (value != null) {
			json.addProperty("value", value);
		}
		if (correlationId != null) {
			json.addProperty("correlationId", correlationId);
			registerPendingRequest(command, correlationId);
		}

		try {
			logger.debug(() -> "MediaBridge stdin: " + json);
			writer.write(gson.toJson(json));
			writer.newLine();
			writer.flush();
		} catch (IOException e) {
			removePendingRequest(correlationId);
			eventManager.echoAsync("Error communicating with MediaBridge", e.getMessage());
			logger.error(() -> "Failed to send command to MediaBridge", e);
			postError(correlationId, e);
		}
	}

	private void registerPendingRequest(String command, String correlationId) {
		pendingCommandResults.offer(correlationId);

		// Register before writing: state-change events can arrive before commandResult.
		if (isVolumeCommand(command)) {
			pendingVolumeEvents.offer(correlationId);
		} else if (isMediaCommand(command) && !COMMAND_STOP.equals(command)) {
			pendingMediaEvents.offer(correlationId);
		}

		logger.debug(() -> "Tracking pending request with correlation ID: " + correlationId);
	}

	private void removePendingRequest(String correlationId) {
		if (correlationId == null) {
			return;
		}
		pendingCommandResults.remove(correlationId);
		removePendingEvent(correlationId);
	}

	private void removePendingEvent(String correlationId) {
		if (correlationId == null) {
			return;
		}
		pendingMediaEvents.remove(correlationId);
		pendingVolumeEvents.remove(correlationId);
	}

	public void prevSong() {
		sendMediaCommand(COMMAND_PREVIOUS, null);
	}

	public void prevSongWithResponse(String correlationId) {
		sendMediaCommand(COMMAND_PREVIOUS, correlationId);
	}

	public void stop() {
		sendMediaCommand(COMMAND_STOP, null);
	}

	public void stopWithResponse(String correlationId) {
		sendMediaCommand(COMMAND_STOP, correlationId);
	}

	public void playPause() {
		sendMediaCommand(COMMAND_TOGGLE_PLAY_PAUSE, null);
	}

	public void playPauseWithResponse(String correlationId) {
		sendMediaCommand(COMMAND_TOGGLE_PLAY_PAUSE, correlationId);
	}

	public void nextSong() {
		sendMediaCommand(COMMAND_NEXT, null);
	}

	public void nextSongWithResponse(String correlationId) {
		sendMediaCommand(COMMAND_NEXT, correlationId);
	}

	private void sendMediaCommand(String command, String correlationId) {
		sendCommand(command, null, correlationId);
		watcher.updatePropertiesAsync();
	}

	public void volumeUp() {
		sendCommand(COMMAND_VOLUME_UP);
	}

	public void volumeUpWithResponse(String correlationId) {
		sendCommand(COMMAND_VOLUME_UP, null, correlationId);
	}

	public void volumeDown() {
		sendCommand(COMMAND_VOLUME_DOWN);
	}

	public void volumeDownWithResponse(String correlationId) {
		sendCommand(COMMAND_VOLUME_DOWN, null, correlationId);
	}

	public void toggleMute() {
		sendCommand(COMMAND_TOGGLE_MUTE);
	}

	public void toggleMuteWithResponse(String correlationId) {
		sendCommand(COMMAND_TOGGLE_MUTE, null, correlationId);
	}

	public void setVolume(double volume) {
		sendCommand(COMMAND_SET_VOLUME, volume);
	}

	public String sendCommandWithResponse(String command) {
		return sendCommandWithResponse(command, null);
	}

	public String sendCommandWithResponse(String command, Double value) {
		var correlationId = "media-" + requestIdCounter.incrementAndGet();
		sendCommand(command, value, correlationId);
		return correlationId;
	}

	public synchronized void shutdown() {
		if (!running.getAndSet(false)) {
			return;
		}

		logger.debug(() -> "Shutting down MediaBridge");
		closeQuietly(writer, "writer");

		if (process != null && process.isAlive()) {
			process.destroy();
			try {
				if (!process.waitFor(3, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				logger.error(() -> "Interrupted while waiting for MediaBridge to exit", e);
				process.destroyForcibly();
			}
		}

		closeQuietly(reader, "reader");
		joinThread(readerThread, 2_000);
		joinThread(stderrThread, 500);

		pendingCommandResults.clear();
		pendingMediaEvents.clear();
		pendingVolumeEvents.clear();

		logger.debug(() -> "MediaBridge shutdown complete");
	}

	private void closeQuietly(AutoCloseable closeable, String name) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception e) {
			logger.error(() -> "Error closing MediaBridge " + name, e);
		}
	}

	private void joinThread(Thread thread, long timeoutMillis) {
		if (thread == null || !thread.isAlive()) {
			return;
		}
		try {
			thread.join(timeoutMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			logger.error(() -> "Interrupted while waiting for " + thread.getName(), e);
		}
		if (thread.isAlive()) {
			logger.warn(() -> "Thread " + thread.getName() + " did not terminate; interrupting");
			thread.interrupt();
		}
	}
}