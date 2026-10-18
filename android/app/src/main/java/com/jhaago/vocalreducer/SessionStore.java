package com.jhaago.vocalreducer;

import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class SessionStore {
    private final File sessions;
    private final AtomicFile metadata;
    public SessionStore(File filesDirectory) {
        sessions = new File(filesDirectory, "sessions");
        metadata = new AtomicFile(new File(filesDirectory, "last-session.json"));
    }
    public File directory(String id) throws IOException {
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid track identifier.");
        File dir = new File(sessions, id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create space for this track.");
        return dir;
    }
    public TrackSession load() {
        try {
            JSONObject json = new JSONObject(new String(metadata.readFully(), StandardCharsets.UTF_8));
            String id = json.getString("id");
            if (!id.matches("[a-f0-9-]{36}")) return null;
            TrackSession result = new TrackSession(id, json.getString("name"), json.getLong("frames"), new File(sessions, id));
            PcmFiles.validatePair(result.original(), result.instrumental(), result.frames);
            return result;
        } catch (Exception missingOrIncomplete) { return null; }
    }
    public void save(TrackSession session) throws Exception {
        PcmFiles.validatePair(session.original(), session.instrumental(), session.frames);
        JSONObject json = new JSONObject();
        json.put("id", session.id).put("name", session.name).put("frames", session.frames);
        FileOutputStream output = null;
        try {
            output = metadata.startWrite();
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            metadata.finishWrite(output);
        } catch (Exception error) {
            if (output != null) metadata.failWrite(output);
            throw error;
        }
    }
    public void cleanExcept(TrackSession keep) {
        File[] children = sessions.listFiles();
        if (children == null) return;
        for (File child : children) if (keep == null || !child.equals(keep.directory)) deleteTree(child);
    }
    public static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
