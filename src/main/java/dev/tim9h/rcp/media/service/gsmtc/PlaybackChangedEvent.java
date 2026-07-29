package dev.tim9h.rcp.media.service.gsmtc;

import com.google.gson.annotations.SerializedName;

public record PlaybackChangedEvent(
		@SerializedName("event") String event,
		@SerializedName("state") String state,
		@SerializedName("position") long position,
		@SerializedName("duration") long duration) {

}
