package app.mysong.player;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class LibraryStore extends SQLiteOpenHelper {
    record Track(String id, String title, String artist, String album, long duration) {}
    record Playlist(long id, String name, int count) {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static LibraryStore instance;

    private final File audioDirectory;
    private final AtomicInteger trackRevision = new AtomicInteger();

    static synchronized LibraryStore get(Context context) {
        if (instance == null) {
            instance = new LibraryStore(context.getApplicationContext());
        }
        return instance;
    }

    private LibraryStore(Context context) {
        super(context, "my-song.db", null, 1);
        audioDirectory = new File(context.getFilesDir(), "audio");
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, "
                + "artist TEXT NOT NULL, album TEXT NOT NULL, duration INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT COLLATE NOCASE UNIQUE NOT NULL)");
        db.execSQL("CREATE TABLE entries (playlist INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE, "
                + "track TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE, UNIQUE (playlist, track))");
        db.execSQL("CREATE INDEX entries_track ON entries(track)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        throw new IllegalStateException("Missing database migration from " + from + " to " + to);
    }

    List<Track> tracks(Long playlist) {
        String columns = "SELECT t.id, t.title, t.artist, t.album, t.duration FROM tracks t";
        String query = playlist == null
                ? columns + " ORDER BY t.title COLLATE NOCASE, t.id"
                : columns + " JOIN entries e ON t.id=e.track WHERE e.playlist=? ORDER BY e.rowid";
        String[] args = playlist == null ? null : new String[]{playlist.toString()};
        List<Track> tracks = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(query, args)) {
            while (cursor.moveToNext()) {
                tracks.add(new Track(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getLong(4)));
            }
        }
        return tracks;
    }

    List<Playlist> playlists() {
        List<Playlist> playlists = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT p.id, p.name, COUNT(e.track) FROM playlists p LEFT JOIN entries e ON p.id=e.playlist "
                        + "GROUP BY p.id ORDER BY p.name COLLATE NOCASE", null)) {
            while (cursor.moveToNext()) {
                playlists.add(new Playlist(cursor.getLong(0), cursor.getString(1), cursor.getInt(2)));
            }
        }
        return playlists;
    }

    long createPlaylist(String name) {
        ContentValues values = new ContentValues();
        values.put("name", name.trim());
        return getWritableDatabase().insertWithOnConflict("playlists", null, values,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    boolean renamePlaylist(long id, String name) {
        ContentValues values = new ContentValues();
        values.put("name", name.trim());
        return getWritableDatabase().updateWithOnConflict("playlists", values, "id=?",
                new String[]{Long.toString(id)}, SQLiteDatabase.CONFLICT_IGNORE) == 1;
    }

    void deletePlaylist(long id) {
        getWritableDatabase().delete("playlists", "id=?", new String[]{Long.toString(id)});
    }

    boolean addToPlaylist(long playlist, String track) {
        ContentValues values = new ContentValues();
        values.put("playlist", playlist);
        values.put("track", track);
        return getWritableDatabase().insertWithOnConflict("entries", null, values,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    void removeFromPlaylist(long playlist, String track) {
        getWritableDatabase().delete("entries", "playlist=? AND track=?",
                new String[]{Long.toString(playlist), track});
    }

    File audioFile(String id) {
        return new File(audioDirectory, id + ".audio");
    }

    int trackRevision() {
        return trackRevision.get();
    }

    synchronized void deleteTrack(String id) {
        getWritableDatabase().delete("tracks", "id=?", new String[]{id});
        audioFile(id).delete();
        trackRevision.incrementAndGet();
    }

    /** Copies one explicitly selected document into private storage. Returns false for duplicates. */
    synchronized boolean importTrack(ContentResolver resolver, Uri uri) throws IOException {
        if (!audioDirectory.isDirectory() && !audioDirectory.mkdirs()) {
            throw new IOException("Music storage is unavailable");
        }
        File temporary = File.createTempFile("import-", ".partial", audioDirectory);
        try {
            MessageDigest digest = sha256();
            try (InputStream input = resolver.openInputStream(uri);
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
                if (input == null) {
                    throw new IOException("The selected file could not be opened");
                }
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            String id = hex(digest.digest());
            File audio = audioFile(id);
            SQLiteDatabase db = getWritableDatabase();
            try (Cursor cursor = db.query("tracks", new String[]{"id"}, "id=?",
                    new String[]{id}, null, null, null)) {
                if (cursor.moveToFirst() && audio.isFile()) {
                    return false;
                }
            }

            ContentValues values = metadata(temporary, displayName(resolver, uri));
            values.put("id", id);
            if (!audio.isFile() && !temporary.renameTo(audio)) {
                throw new IOException("The audio copy could not be saved");
            }
            // IGNORE also lets a re-import restore the audio for a previously missing private file.
            db.insertWithOnConflict("tracks", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            trackRevision.incrementAndGet();
            return true;
        } finally {
            temporary.delete();
        }
    }

    private static ContentValues metadata(File audio, String displayName) throws IOException {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(audio.getAbsolutePath());
            String durationText = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration = durationText == null ? 0 : Long.parseLong(durationText);
            if (duration <= 0 || !"yes".equals(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) {
                throw new IOException("Unsupported or empty audio file");
            }
            ContentValues values = new ContentValues();
            values.put("title", tag(reader, MediaMetadataRetriever.METADATA_KEY_TITLE, displayName));
            values.put("artist", tag(reader, MediaMetadataRetriever.METADATA_KEY_ARTIST, ""));
            values.put("album", tag(reader, MediaMetadataRetriever.METADATA_KEY_ALBUM, ""));
            values.put("duration", duration);
            return values;
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new IOException("Unsupported or unreadable audio file", error);
        } finally {
            reader.release();
        }
    }

    private static String tag(MediaMetadataRetriever reader, int key, String fallback) {
        String value = reader.extractMetadata(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    int extension = name.lastIndexOf('.');
                    return extension > 0 ? name.substring(0, extension) : name;
                }
            }
        } catch (RuntimeException ignored) {
            // The provider can expose a readable stream without returning display metadata.
        }
        return "Untitled audio";
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = HEX[value >>> 4];
            result[index * 2 + 1] = HEX[value & 0xf];
        }
        return new String(result);
    }
}
