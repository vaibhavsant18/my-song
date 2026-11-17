package app.mysong.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@UnstableApi
final class PlaybackPanels {
    private final Activity activity;
    private final LibraryStore library;
    private final Supplier<? extends Player> player;
    private final Consumer<String> pickLyrics;
    private AlertDialog lyricsDialog;
    private ListView lyricsList;
    private LyricRows lyricRows;
    private String lyricTrack;

    PlaybackPanels(Activity activity, LibraryStore library, Supplier<? extends Player> player,
            Consumer<String> pickLyrics) {
        this.activity = activity;
        this.library = library;
        this.player = player;
        this.pickLyrics = pickLyrics;
    }

    void showLyrics() {
        Player current = player.get();
        MediaItem playing = current == null ? null : current.getCurrentMediaItem();
        if (playing == null) return;
        String track = playing.mediaId;
        List<TimedLyrics.Cue> cues = TimedLyrics.parse(library.lyrics(track));
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(title(playing))
                .setNegativeButton("Close", null)
                .setPositiveButton(cues.isEmpty() ? "Add .lrc file" : "Replace .lrc file",
                        (ignored, which) -> pickLyrics.accept(track));
        if (cues.isEmpty()) {
            dialog.setMessage("Choose a timed lyrics file for this track.").show();
            return;
        }

        lyricTrack = track;
        lyricsList = new ListView(activity);
        lyricsList.setDivider(null);
        lyricRows = new LyricRows(cues);
        lyricsList.setAdapter(lyricRows);
        lyricsList.setOnItemClickListener((parent, view, index, id) -> {
            Player active = player.get();
            MediaItem item = active == null ? null : active.getCurrentMediaItem();
            if (item != null && track.equals(item.mediaId) && active.isCurrentMediaItemSeekable()) {
                active.seekTo(cues.get(index).timeMs());
            }
        });
        lyricsDialog = dialog.setView(lyricsList).create();
        lyricsDialog.setOnDismissListener(ignored -> {
            lyricsDialog = null;
            lyricsList = null;
            lyricRows = null;
            lyricTrack = null;
        });
        lyricsDialog.show();
        updateLyrics();
    }

    void updateLyrics() {
        if (lyricsDialog == null) return;
        Player current = player.get();
        MediaItem playing = current == null ? null : current.getCurrentMediaItem();
        if (playing == null || !playing.mediaId.equals(lyricTrack)) {
            closeLyrics();
            return;
        }
        int index = TimedLyrics.indexAt(lyricRows.cues, current.getCurrentPosition());
        if (index == lyricRows.active) return;
        lyricRows.active = index;
        lyricRows.notifyDataSetChanged();
        lyricsList.setSelectionFromTop(Math.max(0, index), lyricsList.getHeight() / 3);
    }

    void closeLyrics() {
        if (lyricsDialog != null) lyricsDialog.dismiss();
    }

    void showQueue() {
        Player current = player.get();
        if (current == null || current.getCurrentMediaItem() == null) return;
        List<Integer> indices = upcoming(current);
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle("Upcoming queue").setNegativeButton("Close", null);
        if (indices.isEmpty()) {
            dialog.setMessage("The queue has no upcoming tracks.").show();
            return;
        }
        List<MediaItem> queued = new ArrayList<>(indices.size());
        for (int index : indices) queued.add(current.getMediaItemAt(index));
        String[] titles = queued.stream().map(PlaybackPanels::title).toArray(String[]::new);
        dialog.setItems(titles, (ignored, index) -> jumpTo(queued.get(index).mediaId))
                .setNeutralButton("Clear upcoming", (ignored, which) -> confirmClear()).show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity).setTitle("Clear upcoming tracks?")
                .setMessage("Your current track will keep playing.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear", (dialog, which) -> {
                    Player current = player.get();
                    if (current == null) return;
                    List<Integer> remaining = upcoming(current);
                    remaining.sort(Comparator.reverseOrder());
                    for (int index : remaining) current.removeMediaItem(index);
                }).show();
    }

    private void jumpTo(String id) {
        Player current = player.get();
        if (current == null) return;
        for (int index = 0; index < current.getMediaItemCount(); index++) {
            if (!current.getMediaItemAt(index).mediaId.equals(id)) continue;
            current.seekToDefaultPosition(index);
            if (current.getPlaybackState() == Player.STATE_IDLE) current.prepare();
            current.play();
            return;
        }
    }

    private static List<Integer> upcoming(Player player) {
        List<Integer> upcoming = new ArrayList<>();
        Timeline timeline = player.getCurrentTimeline();
        if (timeline.isEmpty()) return upcoming;
        boolean shuffle = player.getShuffleModeEnabled();
        int index = player.getCurrentMediaItemIndex();
        while ((index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)) != C.INDEX_UNSET) {
            upcoming.add(index);
        }
        return upcoming;
    }

    private static String title(MediaItem item) {
        CharSequence name = item.mediaMetadata.title;
        return name == null || name.length() == 0 ? "Audio" : name.toString();
    }

    private final class LyricRows extends BaseAdapter {
        private final List<TimedLyrics.Cue> cues;
        private int active = -1;

        LyricRows(List<TimedLyrics.Cue> cues) {
            this.cues = cues;
        }

        @Override public int getCount() { return cues.size(); }
        @Override public TimedLyrics.Cue getItem(int position) { return cues.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View existing, ViewGroup parent) {
            TextView line = existing == null ? new TextView(activity) : (TextView) existing;
            int horizontal = Math.round(24 * activity.getResources().getDisplayMetrics().density);
            int vertical = Math.round(12 * activity.getResources().getDisplayMetrics().density);
            line.setPadding(horizontal, vertical, horizontal, vertical);
            line.setTextSize(17);
            line.setText(getItem(position).text());
            line.setTypeface(null, position == active ? Typeface.BOLD : Typeface.NORMAL);
            line.setTextColor(position == active ? 0xffa31628 : 0xff171717);
            line.setBackgroundColor(position == active ? 0xfff6e4e5 : 0x00000000);
            return line;
        }
    }
}
