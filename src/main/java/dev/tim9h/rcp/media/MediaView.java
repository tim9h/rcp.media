package dev.tim9h.rcp.media;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;

import dev.tim9h.rcp.controls.IconButton;
import dev.tim9h.rcp.event.CcEvent;
import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.media.service.aimp.SongFileWatcher;
import dev.tim9h.rcp.media.service.bean.CurrentTrackProperties;
import dev.tim9h.rcp.media.service.lastfm.LastFmService;
import dev.tim9h.rcp.media.service.lastfm.LastFmWatcher;
import dev.tim9h.rcp.media.service.playback.MediaService;
import dev.tim9h.rcp.settings.Settings;
import dev.tim9h.rcp.spi.CCard;
import dev.tim9h.rcp.spi.Gravity;
import dev.tim9h.rcp.spi.Mode;
import dev.tim9h.rcp.spi.Position;
import dev.tim9h.rcp.spi.TreeNode;
import javafx.animation.FadeTransition;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public class MediaView implements CCard {

	private static final String PROFILE = "profile";

	public static final String NOT_PLAYING = "Not playing";

	private static final String MODE_LASTFM = "lastfm";

	public static final String SETTING_MODES = "core.modes";

	private static final String SETTINGS_ANIMATIONS_ENABLED = "core.ui.animations";

	@InjectLogger
	private Logger logger;

	@Inject
	private MediaService mediaService;

	@Inject
	private SongFileWatcher songWatcher;

	@Inject
	private Settings settings;

	private Duration animationDuration;

	@Inject
	private LastFmService lastFmService;

	@Inject
	private LastFmWatcher lastFmWatcher;

	private Hyperlink lblTitle;

	private Hyperlink lblArtist;

	private Hyperlink lblAlbum;

	@Inject
	private CurrentTrackProperties currentTrack;

	@Inject
	private EventManager eventManager;

	@Inject
	private IconButton btnPrev;

	@Inject
	private IconButton btnStop;

	@Inject
	private IconButton btnPlay;

	@Inject
	private IconButton btnNext;

	@Inject
	private IconButton btnLastFm;

	@Override
	public String getName() {
		return "Music Player";
	}

	@Override
	public Optional<Node> getNode() throws IOException {
		lblTitle = new Hyperlink();
		lblArtist = new Hyperlink();
		lblAlbum = new Hyperlink();
		lblTitle.getStyleClass().add("accent-label");

		btnPrev.setLabel('⏪');
		btnPrev.setOnAction(_ -> {
			mediaService.prevSong();
			eventManager.echo("Previous");
		});

		btnStop.setLabel('⏹');
		btnStop.setOnAction(_ -> {
			mediaService.stop();
			eventManager.echo("Stop");
		});

		btnPlay.setLabel('⏵');
		btnPlay.setOnAction(_ -> {
			mediaService.playPause();
			eventManager.echo("Play/Pause");
		});

		btnNext.setLabel('⏩');
		btnNext.setOnAction(_ -> {
			mediaService.nextSong();
			eventManager.echo("Next");
		});

		createCurrentTrackBindings();

		// add last.fm links
		lblTitle.setOnAction(_ -> {
			eventManager.post(new CcEvent(CcEvent.EVENT_CLI_REQUEST_FOCUS));
			if (currentTrack.getNowPlayingProperty().get()) {
				openUrl("https://www.last.fm/music/%s/_/%s", lblArtist.getText(), lblTitle.getText());
			} else {
				openUrl("https://www.last.fm/user/%s", lastFmService.getUsername());
			}
		});
		lblArtist.setOnAction(_ -> {
			eventManager.post(new CcEvent(CcEvent.EVENT_CLI_REQUEST_FOCUS));
			openUrl("https://www.last.fm/music/%s/", lblArtist.getText());
		});
		lblAlbum.setOnAction(_ -> {
			eventManager.post(new CcEvent(CcEvent.EVENT_CLI_REQUEST_FOCUS));
			openUrl("https://www.last.fm/music/%s/%s/", lblArtist.getText(), lblAlbum.getText());
		});

		var songInfo = new VBox(lblTitle, lblArtist, lblAlbum);

		var fade = new FadeTransition(getAnimationDuration(), songInfo);
		fade.setFromValue(0.0);
		fade.setToValue(1.0);
		fade.setAutoReverse(true);

		if (settings.getBoolean(SETTINGS_ANIMATIONS_ENABLED).booleanValue()) {
			lblTitle.textProperty().addListener((_, _, _) -> fade.play());
		}

		btnLastFm.setLabel('∞');
		btnLastFm.setOnAction(_ -> eventManager.post(MODE_LASTFM, PROFILE));

		var buttons = new HBox(btnLastFm, btnPrev, btnStop, btnPlay, btnNext);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		buttons.setPrefHeight(Region.USE_PREF_SIZE);

		var hbox = new HBox();
		HBox.setHgrow(songInfo, Priority.ALWAYS);
		hbox.getStyleClass().add("ccCard");
		hbox.getChildren().addAll(songInfo, buttons);
		hbox.setFillHeight(false);
		hbox.setMaxHeight(Region.USE_PREF_SIZE);
		hbox.setAlignment(Pos.CENTER);

		return Optional.of(hbox);
	}

	private void createCurrentTrackBindings() {
		lblTitle.textProperty().bind(Bindings.createStringBinding(
				() -> currentTrack.getNowPlayingProperty().get() ? currentTrack.getTitleProperty().get() : NOT_PLAYING,
				currentTrack.getTitleProperty(), currentTrack.getNowPlayingProperty()));
		lblAlbum.textProperty()
				.bind(Bindings.createStringBinding(
						() -> currentTrack.getNowPlayingProperty().get() ? currentTrack.getAlbumProperty().get()
								: StringUtils.EMPTY,
						currentTrack.getAlbumProperty(), currentTrack.getNowPlayingProperty()));
		lblArtist.textProperty()
				.bind(Bindings.createStringBinding(
						() -> currentTrack.getNowPlayingProperty().get() ? currentTrack.getArtistProperty().get()
								: StringUtils.EMPTY,
						currentTrack.getArtistProperty(), currentTrack.getNowPlayingProperty()));

		lblAlbum.disableProperty().bind(currentTrack.getNowPlayingProperty().not());
		lblArtist.disableProperty().bind(currentTrack.getNowPlayingProperty().not());
	}

	private void openUrl(String url, String... params) {
		var encodedParams = Arrays.stream(params).map(s -> encodeValue(s)).toArray();
		try {
			Desktop.getDesktop().browse(new URI(String.format(url, encodedParams)));
		} catch (URISyntaxException | IOException e) {
			logger.error(() -> "Unable to open url", e);
		}
	}

	private static String encodeValue(String value) {
		var encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
		if (encoded.startsWith("%EF%BB%BF")) {
			return encoded.substring(9); // remove BOM
		}
		return encoded;
	}

	@Override
	public Gravity getGravity() {
		return new Gravity(5, Position.TOP);
	}

	@Override
	public void onShown() {
		if (settings.getStringSet(SETTING_MODES).contains(MODE_LASTFM)) {
			lastFmWatcher.startPolling();
		}
	}

	@Override
	public void onHidden() {
		if (settings.getStringSet(SETTING_MODES).contains(MODE_LASTFM)) {
			lastFmWatcher.stopPolling();
		}
	}

	@Override
	public void onShutdown() {
		songWatcher.shutdown();
	}

	@Override
	public void initBus(EventManager em) {
		CCard.super.initBus(eventManager);

		em.listen("next", _ -> {
			mediaService.nextSong();
			em.echo("Playing next song");
		});
		em.listen("previous", _ -> {
			mediaService.prevSong();
			em.echo("Playing previous song");
		});
		em.listen("play", _ -> {
			mediaService.playPause();
			em.echo("Play/Pause music");
		});
		em.listen("pause", _ -> {
			mediaService.playPause();
			em.echo("Play/Pause music");
		});
		em.listen("stop", _ -> {
			mediaService.stop();
			em.echo("Stop playing music");
		});
	}

	@Override
	public void onSettingsChanged() {
		animationDuration = null;
		lastFmService.reloadSettings();
	}

	private Duration getAnimationDuration() {
		if (animationDuration == null) {
			animationDuration = Duration.millis(settings.getDouble("ui.components.label.fade.duration").doubleValue());
		}
		return animationDuration;
	}

	@Override
	public Optional<List<Mode>> getModes() {
		return Optional.of(Arrays.asList(new Mode() {

			@Override
			public String getName() {
				return MODE_LASTFM;
			}

			@Override
			public void onCommand(String command, String... args) {
				handleLastfmCommands(command, args);
			}

			@Override
			public void onEnable() {
				lastFmWatcher.startPolling();
				eventManager.echo("Last.fm status enabled");
			}

			@Override
			public void onDisable() {
				lastFmWatcher.stopPolling();
				eventManager.echo("Last.fm status disabled");
			}

			@Override
			public TreeNode<String> getCommandTree() {
				var commands = Mode.super.getCommandTree();
				commands.add("np", "username", "apikey", PROFILE);
				return commands;
			}
		}));
	}

	private void handleLastfmCommands(String command, String... args) {
		var join = StringUtils.join(args);
		if ("np".equals(command)) {
			lastFmNowPlaying();
		} else if ("username".equals(command)) {
			if (StringUtils.isBlank(join)) {
				eventManager.echo("Last.fm username", settings.getString(MediaViewFactory.SETTING_LASTFM_USERNAME));
			} else {
				settings.persist(MediaViewFactory.SETTING_LASTFM_USERNAME, join);
				eventManager.echo("Updated last.fm Username");
			}
		} else if ("apikey".equals(command)) {
			if (StringUtils.isBlank(join)) {
				eventManager.echo("Last.fm API key", settings.getString(MediaViewFactory.SETTING_LASTFM_APIKEY));
			} else {
				settings.persist(MediaViewFactory.SETTING_LASTFM_APIKEY, join);
				eventManager.echo("Updated last.fm API key");
			}
		} else if (PROFILE.equals(command)) {
			openUrl("https://www.last.fm/user/%s", lastFmService.getUsername());
		}
	}

	private void lastFmNowPlaying() {
		eventManager.showWaitingIndicator();
		CompletableFuture.supplyAsync(() -> lastFmService.getCurrentTrack()).thenAccept(track -> {
			if (track != null) {
				if (track.isPlaying()) {
					eventManager.echo(track.artist(), track.title());
				} else {
					eventManager.echo("Not scrobbling");
				}
			}
		});
	}

	@Override
	public Optional<TreeNode<String>> getModelessCommands() {
		return Optional.of(new TreeNode<>(StringUtils.EMPTY).add("next", "previous", "play", "pause", "stop"));
	}

}
