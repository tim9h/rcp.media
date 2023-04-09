package dev.tim9h.rcp.media.service.playback;

import org.apache.logging.log4j.Logger;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.google.inject.Inject;

import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.media.service.lastfm.LastFmWatcher;

public class MediaService {

	@InjectLogger
	private Logger logger;

	@Inject
	private LastFmWatcher watcher;

	public void nextSong() {
		logger.debug(() -> "Firing next song key event");
		GlobalScreen.postNativeEvent(new NativeKeyEvent(2401, 0, 176, 57369, NativeKeyEvent.CHAR_UNDEFINED));
		watcher.updatePropertiesAsync();
	}

	public void prevSong() {
		logger.debug(() -> "Firing prev song key event");
		GlobalScreen.postNativeEvent(new NativeKeyEvent(2401, 0, 177, 57360, NativeKeyEvent.CHAR_UNDEFINED));
		watcher.updatePropertiesAsync();
	}

	public void playPause() {
		logger.debug(() -> "Firing play/pause key event");
		GlobalScreen.postNativeEvent(new NativeKeyEvent(2401, 0, 179, 57378, NativeKeyEvent.CHAR_UNDEFINED));
		watcher.updatePropertiesAsync();
	}

	public void stop() {
		logger.debug(() -> "Firing stop key event");
		GlobalScreen.postNativeEvent(new NativeKeyEvent(2401, 0, 178, 57380, NativeKeyEvent.CHAR_UNDEFINED));
		watcher.updatePropertiesAsync();
	}

}
