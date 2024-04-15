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
			Platform.runLater(() -> {
				currentTrack.getTitleProperty().set(track.title());
				currentTrack.getArtistProperty().set(track.artist());
				currentTrack.getAlbumProperty().set(track.album());
				currentTrack.getNowPlayingProperty().set(true);
			});
		} else if (track != null && !track.isPlaying()) {
			Platform.runLater(() -> {
				currentTrack.getTitleProperty().set(MediaView.NOT_PLAYING);
				currentTrack.getArtistProperty().set(StringUtils.EMPTY);
				currentTrack.getAlbumProperty().set(StringUtils.EMPTY);
				currentTrack.getNowPlayingProperty().set(false);
			});
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
		}
	}

}
