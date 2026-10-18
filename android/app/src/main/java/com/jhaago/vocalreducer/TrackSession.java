package com.jhaago.vocalreducer;

import java.io.File;

public final class TrackSession {
    public final String id;
    public final String name;
    public final long frames;
    public final File directory;
    public TrackSession(String id, String name, long frames, File directory) {
        this.id = id;
        this.name = name;
        this.frames = frames;
        this.directory = directory;
    }
    public File original() { return new File(directory, "original.pcm"); }
    public File instrumental() { return new File(directory, "instrumental.pcm"); }
}
