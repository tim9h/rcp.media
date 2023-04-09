package dev.tim9h.rcp.media.service.bean;

import com.google.inject.Singleton;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

@Singleton
public class CurrentTrackProperties {

	private StringProperty titleProperty;

	private StringProperty artistProperty;

	private StringProperty albumProperty;

	private BooleanProperty nowPlayingProperty;

	public StringProperty getTitleProperty() {
		if (titleProperty == null) {
			titleProperty = new SimpleStringProperty();
		}
		return titleProperty;
	}

	public StringProperty getArtistProperty() {
		if (artistProperty == null) {
			artistProperty = new SimpleStringProperty();
		}
		return artistProperty;
	}

	public StringProperty getAlbumProperty() {
		if (albumProperty == null) {
			albumProperty = new SimpleStringProperty();
		}
		return albumProperty;
	}

	public BooleanProperty getNowPlayingProperty() {
		if (nowPlayingProperty == null) {
			nowPlayingProperty = new SimpleBooleanProperty();
		}
		return nowPlayingProperty;
	}

}
