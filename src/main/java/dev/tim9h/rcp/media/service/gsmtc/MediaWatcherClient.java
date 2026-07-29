package dev.tim9h.rcp.media.service.gsmtc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import javafx.application.Platform;

@Singleton
public class MediaWatcherClient {

	private static final String MEDIA_WATCHER_EXE = "MediaWatcher.exe";

	@InjectLogger
	private Logger logger;

	@Inject
	private EventManager eventManager;

	@Inject
	private CurrentTrackProperties currentTrack;

	private Process process;

	private Thread readerThread;

	private Thread stderrThread;

	private BufferedReader reader;

	private final Gson gson = new Gson();

	private final AtomicBoolean running = new AtomicBoolean(false);

	@Inject
	public MediaWatcherClient(Injector injector) {
		injector.injectMembers(this);
		startMediaWatcher();
	}

	private void startMediaWatcher() {
		try {
			var exePath = extractMediaWatcher();
			if (exePath == null) {
				logger.error(() -> "Failed to extract MediaWatcher.exe");
				return;
			}

			logger.info(() -> "Starting MediaWatcher from: " + exePath);
			process = new ProcessBuilder(exePath).start();
			reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

			running.set(true);
			readerThread = new Thread(this::readMediaEvents, "MediaWatcherEventReader");
			readerThread.setDaemon(true);
			readerThread.start();

			// capture stderr to the log to aid debugging
			var errStream = process.getErrorStream();
			stderrThread = new Thread(() -> readStreamErrors(errStream), "MediaWatcherStderrReader");
			stderrThread.setDaemon(true);
			stderrThread.start();

			logger.info(() -> "MediaWatcher started successfully");
		} catch (IOException e) {
			logger.error(() -> "Failed to start MediaWatcher", e);
		}
	}

	private String extractMediaWatcher() {
		try {
			var homeDir = System.getProperty("user.home");
			var rcpDir = Paths.get(homeDir, "rcp");
			var exeFile = rcpDir.resolve(MEDIA_WATCHER_EXE);

			// Create rcp directory if it doesn't exist
			if (!Files.exists(rcpDir)) {
				Files.createDirectories(rcpDir);
				logger.info(() -> "Created directory: " + rcpDir);
			}

			// Extract exe only if it doesn't exist
			if (!Files.exists(exeFile)) {
				var resourceStream = getClass().getResourceAsStream("/native/windows/" + MEDIA_WATCHER_EXE);
				if (resourceStream == null) {
					logger.error(() -> "MediaWatcher.exe not found in resources");
					return null;
				}

				try (resourceStream) {
					Files.copy(resourceStream, exeFile, StandardCopyOption.REPLACE_EXISTING);
					logger.info(() -> "Extracted MediaWatcher.exe to: " + exeFile);
				}
			} else {
				logger.info(() -> "MediaWatcher.exe already exists at: " + exeFile);
			}

			return exeFile.toAbsolutePath().toString();
		} catch (IOException e) {
			logger.error(() -> "Error extracting MediaWatcher.exe", e);
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
				logger.warn("MediaWatcher stderr: " + line);
			}
		} catch (IOException e) {
			if (running.get()) {
				logger.error(() -> "Error reading MediaWatcher stderr", e);
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
			logger.debug(() -> "MediaWatcher: " + jsonObject);
			var eventType = jsonObject.get("event");

			if (eventType == null) {
				logger.warn(() -> "Missing 'event' field in JSON");
				return;
			}

			var event = eventType.getAsString();

			if ("mediaChanged".equals(event)) {
				handleMediaChanged(jsonObject);
			} else if ("playbackChanged".equals(event)) {
				handlePlaybackChanged(jsonObject);
			} else {
				logger.debug(() -> "Unknown event type: " + event);
			}
		} catch (Exception e) {
			logger.error(() -> "Error processing media event: " + jsonLine, e);
		}
	}

	private void handleMediaChanged(JsonObject jsonObject) {
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
		} catch (Exception e) {
			logger.error(() -> "Error handling mediaChanged event", e);
		}
	}

	private void handlePlaybackChanged(JsonObject jsonObject) {
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
		} catch (Exception e) {
			logger.error(() -> "Error handling playbackChanged event", e);
		}
	}

	public void shutdown() {
		logger.debug(() -> "Shutting down MediaWatcher");
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
				logger.error(() -> "Interrupted while waiting for MediaWatcher to exit", e);
			}
		}

		// Close the reader (this will unblock readLine)
		if (reader != null) {
			try {
				reader.close();
			} catch (IOException e) {
				logger.error(() -> "Error closing reader", e);
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

		logger.debug(() -> "MediaWatcher shutdown complete");
	}

}
