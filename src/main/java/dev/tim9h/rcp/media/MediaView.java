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

	private Hyperlink lblArtist;

	private Hyperlink lblTitle;

	private Hyperlink lblAlbum;

	@Inject
	private CurrentTrackProperties currentTrack;

	@Inject
	private EventManager eventManager;

	private VBox songInfo;

	@Inject
	private IconButton btnPrev;

	@Inject
	private IconButton btnStop;

	@Inject
	private IconButton btnPlay;

	@Inject
	private IconButton btnNext;

	@Override
	public String getName() {
		return "Music Player";
	}

	@Override
	public Optional<Node> getNode() throws IOException {
		lblArtist = new Hyperlink();
		lblTitle = new Hyperlink();
		lblAlbum = new Hyperlink();
		lblTitle.getStyleClass().add("accent-label");

		btnPrev.setLabel('⏪');
		btnPrev.setOnAction(event -> {
			mediaService.prevSong();
			eventManager.echo("Previous");
		});

		btnStop.setLabel('⏹');
		btnStop.setOnAction(event -> {
			mediaService.stop();
			eventManager.echo("Stop");
		});

		btnPlay.setLabel('⏵');
		btnPlay.setOnAction(event -> {
			mediaService.playPause();
			eventManager.echo("Play/Pause");
		});

		btnNext.setLabel('⏩');
		btnNext.setOnAction(event -> {
			mediaService.nextSong();
			eventManager.echo("Next");
		});

		bindNowPlayingProperties();

		// add last.fm links
		lblTitle.setOnAction(e -> {
			eventManager.post(new CcEvent(CcEvent.EVENT_CLI_REQUEST_FOCUS));
			openUrl("https://www.last.fm/music/%s/_/%s", encodeValue(lblArtist.getText()),
					encodeValue(lblTitle.getText()));
		});
		lblArtist.setOnAction(e -> {
			eventManager.post(new CcEvent(CcEvent.EVENT_CLI_REQUEST_FOCUS));
			openUrl("https://www.last.fm/music/%s/", encodeValue(lblArtist.getText()));
		});
		lblAlbum.setOnAction(e -> {
			eventManager.post(new CcEvent(CcEvent.EVENT_CLI_REQUEST_FOCUS));
			openUrl("https://www.last.fm/music/%s/%s/", encodeValue(lblArtist.getText()),
					encodeValue(lblAlbum.getText()));
		});

		songInfo = new VBox(lblTitle, lblArtist, lblAlbum);

		var fade = new FadeTransition(getAnimationDuration(), songInfo);
		fade.setFromValue(0.0);
		fade.setToValue(1.0);
		fade.setAutoReverse(true);

		if (settings.getBoolean(SETTINGS_ANIMATIONS_ENABLED).booleanValue()) {
			lblTitle.textProperty().addListener((obs, oldVal, newVal) -> fade.play());
		}

		var buttons = new HBox(btnPrev, btnStop, btnPlay, btnNext);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		buttons.setPrefHeight(Region.USE_PREF_SIZE);

		var hbox = new HBox();
		HBox.setHgrow(songInfo, Priority.ALWAYS);
		hbox.getStyleClass().add("ccCard");
		hbox.getChildren().addAll(songInfo, buttons);
		hbox.setFillHeight(false);
		hbox.setMaxHeight(Region.USE_PREF_SIZE);

		return Optional.of(hbox);
	}

	private void bindNowPlayingProperties() {
		lblTitle.textProperty().bind(currentTrack.getTitleProperty());
		lblAlbum.textProperty().bind(currentTrack.getAlbumProperty());
		lblArtist.textProperty().bind(currentTrack.getArtistProperty());

		lblTitle.visibleProperty().bind(Bindings.createBooleanBinding(
				() -> Boolean.valueOf(StringUtils.isNotBlank(lblTitle.textProperty().get())), lblTitle.textProperty()));
		lblAlbum.visibleProperty().bind(Bindings.createBooleanBinding(
				() -> Boolean.valueOf(StringUtils.isNotBlank(lblAlbum.textProperty().get())), lblAlbum.textProperty()));
		lblArtist.visibleProperty()
				.bind(Bindings.createBooleanBinding(
						() -> Boolean.valueOf(StringUtils.isNotBlank(lblArtist.textProperty().get())),
						lblArtist.textProperty()));

		currentTrack.getTitleProperty().addListener((obs, oldVal, newVal) -> {
			if (newVal.isBlank()) {
				songInfo.setMinHeight(0);
				songInfo.setMaxHeight(0);
			} else {
				songInfo.setMinHeight(Region.USE_COMPUTED_SIZE);
				songInfo.setMaxHeight(Region.USE_COMPUTED_SIZE);
			}
		});
	}

	private void openUrl(String url, Object... params) {
		try {
			Desktop.getDesktop().browse(new URI(String.format(url, params)));
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

		em.listen("next", data -> {
			mediaService.nextSong();
			em.echo("Playing next song");
		});
		em.listen("previous", data -> {
			mediaService.prevSong();
			em.echo("Playing previous song");
		});
		em.listen("play", data -> {
			mediaService.playPause();
			em.echo("Play/Pause music");
		});
		em.listen("pause", data -> {
			mediaService.playPause();
			em.echo("Play/Pause music");
		});
		em.listen("stop", data -> {
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
			animationDuration = Duration
					.millis(settings.getDouble("ui.components.label.animationduration").doubleValue());
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
				var join = StringUtils.join(args);
				if ("np".equals(command)) {
					eventManager.showWaitingIndicator();
					CompletableFuture.supplyAsync(() -> lastFmService.getCurrentTrack()).thenAccept(track -> {
						if (track != null) {
							if (track.isPlaying()) {
								eventManager.echoAsync(track.artist(), track.title());
							} else {
								eventManager.echoAsync("Not scrobbling");
							}
						}
					});
				} else if ("username".equals(command)) {
					settings.persist(MediaViewFactory.SETTING_LASTFM_USERNAME, join);
					eventManager.echo("Updated last.fm Username");
				} else if ("apikey".equals(command)) {
					settings.persist(MediaViewFactory.SETTING_LASTFM_APIKEY, join);
					eventManager.echo("Updated last.fm API key");
				}
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
				commands.add("np", "username", "apikey");
				return commands;
			}
		}));
	}

	@Override
	public Optional<TreeNode<String>> getModelessCommands() {
		return Optional.of(new TreeNode<>(StringUtils.EMPTY).add("next", "previous", "play", "pause", "stop"));
	}

}
