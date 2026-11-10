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
import dev.tim9h.rcp.media.service.bean.CurrentTrackProperties;
import dev.tim9h.rcp.media.service.bean.Track;
import dev.tim9h.rcp.service.ModeService;
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

	@Inject
	private ModeService modeService;

	@InjectLogger
	private Logger logger;

	private Timer timer;

	private volatile Track latestTrack;

	private volatile boolean viewShown;

	private volatile boolean modeActive;

	private volatile boolean pollingRequired;

	public void startPolling() {
		modeActive = true;
		refreshPollingState();
	}

	public void setViewShown(boolean shown) {
		viewShown = shown;
		refreshPollingState();
	}

	private void updateProperties() {
		var track = service.getCurrentTrack();
		updateCurrentTrack(track);
	}

	private void pollProperties() {
		if (!pollingRequired) {
			return;
		}
		var track = service.getCurrentTrack();
		if (pollingRequired) {
			updateCurrentTrack(track);
		}
	}

	public void updateCurrentTrack(Track track) {
		if (track == null || !track.isPlaying()) {
			return;
		}

		latestTrack = track;
		Platform.runLater(() -> {
			if (!currentTrack.getNowPlayingProperty().get()) {
				currentTrack.getTitleProperty().set(track.title());
				currentTrack.getArtistProperty().set(track.artist());
				currentTrack.getAlbumProperty().set(track.album());
				currentTrack.getNowPlayingProperty().set(true);
				refreshPollingState();
				return;
			}

			completeAlbumIfCurrentTrackMatches(track);
			refreshPollingState();
		});
	}

	public void onCurrentTrackChanged() {
		runOnFxThread(() -> {
			var track = latestTrack;
			if (track != null) {
				completeAlbumIfCurrentTrackMatches(track);
			}
			refreshPollingState();
		});
	}

	private void completeAlbumIfCurrentTrackMatches(Track track) {
		var matchesCurrentTrack = track.matches(currentTrack.getTitleProperty().get(),
				currentTrack.getArtistProperty().get());
		if (matchesCurrentTrack && StringUtils.isBlank(currentTrack.getAlbumProperty().get())
				&& StringUtils.isNotBlank(track.album())) {
			currentTrack.getAlbumProperty().set(track.album());
		}
	}

	private void refreshPollingState() {
		runOnFxThread(() -> {
			pollingRequired = viewShown && modeActive && currentTrack.getNowPlayingProperty().get()
					&& StringUtils.isBlank(currentTrack.getAlbumProperty().get());
			if (pollingRequired) {
				startTimer();
			} else {
				stopTimer();
			}
		});
	}

	private void runOnFxThread(Runnable action) {
		if (Platform.isFxApplicationThread()) {
			action.run();
		} else {
			Platform.runLater(action);
		}
	}

	private void startTimer() {
		if (timer != null) {
			return;
		}
		timer = new Timer("lastFmUpdater", true);
		timer.scheduleAtFixedRate(new TimerTask() {

			@Override
			public void run() {
				pollProperties();
			}

		}, 0, settings.getLong(MediaView.SETTING_LASTFM_POLLINGINTERVAL).longValue());
	}

	private void stopTimer() {
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
	}

	public void updatePropertiesAsync() {
		if (modeService.isModeActive(MediaView.MODE_LASTFM)) {
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
		modeActive = false;
		latestTrack = null;
		refreshPollingState();
	}

}
