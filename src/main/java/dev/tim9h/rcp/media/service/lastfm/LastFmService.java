package dev.tim9h.rcp.media.service.lastfm;

import org.apache.commons.lang3.StringUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import de.umass.lastfm.User;
import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.media.MediaViewFactory;
import dev.tim9h.rcp.media.service.bean.Track;
import dev.tim9h.rcp.settings.Settings;

@Singleton
public class LastFmService {

	@Inject
	private Settings settings;

	@Inject
	private EventManager eventManager;

	private String apiKey;

	private String username;

	private String getApiKey() {
		if (StringUtils.isBlank(apiKey)) {
			apiKey = settings.getString(MediaViewFactory.SETTING_LASTFM_APIKEY);
		}
		return apiKey;
	}

	public String getUsername() {
		if (StringUtils.isBlank(username)) {
			username = settings.getString(MediaViewFactory.SETTING_LASTFM_USERNAME);
		}
		return username;
	}

	public Track getCurrentTrack() {
		if (StringUtils.isBlank(getUsername()) || StringUtils.isBlank(getApiKey())) {
			eventManager.echoAsync("Last.fm Service not configured");
			return null;
		}
		var iterator = User.getRecentTracks(getUsername(), getApiKey()).iterator();
		if (iterator.hasNext()) {
			var track = iterator.next();
			return new Track(track.getName(), track.getArtist(), track.getAlbum(), track.isNowPlaying());
		}
		return null;
	}

	public void reloadSettings() {
		apiKey = null;
		username = null;
	}

}
