package dev.tim9h.rcp.media.service.gsmtc;

import com.google.gson.annotations.SerializedName;

public record MediaChangedEvent(
		@SerializedName("event") String event,
		@SerializedName("player") String player,
		@SerializedName("title") String title,
		@SerializedName("artist") String artist,
		@SerializedName("album") String album,
		@SerializedName("state") String state,
		@SerializedName("position") long position,
		@SerializedName("duration") long duration) {

}
