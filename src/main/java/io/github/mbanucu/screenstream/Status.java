package io.github.mbanucu.screenstream;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The stream's answers, written into the app's own external files directory.
 *
 * <p>This is a context of its own rather than whatever else the app runs: the
 * stream has to be able to say what it is doing while the app's accessibility
 * service is off, which is exactly the case when the screen capture was granted
 * with an app-op instead of the dialog. Nothing here needs a screen reader.
 */
final class Status {

    private static final String TAG = "ScreenStream";

    private Status() {
    }

    static void replace(Context context, String name, String body) {
        File directory = context.getExternalFilesDir(null);
        if (directory == null) {
            Log.w(TAG, "no files dir for " + name);
            return;
        }
        File file = new File(directory, name);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            Log.w(TAG, "could not write " + file, failed);
        }
    }
}
