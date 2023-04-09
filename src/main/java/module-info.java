module rcp.clock {
	exports dev.tim9h.rcp.media;

	requires transitive rcp.api;
	requires com.google.guice;
	requires javafx.base;
	requires javafx.controls;
	requires javafx.graphics;
	requires java.desktop;
	requires com.github.kwhat.jnativehook;
	requires org.apache.commons.lang3;
	requires transitive rcp.controls;
	requires lastfm.java;
	requires org.apache.logging.log4j;
}