package app.mysong.player;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@UnstableApi
public final class PlaybackService extends MediaSessionService {
    private static final Pattern TRACK_ID = Pattern.compile("[a-f0-9]{64}");
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        ExoPlayer player = new ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(FileDataSource::new))
                .build();
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, player)
                .setSessionActivity(openApp)
                .setCallback(new LocalTracks())
                .build();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.getPlayer().release();
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    private final class LocalTracks implements MediaSession.Callback {
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession session,
                MediaSession.ControllerInfo controller, List<MediaItem> items) {
            List<MediaItem> resolved = new ArrayList<>(items.size());
            LibraryStore library = LibraryStore.get(PlaybackService.this);
            for (MediaItem item : items) {
                if (!TRACK_ID.matcher(item.mediaId).matches()) {
                    return Futures.immediateFailedFuture(new IllegalArgumentException("Invalid track ID"));
                }
                File audio = library.audioFile(item.mediaId);
                if (!audio.isFile()) {
                    return Futures.immediateFailedFuture(new IllegalArgumentException("Track is unavailable"));
                }
                resolved.add(item.buildUpon().setUri(Uri.fromFile(audio)).build());
            }
            return Futures.immediateFuture(resolved);
        }
    }
}
