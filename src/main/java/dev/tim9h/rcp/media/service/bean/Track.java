package dev.tim9h.rcp.media.service.bean;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

public record Track(String title, String artist, String album, boolean isPlaying) {

	public boolean matches(String title, String artist) {
		return matches(this.title, this.artist, title, artist);
	}

	public static boolean matches(String firstTitle, String firstArtist, String secondTitle, String secondArtist) {
		return StringUtils.isNotBlank(firstTitle) && StringUtils.isNotBlank(firstArtist)
				&& StringUtils.isNotBlank(secondTitle) && StringUtils.isNotBlank(secondArtist)
				&& Strings.CI.equals(StringUtils.trim(firstTitle), StringUtils.trim(secondTitle))
				&& Strings.CI.equals(StringUtils.trim(firstArtist), StringUtils.trim(secondArtist));
	}

}
