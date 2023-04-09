package dev.tim9h.rcp.media.service.lastfm;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.media.MediaView;
import dev.tim9h.rcp.media.MediaViewFactory;
import dev.tim9h.rcp.media.service.bean.CurrentTrackProperties;
import dev.tim9h.rcp.settings.Settings;
import javafx.application.Platform;

@Singleton
public class LastFmWatcher {

	@Inject
	private CurrentTrackProperties currentTrack;

	@Inject
	private LastFmService service;

	@Inject
	private Settings settings;

	@InjectLogger
	private Logger logger;

	private Timer timer;

	public void startPolling() {
		timer = new Timer("lastFmUpdater", true);
		timer.scheduleAtFixedRate(new TimerTask() {

			@Override
			public void run() {
				updateProperties();
			}

		}, 0, settings.getLong(MediaViewFactory.SETTING_LASTFM_POLLINGINTERVAL).longValue());
	}

	private void updateProperties() {
		var track = service.getCurrentTrack();
		if (track != null && track.isPlaying()) {
			if (!StringUtils.equals(currentTrack.getTitleProperty().get(), track.title())
					|| !StringUtils.equals(currentTrack.getArtistProperty().get(), track.artist())
					|| !StringUtils.equals(currentTrack.getAlbumProperty().get(), track.album())) {
				logger.debug(() -> "Updating song properties: " + track);
				Platform.runLater(() -> {
					currentTrack.getTitleProperty().set(track.title());
					currentTrack.getArtistProperty().set(track.artist());
					currentTrack.getAlbumProperty().set(track.album());
				});
			}
		} else {
			if (StringUtils.isNotBlank(currentTrack.getTitleProperty().get())
					&& StringUtils.isNotBlank(currentTrack.getArtistProperty().get())
					&& StringUtils.isNotBlank(currentTrack.getAlbumProperty().get())) {
				logger.debug(() -> "Clearing song properties: not playing");
				Platform.runLater(() -> {
					currentTrack.getTitleProperty().set(StringUtils.EMPTY);
					currentTrack.getArtistProperty().set(StringUtils.EMPTY);
					currentTrack.getAlbumProperty().set(StringUtils.EMPTY);
				});
			}
		}
	}

	public void updatePropertiesAsync() {
		if (settings.getStringSet(MediaView.SETTING_MODES).contains("lastfm")) {
			CompletableFuture.runAsync(() -> {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					logger.error(() -> "Error during async last.fm status update delay", e);
					Thread.currentThread().interrupt();
				}
				logger.info(() -> "Updating last.fm status manually async");
				updateProperties();
			});
		}
	}

	public void stopPolling() {
		if (timer != null) {
			timer.cancel();
			timer.purge();
		}
	}

}
