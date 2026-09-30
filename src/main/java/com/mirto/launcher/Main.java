package com.mirto.launcher;

/**
 * Bootstrap entry point for the packaged jar.
 *
 * The JDK launcher treats a Main-Class that extends javafx.application.Application
 * specially and requires JavaFX to be part of the JRE itself, ignoring bundled
 * classpath classes. Routing through this plain class lets the app's own main()
 * start JavaFX from the bundled (shaded) classpath instead.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        BridgeLinkLauncher.main(args);
    }
}
