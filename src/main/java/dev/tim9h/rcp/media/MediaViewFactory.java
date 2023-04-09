package dev.tim9h.rcp.media;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.google.inject.Inject;

import dev.tim9h.rcp.spi.CCard;
import dev.tim9h.rcp.spi.CCardFactory;

public class MediaViewFactory implements CCardFactory {

	public static final String SETTING_CURRENTTRACKINFOPATH = "media.currenttrackinfopath";

	public static final String SETTING_LASTFM_APIKEY = "media.lastfm.apikey";

	public static final String SETTING_LASTFM_USERNAME = "media.lastfm.username";

	public static final String SETTING_LASTFM_POLLINGINTERVAL = "media.lastfm.pollinginterval";

	@Inject
	private MediaView view;

	@Override
	public String getId() {
		return "mediaplayer";
	}

	@Override
	public CCard createCCard() {
		return view;
	}

	@Override
	public Map<String, String> getSettingsContributions() {
		HashMap<String, String> map = new HashMap<>();
		map.put(SETTING_CURRENTTRACKINFOPATH, StringUtils.EMPTY);
		map.put(SETTING_LASTFM_APIKEY, StringUtils.EMPTY);
		map.put(SETTING_LASTFM_USERNAME, StringUtils.EMPTY);
		map.put(SETTING_LASTFM_POLLINGINTERVAL, "10000");
		return map;
	}

}
