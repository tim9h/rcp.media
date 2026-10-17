package dev.tim9h.rcp.media;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;

import dev.tim9h.rcp.controls.AnimatedLabel;
import dev.tim9h.rcp.controls.IconButton;
import dev.tim9h.rcp.event.Event;
import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.media.service.bean.CurrentTrackProperties;
import dev.tim9h.rcp.media.service.gsmtc.MediaBridgeClient;
import dev.tim9h.rcp.media.service.lastfm.LastFmService;
import dev.tim9h.rcp.media.service.lastfm.LastFmWatcher;
import dev.tim9h.rcp.settings.Settings;
import dev.tim9h.rcp.spi.CommandBuilder;
import dev.tim9h.rcp.spi.CommandNode;
import dev.tim9h.rcp.spi.Gravity;
import dev.tim9h.rcp.spi.Mode;
import dev.tim9h.rcp.spi.Plugin;
import dev.tim9h.rcp.spi.Position;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

public class MediaView implements Plugin {

	public static final String SETTING_LASTFM_APIKEY = "media.lastfm.apikey";

	public static final String SETTING_LASTFM_USERNAME = "media.lastfm.username";

	public static final String SETTING_LASTFM_POLLINGINTERVAL = "media.lastfm.pollinginterval";

	private static final String PROFILE = "profile";

	public static final String NOT_PLAYING = "Not playing";

	private static final String MODE_LASTFM = "lastfm";

	public static final String SETTING_MODES = "core.modes";

	@InjectLogger
	private Logger logger;

	@Inject
	private MediaBridgeClient mediaBridge;

	@Inject
	private Settings settings;

	@Inject
	private LastFmService lastFmService;

	@Inject
	private LastFmWatcher lastFmWatcher;

	@Inject
	private AnimatedLabel lblTitle;

	@Inject
	private AnimatedLabel lblArtist;

	@Inject
	private AnimatedLabel lblAlbum;

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
	public String getId() {
		return "media";
	}

	@Override
	public Optional<Node> getNode() throws IOException {
		lblTitle.getStyleClass().add("accent-label");
		for (var label : List.of(lblTitle, lblArtist, lblAlbum)) {
			label.setTextAlignment(TextAlignment.LEFT);
			label.setClipEnabled(false);
			label.getStyleClass().add("link");
		}

		btnPrev.setLabel('⏪');
		btnPrev.setOnAction(_ -> {
			mediaBridge.prevSong();
			eventManager.echo("Previous");
		});

		btnStop.setLabel('⏹');
		btnStop.setOnAction(_ -> {
			mediaBridge.stop();
			eventManager.echo("Stop");
		});

		btnPlay.setLabel('⏯');
		btnPlay.setOnAction(_ -> {
			mediaBridge.playPause();
			eventManager.echo("Play/Pause");
		});

		btnNext.setLabel('⏩');
		btnNext.setOnAction(_ -> {
			mediaBridge.nextSong();
			eventManager.echo("Next");
		});

		createCurrentTrackBindings();

		// add last.fm links
		onPrimaryClick(lblTitle, () -> {
			eventManager.post(new Event(Event.EVENT_CLI_REQUEST_FOCUS));
			if (currentTrack.getNowPlayingProperty().get()) {
				openUrl("https://www.last.fm/music/%s/_/%s", lblArtist.getText(), lblTitle.getText());
			} else {
				openUrl("https://www.last.fm/user/%s", lastFmService.getUsername());
			}
		});
		onPrimaryClick(lblArtist, () -> {
			eventManager.post(new Event(Event.EVENT_CLI_REQUEST_FOCUS));
			openUrl("https://www.last.fm/music/%s/", lblArtist.getText());
		});
		onPrimaryClick(lblAlbum, () -> {
			eventManager.post(new Event(Event.EVENT_CLI_REQUEST_FOCUS));
			openUrl("https://www.last.fm/music/%s/%s/", lblArtist.getText(), lblAlbum.getText());
		});

		var songInfo = new VBox(lblTitle, lblArtist, lblAlbum);
		songInfo.setMinWidth(0);

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

	private static void onPrimaryClick(Node node, Runnable action) {
		node.setOnMouseClicked(e -> {
			if (e.getButton() == MouseButton.PRIMARY) {
				action.run();
			}
		});
	}

	private static String orEmpty(String value) {
		return value == null ? StringUtils.EMPTY : value;
	}

	private void createCurrentTrackBindings() {
		Runnable updateLabels = () -> {
			var playing = currentTrack.getNowPlayingProperty().get();
			showText(lblTitle, playing ? orEmpty(currentTrack.getTitleProperty().get()) : NOT_PLAYING);
			showText(lblAlbum, playing ? orEmpty(currentTrack.getAlbumProperty().get()) : StringUtils.EMPTY);
			showText(lblArtist, playing ? orEmpty(currentTrack.getArtistProperty().get()) : StringUtils.EMPTY);
		};
		currentTrack.getTitleProperty().addListener((_, _, _) -> updateLabels.run());
		currentTrack.getAlbumProperty().addListener((_, _, _) -> updateLabels.run());
		currentTrack.getArtistProperty().addListener((_, _, _) -> updateLabels.run());
		currentTrack.getNowPlayingProperty().addListener((_, _, _) -> updateLabels.run());
		updateLabels.run();

		btnPlay.bindLabel(Bindings.when(currentTrack.getNowPlayingProperty()).then("⏸").otherwise("⏵"));
	}

	/**
	 * Shows the text and disables the label (no hover effect, no click handling)
	 * while there is nothing to link to.
	 */
	private static void showText(AnimatedLabel label, String text) {
		label.showText(text);
		label.setDisable(text.isBlank());
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
		setLabelsAnimated(true);
		if (settings.getStringSet(SETTING_MODES).contains(MODE_LASTFM)) {
			lastFmWatcher.startPolling();
		}
	}

	@Override
	public void onHidden() {
		// keep updating the track, but without animations while collapsed
		setLabelsAnimated(false);
		if (settings.getStringSet(SETTING_MODES).contains(MODE_LASTFM)) {
			lastFmWatcher.stopPolling();
		}
	}

	private void setLabelsAnimated(boolean animated) {
		for (var label : List.of(lblTitle, lblArtist, lblAlbum)) {
			label.setAnimated(animated);
		}
	}

	@Override
	public void onShutdown() {
		mediaBridge.shutdown();
	}

	@Override
	public Optional<CommandNode> getCommands() {
		return new CommandBuilder().command("next", data -> {
			var correlationId = extractCorrelationId(data);
			mediaBridge.nextSongWithResponse(correlationId);
			eventManager.echo("Playing next song");
		}).command("previous", data -> {
			var correlationId = extractCorrelationId(data);
			mediaBridge.prevSongWithResponse(correlationId);
			eventManager.echo("Playing previous song");
		}).command("play", data -> {
			var correlationId = extractCorrelationId(data);
			mediaBridge.playPauseWithResponse(correlationId);
			eventManager.echo("Play/Pause music");
		}).command("pause", data -> {
			var correlationId = extractCorrelationId(data);
			mediaBridge.playPauseWithResponse(correlationId);
			eventManager.echo("Play/Pause music");
		}).command("stop", data -> {
			var correlationId = extractCorrelationId(data);
			mediaBridge.stopWithResponse(correlationId);
			eventManager.echo("Stop playing music");
		}).command("np", data -> {
			if (data == null) {
				lastFmWatcher.updatePropertiesAsync();
			}
		}).command("volumeup", data -> {
			var correlationId = extractCorrelationId(data);
			mediaBridge.volumeUpWithResponse(correlationId);
			eventManager.echo("Volume up");
		}).command("volumedown", data -> {
			var correlationId = extractCorrelationId(data);
			mediaBridge.volumeDownWithResponse(correlationId);
			eventManager.echo("Volume down");
		}).command("mute", data -> {
			var correlationId = extractCorrelationId(data);
			mediaBridge.toggleMuteWithResponse(correlationId);
			eventManager.echo("Mute");
		}).build();
	}

	/**
	 * Extract correlation ID from event payload if present (first element). When
	 * using postRequest, the first element is the correlation ID.
	 */
	private String extractCorrelationId(String sData) {
		if (sData == null || sData.isBlank()) {
			return null;
		}
		var data = sData.split(" ");
		if (data != null && data.length > 0 && data[0] instanceof String firstElement
				&& firstElement.startsWith("webapi-")) {
			return firstElement;
		}
		return null;
	}

	@Override
	public void onSettingsChanged() {
		lastFmService.reloadSettings();
	}

	@Override
	public Optional<List<Mode>> getModes() {
		return Optional.of(Arrays.asList(new Mode() {

			@Override
			public String getName() {
				return MODE_LASTFM;
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
			public Optional<CommandNode> getModeCommands() {
				return new CommandBuilder().command(MODE_LASTFM).child("np", _ -> lastFmNowPlaying()).up()
						.child("username", _ -> {
							eventManager.echo("Last.fm username", settings.getString(SETTING_LASTFM_USERNAME));
						}).argumentAction(username -> {
							settings.persist(SETTING_LASTFM_USERNAME, username);
							eventManager.echo("Updated last.fm Username");
						}).up().child("apikey", _ -> {
							eventManager.echo("Last.fm API key", settings.getString(SETTING_LASTFM_APIKEY));
						}).argumentAction(apiKey -> {
							settings.persist(SETTING_LASTFM_APIKEY, apiKey);
							eventManager.echo("Updated last.fm API key");
						}).up().child(PROFILE, _ -> {
							openUrl("https://www.last.fm/user/%s", lastFmService.getUsername());
						}).build();
			}
		}));
	}

	private void lastFmNowPlaying() {
		eventManager.showWaitingIndicator();
		CompletableFuture.supplyAsync(() -> lastFmService.getCurrentTrack()).thenAccept(track -> {
			if (track != null) {
				if (track.isPlaying()) {
					eventManager.echo(track.artist(), track.title());
					eventManager.post(new Event("np", track.title(), track.artist(), track.album(), track.isPlaying()));
				} else {
					eventManager.echo("Not scrobbling");
					eventManager.post(new Event("np", StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, false));
				}
			}
		});
	}

	@Override
	public Map<String, String> getSettingsContributions() {
		HashMap<String, String> map = new HashMap<>();
		map.put(SETTING_LASTFM_APIKEY, StringUtils.EMPTY);
		map.put(SETTING_LASTFM_USERNAME, StringUtils.EMPTY);
		map.put(SETTING_LASTFM_POLLINGINTERVAL, "10000");
		return map;
	}

}