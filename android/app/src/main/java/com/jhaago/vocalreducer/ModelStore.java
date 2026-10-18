package com.jhaago.vocalreducer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;
import javax.net.ssl.HttpsURLConnection;

/** Only the public model is downloaded. This class never receives a song or song URI. */
public final class ModelStore {
    public static final long MODEL_BYTES = 83_994_361L;
    public static final String MODEL_SHA256 = "72b17c42d308982ddb5069bc3bf48b81a5aac4cb6516e4366c0fa7cef6df0064";
    public static final String MODEL_URL = "https://huggingface.co/datasets/Retrobear/demucs.cpp/resolve/8f58ac0491bbea657275bcd3e38af1ab3a27bfc9/ggml-model-htdemucs-4s-f16.bin";
    public interface Progress {
        boolean isCancelled();
        void onProgress(float fraction, String stage);
    }

    private ModelStore() {}

    public static boolean isDownloaded(File directory) {
        File model = new File(directory, "htdemucs-4s-f16.bin");
        return model.isFile() && model.length() == MODEL_BYTES;
    }

    public static File ensure(File directory, Progress progress) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create the model folder.");
        File model = new File(directory, "htdemucs-4s-f16.bin");
        File partial = new File(directory, "htdemucs-4s-f16.bin.partial");
        checkCancel(progress);
        if (model.isFile()) {
            progress.onProgress(0f, "Checking the offline model");
            if (model.length() == MODEL_BYTES && MODEL_SHA256.equals(hash(model, progress))) return model;
            if (!model.delete()) throw new IOException("Could not replace the incomplete model. Clear app storage and retry.");
        }
        if (directory.getUsableSpace() < MODEL_BYTES + 64L * 1024 * 1024) {
            throw new IOException("Free at least 150 MB on your phone, then try again.");
        }
        if (partial.exists() && !partial.delete()) throw new IOException("Could not clear the interrupted model download.");
        HttpsURLConnection connection = null;
        try {
            progress.onProgress(0f, "Downloading the offline model · 80 MB");
            URL url = new URL(MODEL_URL);
            for (int redirect = 0; redirect <= 6; redirect++) {
                checkCancel(progress);
                if (!url.getProtocol().equals("https")) throw new IOException("The model download requires a secure connection.");
                connection = (HttpsURLConnection) url.openConnection();
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(15_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "VocalReducer/0.1");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect(); connection = null;
                    if (location == null || redirect == 6) throw new IOException("The model download redirected too many times. Please retry later.");
                    url = new URL(url, location);
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("The model server is unavailable (" + code + "). Please retry later.");
                break;
            }
            if (connection == null) throw new IOException("Could not connect to the model download.");
            long advertised = connection.getContentLengthLong();
            if (advertised > 0 && advertised != MODEL_BYTES) throw new IOException("The model download has an unexpected size. Please retry later.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream(), 65536);
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    checkCancel(progress);
                    total += length;
                    if (total > MODEL_BYTES) throw new IOException("The model download was larger than expected.");
                    digest.update(buffer, 0, length);
                    output.write(buffer, 0, length);
                    progress.onProgress((float) total / MODEL_BYTES, "Downloading the offline model · 80 MB");
                }
                output.getFD().sync();
            }
            checkCancel(progress);
            if (total != MODEL_BYTES || !MODEL_SHA256.equals(hex(digest.digest()))) {
                throw new IOException("The model download did not pass its integrity check. Connect to the internet and try again.");
            }
            if (!partial.renameTo(model)) throw new IOException("Could not save the offline model.");
            progress.onProgress(1f, "Offline model ready");
            return model;
        } finally {
            if (connection != null) connection.disconnect();
            if (partial.exists()) partial.delete();
        }
    }

    private static String hash(File file, Progress progress) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancel(progress);
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return value.toString();
    }

    private static void checkCancel(Progress progress) {
        if (Thread.currentThread().isInterrupted() || progress.isCancelled()) throw new CancellationException("Cancelled");
    }
}
