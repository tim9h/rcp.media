package dev.tim9h.rcp.media.service.aimp;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

import dev.tim9h.rcp.controls.utils.DelayedRunner;
import dev.tim9h.rcp.event.CcEvent;
import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.media.MediaViewFactory;
import dev.tim9h.rcp.media.service.bean.CurrentTrackProperties;
import dev.tim9h.rcp.settings.Settings;
import javafx.application.Platform;

@Singleton
public class SongFileWatcher extends DelayedRunner implements Runnable {

	private static final String BOM = "\uFEFF";

	@Inject
	private Settings settings;

	@Inject
	private EventManager eventManager;

	private String currentTrackInfoPath;

	@InjectLogger
	private Logger logger;

	@Inject
	private CurrentTrackProperties currentTrack;

	private static final List<WatchService> watchServices = new ArrayList<>();

	private Thread thread;

	@Inject
	public SongFileWatcher(Injector injector) {
		super(5); // set delay to 50 ms
		injector.injectMembers(this);
		currentTrackInfoPath = settings.getString(MediaViewFactory.SETTING_CURRENTTRACKINFOPATH);
		if (StringUtils.isBlank(currentTrackInfoPath)) {
			eventManager.echo("Missing preference: currentTrackInfoPath");
			logger.warn(() -> "Unable to watch current track: Preference currentTrackInfoPath not set");
		} else {
			parseFileInfoFile(Path.of(currentTrackInfoPath));
			thread = new Thread(this, "CurrentSongFileWatcher");
			thread.setDaemon(true);
			thread.start();
		}
	}

	@Override
	public void run() {
		try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
			var path = Paths.get(currentTrackInfoPath);
			logger.info(() -> "Night gathers and now my watch begins.");
			path.getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
			watchServices.add(watchService);
			var poll = true;
			while (poll) {
				poll = pollEvents(watchService);
			}
		} catch (IOException | InterruptedException e) {
			Thread.currentThread().interrupt();
			logger.error(() -> "Error while watching currentTrackInfo file", e);
		} catch (ClosedWatchServiceException e) {
			//
		}
	}

	protected boolean pollEvents(WatchService watchService) throws InterruptedException {
		var key = watchService.take();
		Thread.sleep(10); // eliminate multiple events (content + change time)
		var path = (Path) key.watchable();
		key.pollEvents().forEach(event -> {
			if (currentTrackInfoPath.endsWith(((Path) event.context()).toString())) {
				runDelayed(() -> parseFileInfoFile(path.resolve((Path) event.context())));
			}
		});
		return key.reset();
	}

	public void parseFileInfoFile(Path path) {
		try {
			var lines = Files.readAllLines(path);
			if (!lines.isEmpty()) {
				lines.set(0, lines.get(0).replace(BOM, StringUtils.EMPTY));
			}
			Platform.runLater(() -> {
				if (lines.size() > 2 && (!StringUtils.equals(currentTrack.getTitleProperty().get(), lines.get(0))
						|| !StringUtils.equals(currentTrack.getArtistProperty().get(), lines.get(1))
						|| !StringUtils.equals(currentTrack.getAlbumProperty().get(), lines.get(2)))) {
					logger.debug(() -> "Updating song properties: " + StringUtils.join(lines, " - "));
					currentTrack.getTitleProperty().set(lines.get(0));
					currentTrack.getArtistProperty().set(lines.get(1));
					currentTrack.getAlbumProperty().set(lines.get(2));
					eventManager.post(new CcEvent("np", lines.get(0), lines.get(1), lines.get(2), true));
				} else if (lines.size() < 3) {
					logger.debug(() -> "Clearing song properties: " + StringUtils.join(lines, " - "));
					currentTrack.getTitleProperty().set(StringUtils.EMPTY);
					currentTrack.getArtistProperty().set(StringUtils.EMPTY);
					currentTrack.getAlbumProperty().set(StringUtils.EMPTY);
					eventManager
							.post(new CcEvent("np", StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, false));
				}
			});
		} catch (IOException e) {
			logger.warn(() -> "Unable to parse " + path + ". Template must be\n%Title%Char(10)%Artist%Char(10)%Album",
					e);
			Platform.runLater(() -> eventManager.echo("Unable to read " + path));
		}
	}

	public void shutdown() {
		logger.debug(() -> "Shutting down SongWatcher");
		watchServices.forEach(service -> {
			try {
				service.close();
			} catch (IOException e) {
				logger.error(() -> "Unable to close watchservice");
			}
		});
		if (thread != null) {
			thread.interrupt();
		}
	}

}
