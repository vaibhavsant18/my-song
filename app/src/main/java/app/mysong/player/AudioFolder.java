package app.mysong.player;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AudioFolder {
    record Scan(List<Uri> audioFiles, int unreadableFolders) {}

    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME
    };
    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(java.util.Arrays.asList(
            "mp3", "m4a", "aac", "wav", "flac", "oga", "ogg", "opus", "amr", "aiff", "aif", "wma", "mka"));

    private AudioFolder() {}

    static Scan scan(ContentResolver resolver, Uri tree) {
        ArrayDeque<String> folders = new ArrayDeque<>();
        folders.add(DocumentsContract.getTreeDocumentId(tree));
        Set<String> visitedFolders = new HashSet<>();
        Set<String> seenFiles = new HashSet<>();
        List<Uri> files = new ArrayList<>();
        int unreadable = 0;
        while (!folders.isEmpty()) {
            String folder = folders.removeFirst();
            if (!visitedFolders.add(folder)) continue;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder);
            try (Cursor cursor = resolver.query(children, COLUMNS, null, null, null)) {
                if (cursor == null) {
                    unreadable++;
                    continue;
                }
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    if (id == null) continue;
                    String type = cursor.getString(1);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) {
                        folders.addLast(id);
                    } else if (isAudio(type, cursor.getString(2)) && seenFiles.add(id)) {
                        files.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    }
                }
            } catch (RuntimeException inaccessible) {
                unreadable++;
            }
        }
        return new Scan(files, unreadable);
    }

    private static boolean isAudio(String mime, String name) {
        if (mime != null && mime.startsWith("audio/")) return true;
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
