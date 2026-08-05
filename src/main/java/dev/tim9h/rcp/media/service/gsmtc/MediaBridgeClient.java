package dev.tim9h.rcp.media.service.gsmtc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
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

	@InjectLogger
	private Logger logger;

	@Inject
	private EventManager eventManager;

	@Inject
	private CurrentTrackProperties currentTrack;

	private double lastVolume = 0.5;

	private Process process;

	private Thread readerThread;

	private Thread stderrThread;

	private BufferedReader reader;

	private BufferedWriter writer;

	private final Gson gson = new Gson();

	private final AtomicBoolean running = new AtomicBoolean(false);

	@Inject
	private LastFmWatcher watcher;

	// Track pending requests in order: queue of correlationIds
	private final ConcurrentLinkedQueue<String> pendingRequestQueue = new ConcurrentLinkedQueue<>();

	// Track pending command results that are waiting for mediaChanged event
	private final ConcurrentHashMap<String, String> pendingMediaCommands = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, String> pendingVolumeCommands = new ConcurrentHashMap<>();

	private final AtomicLong requestIdCounter = new AtomicLong(0);

	@Inject
	public MediaBridgeClient(Injector injector) {
		injector.injectMembers(this);
		startMediaBridge();
	}

	private void startMediaBridge() {
		try {
			var exePath = extractMediaBridge();
			if (exePath == null) {
				logger.error(() -> "Failed to extract MediaBridge.exe");
				return;
			}

			logger.info(() -> "Starting MediaBridge from: " + exePath);
			process = new ProcessBuilder(exePath).start();
			reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

			running.set(true);
			readerThread = new Thread(this::readMediaEvents, "MediaBridgeEventReader");
			readerThread.setDaemon(true);
			readerThread.start();

			// capture stderr to the log to aid debugging
			var errStream = process.getErrorStream();
			stderrThread = new Thread(() -> readStreamErrors(errStream), "MediaBridgeStderrReader");
			stderrThread.setDaemon(true);
			stderrThread.start();

			logger.info(() -> "MediaBridge started successfully");
		} catch (IOException e) {
			logger.error(() -> "Failed to start MediaBridge", e);
		}
	}

	private String extractMediaBridge() {
		try {
			var homeDir = System.getProperty("user.home");
			var rcpDir = Paths.get(homeDir, "rcp");
			var exeFile = rcpDir.resolve(MEDIA_BRIDGE_EXE);

			// Create rcp directory if it doesn't exist
			if (!Files.exists(rcpDir)) {
				Files.createDirectories(rcpDir);
				logger.info(() -> "Created directory: " + rcpDir);
			}

			// Extract exe only if it doesn't exist
			if (!Files.exists(exeFile)) {
				var resourceStream = getClass().getResourceAsStream("/native/windows/" + MEDIA_BRIDGE_EXE);
				if (resourceStream == null) {
					logger.error(() -> "MediaBridge.exe not found in resources");
					return null;
				}

				try (resourceStream) {
					Files.copy(resourceStream, exeFile, StandardCopyOption.REPLACE_EXISTING);
					logger.info(() -> "Extracted MediaBridge.exe to: " + exeFile);
				}
			} else {
				logger.info(() -> "MediaBridge.exe already exists at: " + exeFile);
			}

			return exeFile.toAbsolutePath().toString();
		} catch (IOException e) {
			logger.error(() -> "Error extracting MediaBridge.exe", e);
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
		}
	}

	private void readStreamErrors(InputStream is) {
		try (var br = new BufferedReader(new InputStreamReader(is))) {
			String line;
			while ((line = br.readLine()) != null) {
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
			var jsonElement = JsonParser.parseString(jsonLine);
			if (!jsonElement.isJsonObject()) {
				logger.warn(() -> "Invalid JSON event format");
				return;
			}

			var jsonObject = jsonElement.getAsJsonObject();
			logger.debug(() -> "MediaBridge: " + jsonObject);
			var eventType = jsonObject.get("event");

			if (eventType == null) {
				logger.warn(() -> "Missing 'event' field in JSON");
				return;
			}

			// Extract correlation ID if present (for request/response pattern)
			var correlationId = jsonObject.has("correlationId") ? jsonObject.get("correlationId").getAsString() : null;

			var event = eventType.getAsString();
			switch (event) {
			case "mediaChanged":
				handleMediaChanged(jsonObject, correlationId);
				break;

			case "playbackChanged":
				handlePlaybackChanged(jsonObject, correlationId);
				break;

			case "volumeChanged":
				handleVolumeChanged(jsonObject, correlationId);
				break;

			case "commandResult":
				handleCommandResult(jsonObject, correlationId);
				break;

			default:
				logger.debug(() -> "Unknown event type: " + event);
			}

		} catch (Exception e) {
			logger.error(() -> "Error processing media event: " + jsonLine, e);
		}
	}

	private void handleMediaChanged(JsonObject jsonObject, String correlationId) {
		try {
			var mediaEvent = gson.fromJson(jsonObject, MediaChangedEvent.class);
			var title = mediaEvent.title();
			var artist = mediaEvent.artist();
			var album = mediaEvent.album();
			var isPlaying = "Playing".equalsIgnoreCase(mediaEvent.state());

			logger.debug(() -> "Media changed: " + title + " - " + artist);

			Platform.runLater(() -> {
				currentTrack.getTitleProperty().set(title);
				currentTrack.getArtistProperty().set(artist);
				currentTrack.getAlbumProperty().set(album);
				currentTrack.getNowPlayingProperty().set(isPlaying);
				eventManager.post(new CcEvent("np", title, artist, album, isPlaying));
			});

			// Determine which correlation ID to use for response
			final String finalCorrelationId;
			if (correlationId != null) {
				finalCorrelationId = correlationId;
			} else if (!pendingMediaCommands.isEmpty()) {
				// Get the first pending media command (FIFO order)
				var iterator = pendingMediaCommands.keySet().iterator();
				if (iterator.hasNext()) {
					finalCorrelationId = iterator.next();
					logger.debug(() -> "Matched mediaChanged to pending media command with correlation ID: "
							+ finalCorrelationId);
				} else {
					finalCorrelationId = null;
				}
			} else {
				finalCorrelationId = null;
			}

			// Post response with track info if correlation ID present
			if (finalCorrelationId != null) {
				pendingMediaCommands.remove(finalCorrelationId);
				eventManager.postResponse(finalCorrelationId, "success", title, artist, album, isPlaying);
			}
		} catch (Exception e) {
			logger.error(() -> "Error handling mediaChanged event", e);
			// If there's a pending command, respond with error
			if (!pendingMediaCommands.isEmpty()) {
				var iterator = pendingMediaCommands.keySet().iterator();
				if (iterator.hasNext()) {
					var pendingCorrelationId = iterator.next();
					pendingMediaCommands.remove(pendingCorrelationId);
					eventManager.postResponse(pendingCorrelationId, "error", e.getMessage());
				}
			}
		}
	}

	private void handlePlaybackChanged(JsonObject jsonObject, String correlationId) {
		try {
			var playbackEvent = gson.fromJson(jsonObject, PlaybackChangedEvent.class);
			var state = playbackEvent.state();
			var isPlaying = "Playing".equalsIgnoreCase(state);

			logger.debug(() -> "Playback changed: " + state);

			Platform.runLater(() -> {
				currentTrack.getNowPlayingProperty().set(isPlaying);
				// Post np event with current values
				eventManager.post(new CcEvent("np", currentTrack.getTitleProperty().get(),
						currentTrack.getArtistProperty().get(), currentTrack.getAlbumProperty().get(), isPlaying));
			});

			// Determine which correlation ID to use for response
			final String finalCorrelationId;
			if (correlationId != null) {
				finalCorrelationId = correlationId;
			} else if (!pendingMediaCommands.isEmpty()) {
				// Get the first pending media command (FIFO order)
				var iterator = pendingMediaCommands.keySet().iterator();
				if (iterator.hasNext()) {
					finalCorrelationId = iterator.next();
					logger.debug(() -> "Matched playbackChanged to pending media command with correlation ID: "
							+ finalCorrelationId);
				} else {
					finalCorrelationId = null;
				}
			} else {
				finalCorrelationId = null;
			}

			// Post response with track info if correlation ID present
			if (finalCorrelationId != null) {
				pendingMediaCommands.remove(finalCorrelationId);
				// Use current track properties or the provided state
				eventManager.postResponse(finalCorrelationId, "success", currentTrack.getTitleProperty().get(),
						currentTrack.getArtistProperty().get(), currentTrack.getAlbumProperty().get(), isPlaying);
			}
		} catch (Exception e) {
			logger.error(() -> "Error handling playbackChanged event", e);
			// If there's a pending command, respond with error
			if (!pendingMediaCommands.isEmpty()) {
				var iterator = pendingMediaCommands.keySet().iterator();
				if (iterator.hasNext()) {
					var pendingCorrelationId = iterator.next();
					pendingMediaCommands.remove(pendingCorrelationId);
					eventManager.postResponse(pendingCorrelationId, "error", e.getMessage());
				}
			}
		}
	}

	private void handleVolumeChanged(JsonObject jsonObject, String correlationId) {
		try {
			lastVolume = jsonObject.get("volume").getAsDouble();
			var muted = jsonObject.get("muted").getAsBoolean();
			logger.debug(() -> "Volume changed: " + Math.round(lastVolume * 100) + "%, muted=" + muted);

			final String finalCorrelationId;
			if (correlationId != null) {
				finalCorrelationId = correlationId;
			} else if (!pendingVolumeCommands.isEmpty()) {
				// Get the first pending volume command (FIFO order)
				var iterator = pendingVolumeCommands.keySet().iterator();
				if (iterator.hasNext()) {
					finalCorrelationId = iterator.next();
				} else {
					finalCorrelationId = null;
				}
			} else {
				finalCorrelationId = null;
			}

			if (finalCorrelationId != null) {
				pendingVolumeCommands.remove(finalCorrelationId);
				eventManager.postResponse(finalCorrelationId, "success", lastVolume, muted);
			}
		} catch (Exception e) {
			logger.error(() -> "Error handling volumeChanged", e);
			if (!pendingVolumeCommands.isEmpty()) {
				var iterator = pendingVolumeCommands.keySet().iterator();
				if (iterator.hasNext()) {
					var pendingCorrelationId = iterator.next();
					pendingVolumeCommands.remove(pendingCorrelationId);
					eventManager.postResponse(pendingCorrelationId, "error", e.getMessage());
				}
			}
		}
	}

	private void handleCommandResult(JsonObject jsonObject, String correlationId) {
		try {
			var success = jsonObject.get("success").getAsBoolean();
			var command = jsonObject.get("command").getAsString();
			logger.debug(() -> "Command '" + command + "' completed: " + success);

			// If correlationId is not in the response, try to get it from the pending queue
			var finalCorrelationId = correlationId != null ? correlationId : pendingRequestQueue.poll();

			if (finalCorrelationId != null) {
				if (correlationId == null) {
					logger.debug(
							() -> "Matched response to pending request with correlation ID: " + finalCorrelationId);
				}

				// For media-changing commands, check if we should wait for
				// mediaChanged/playbackChanged or respond immediately
				if (isMediaCommand(command) && success) {
					// "stop" might not trigger playbackChanged, so respond immediately with current
					// track state
					if ("stop".equals(command)) { // stop means not playing
						eventManager.postResponse(finalCorrelationId, "success", currentTrack.getTitleProperty().get(),
								currentTrack.getArtistProperty().get(), currentTrack.getAlbumProperty().get(), false);
						logger.debug(() -> "Responding immediately to stop command");
					} else {
						// For other media commands, wait for mediaChanged or playbackChanged event
						pendingMediaCommands.put(finalCorrelationId, command);
						logger.debug(() -> "Storing pending media command for correlation ID: " + finalCorrelationId);
					}
				} else if (isVolumeCommand(command)) {
					if (!success) {
						pendingVolumeCommands.remove(finalCorrelationId);
						eventManager.postResponse(finalCorrelationId, "failed", command);
					}
				} else {
					// Non-media commands: respond immediately
					eventManager.postResponse(finalCorrelationId, success ? "success" : "failed", command);
				}
			}
		} catch (Exception e) {
			logger.error(() -> "Error handling commandResult", e);
			// Try to use a pending correlation ID
			var pendingCorrelationId = pendingRequestQueue.poll();
			if (pendingCorrelationId != null) {
				eventManager.postResponse(pendingCorrelationId, "error", e.getMessage());
			}
		}
	}

	private static boolean isMediaCommand(String command) {
		return command != null && ("next".equals(command) || "previous".equals(command)
				|| "togglePlayPause".equals(command) || "stop".equals(command));
	}

	private static boolean isVolumeCommand(String command) {
		return command != null && ("vol+".equals(command) || "vol-".equals(command) || "toggleMute".equals(command)
				|| "setVolume".equals(command));
	}

	private void sendCommand(String command) {
		sendCommand(command, null, null);
	}

	private synchronized void sendCommand(String command, Double value) {
		sendCommand(command, value, null);
	}

	private synchronized void sendCommand(String command, Double value, String correlationId) {
		if (writer == null) {
			logger.warn(() -> "MediaBridge is not running.");
			return;
		}

		try {
			var json = new JsonObject();
			json.addProperty("command", command);

			if (value != null) {
				json.addProperty("value", value);
			}

			if (correlationId != null) {
				json.addProperty("correlationId", correlationId);
				pendingRequestQueue.offer(correlationId);

				// Register before sending, because volumeChanged may arrive first.
				if (isVolumeCommand(command)) {
					pendingVolumeCommands.put(correlationId, command);
				} else if (isMediaCommand(command) && !"stop".equals(command)) {
					pendingMediaCommands.put(correlationId, command);
				}

				logger.debug(() -> "Tracking pending request with correlation ID: " + correlationId);
			}

			writer.write(gson.toJson(json));
			writer.newLine();
			writer.flush();
		} catch (IOException e) {
			if (correlationId != null) {
				pendingRequestQueue.remove(correlationId);
				pendingVolumeCommands.remove(correlationId);
				pendingMediaCommands.remove(correlationId);
			}

			eventManager.echoAsync("Error communicating with MediaBridge", e.getMessage());
			logger.error(() -> "Failed to send command to MediaBridge", e);
		}
	}

	public void prevSong() {
		sendCommand("previous");
		watcher.updatePropertiesAsync();
	}

	public void prevSongWithResponse(String correlationId) {
		sendCommand("previous", null, correlationId);
		watcher.updatePropertiesAsync();
	}

	public void stop() {
		sendCommand("stop");
		watcher.updatePropertiesAsync();
	}

	public void stopWithResponse(String correlationId) {
		sendCommand("stop", null, correlationId);
		watcher.updatePropertiesAsync();
	}

	public void playPause() {
		sendCommand("togglePlayPause");
		watcher.updatePropertiesAsync();
	}

	public void playPauseWithResponse(String correlationId) {
		sendCommand("togglePlayPause", null, correlationId);
		watcher.updatePropertiesAsync();
	}

	public void nextSong() {
		sendCommand("next");
		watcher.updatePropertiesAsync();
	}

	public void nextSongWithResponse(String correlationId) {
		sendCommand("next", null, correlationId);
		watcher.updatePropertiesAsync();
	}

	public void volumeUp() {
		sendCommand("vol+");
	}

	public void volumeUpWithResponse(String correlationId) {
		sendCommand("vol+", null, correlationId);
	}

	public void volumeDown() {
		sendCommand("vol-");
	}

	public void volumeDownWithResponse(String correlationId) {
		sendCommand("vol-", null, correlationId);
	}

	public void toggleMute() {
		sendCommand("toggleMute");
	}

	public void toggleMuteWithResponse(String correlationId) {
		sendCommand("toggleMute", null, correlationId);
	}

	public void setVolume(double volume) {
		sendCommand("setVolume", volume);
	}

	/**
	 * Send a command and return a correlation ID for tracking the response
	 * 
	 * @param command the command to send
	 * @return correlation ID that can be used with EventManager.listenForResponse()
	 */
	public String sendCommandWithResponse(String command) {
		return sendCommandWithResponse(command, null);
	}

	/**
	 * Send a command with value and return a correlation ID for tracking the
	 * response
	 * 
	 * @param command the command to send
	 * @param value   optional double value parameter
	 * @return correlation ID that can be used with EventManager.listenForResponse()
	 */
	public String sendCommandWithResponse(String command, Double value) {
		var correlationId = "media-" + requestIdCounter.incrementAndGet();
		sendCommand(command, value, correlationId);
		return correlationId;
	}

	public void shutdown() {
		logger.debug(() -> "Shutting down MediaBridge");
		running.set(false);

		// First, try a graceful shutdown of the process
		if (process != null && process.isAlive()) {
			process.destroy();
			try {
				if (!process.waitFor(3, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				logger.error(() -> "Interrupted while waiting for MediaBridge to exit", e);
			}
		}

		// Close the reader/writer streams
		if (reader != null) {
			try {
				reader.close();
			} catch (IOException e) {
				logger.error(() -> "Error closing reader", e);
			}
		}
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException e) {
				logger.error(() -> "Error closing writer", e);
			}
		}

		// Wait for reader thread to finish
		if (readerThread != null && readerThread.isAlive()) {
			try {
				readerThread.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				logger.error(() -> "Interrupted while waiting for reader thread to finish", e);
			}
			if (readerThread.isAlive()) {
				logger.warn(() -> "Reader thread did not terminate, interrupting");
				readerThread.interrupt();
			}
		}

		// Stop stderr reader
		if (stderrThread != null && stderrThread.isAlive()) {
			try {
				stderrThread.join(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		logger.debug(() -> "MediaBridge shutdown complete");
	}

}
