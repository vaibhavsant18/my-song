package app.mysong.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public final class MainActivity extends Activity implements Player.Listener {
    private enum Tab { LIBRARY, PLAYLISTS, LIVE }

    private static final int PICK_AUDIO = 100;
    private static final int PICK_FOLDER = 101;
    private static final int PICK_LYRICS = 102;
    private static final int BACKGROUND = 0xff15131c;
    private static final int SURFACE = 0xff23202e;
    private static final int PLAYER_SURFACE = 0xff2b2540;
    private static final int TEXT = 0xfff5f1fb;
    private static final int MUTED = 0xffc6bdd3;
    private static final int ACCENT = 0xffccbfff;
    private static final int ON_ACCENT = 0xff211737;
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            if (shownTrackRevision != library.trackRevision()) refreshLibrary();
            updateProgress();
            playbackPanels.updateLyrics();
            handler.postDelayed(this, 350);
        }
    };

    private LibraryStore library;
    private PlaybackPanels playbackPanels;
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private Tab tab = Tab.LIBRARY;
    private LibraryStore.Playlist openPlaylist;
    private List<LibraryStore.Track> visibleTracks = Collections.emptyList();
    private int shownTrackRevision;
    private boolean seeking;
    private boolean importing;
    private String pendingLyricsTrack;

    private Button add;
    private Button libraryTab;
    private Button playlistsTab;
    private Button liveTab;
    private Button sectionAction;
    private TextView sectionTitle;
    private TextView status;
    private TextView empty;
    private EditText search;
    private ListView list;
    private RowAdapter adapter;
    private LinearLayout playerCard;
    private LinearLayout timeline;
    private TextView nowTitle;
    private TextView nowArtist;
    private TextView position;
    private TextView duration;
    private SeekBar seek;
    private Button playPause;
    private Button previous;
    private Button next;
    private Button shuffle;
    private Button repeat;
    private Button lyrics;
    private Button queue;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        library = LibraryStore.get(this);
        playbackPanels = new PlaybackPanels(this, library, () -> controller, this::chooseLyrics);
        buildScreen();
        if (state != null) {
            Tab previousTab = state.getBoolean("playlistTab") ? Tab.PLAYLISTS : Tab.LIBRARY;
            tab = Tab.valueOf(state.getString("tab", previousTab.name()));
            pendingLyricsTrack = state.getString("pendingLyricsTrack");
            long id = state.getLong("playlistId", -1);
            if (id != -1) {
                openPlaylist = library.playlists().stream().filter(item -> item.id() == id)
                        .findFirst().orElse(null);
            }
            search.setText(state.getString("search", ""));
        }
        refreshLibrary();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("tab", tab.name());
        state.putLong("playlistId", openPlaylist == null ? -1 : openPlaylist.id());
        state.putString("search", search.getText().toString());
        state.putString("pendingLyricsTrack", pendingLyricsTrack);
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshLibrary();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        ListenableFuture<MediaController> pending = new MediaController.Builder(this, token).buildAsync();
        controllerFuture = pending;
        pending.addListener(() -> connect(pending), handler::post);
        handler.post(clock);
    }

    private void connect(ListenableFuture<MediaController> pending) {
        if (controllerFuture != pending) return;
        try {
            controller = pending.get();
            controller.addListener(this);
            updatePlayer();
        } catch (ExecutionException error) {
            showMessage("The player could not start. Reopen my-song to try again.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(clock);
        playbackPanels.closeLyrics();
        if (controller != null) controller.removeListener(this);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controller = null;
        controllerFuture = null;
        seeking = false;
        super.onStop();
    }

    private void buildScreen() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        root.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left + dp(18), safe.top + dp(12),
                        safe.right + dp(18), safe.bottom + dp(12));
                return insets;
            });
        }
        setContentView(root);

        LinearLayout header = row();
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_mysong);
        header.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView name = text(getString(R.string.app_name), 30, TEXT, true);
        LinearLayout.LayoutParams title = new LinearLayout.LayoutParams(0, -2, 1);
        title.leftMargin = dp(10);
        header.addView(name, title);
        add = action("+  Add music", ACCENT, ON_ACCENT, this::showImportChoices);
        header.addView(add, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        search = field("Search songs, artists, albums");
        search.setContentDescription("Search");
        margin(root, search, 14, 48);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshLibrary(); }
            @Override public void afterTextChanged(Editable value) {}
        });

        LinearLayout tabs = row();
        libraryTab = action("Library", ACCENT, ON_ACCENT, () -> changeTab(Tab.LIBRARY));
        playlistsTab = action("Playlists", SURFACE, TEXT, () -> changeTab(Tab.PLAYLISTS));
        liveTab = action("Live", SURFACE, TEXT, () -> changeTab(Tab.LIVE));
        for (Button button : new Button[]{libraryTab, playlistsTab, liveTab}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
            if (button != libraryTab) params.leftMargin = dp(8);
            tabs.addView(button, params);
        }
        margin(root, tabs, 10, 46);

        LinearLayout section = row();
        sectionTitle = text("All songs", 21, TEXT, true);
        sectionTitle.setSingleLine();
        sectionTitle.setEllipsize(TextUtils.TruncateAt.END);
        section.addView(sectionTitle, new LinearLayout.LayoutParams(0, -2, 1));
        sectionAction = action("New playlist", SURFACE, ACCENT, () -> {
            if (tab == Tab.LIVE) stationDialog(null);
            else if (openPlaylist == null) playlistName(null, null);
            else showPlaylistMenu(sectionAction, openPlaylist);
        });
        section.addView(sectionAction, new LinearLayout.LayoutParams(-2, dp(48)));
        margin(root, section, 12, 48);

        status = text("", 13, ACCENT, false);
        status.setMaxLines(3);
        status.setVisibility(View.GONE);
        root.addView(status);

        FrameLayout content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setItemsCanFocus(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(8));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, index, id) -> selectRow(index));
        empty = text("", 16, MUTED, false);
        empty.setMaxLines(3);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(16), dp(20), dp(16));
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        playerCard = buildPlayer();
        playerCard.setVisibility(View.GONE);
        margin(root, playerCard, 6, -2);
        root.requestFocus();
    }

    private LinearLayout buildPlayer() {
        LinearLayout card = column();
        card.setPadding(dp(13), dp(10), dp(13), dp(8));
        card.setBackground(shape(PLAYER_SURFACE, 20));
        LinearLayout track = row();
        ImageView artwork = new ImageView(this);
        artwork.setImageResource(R.drawable.ic_mysong);
        track.addView(artwork, new LinearLayout.LayoutParams(dp(39), dp(39)));
        LinearLayout captions = column();
        nowTitle = text("", 16, TEXT, true);
        nowArtist = text("", 12, MUTED, false);
        captions.addView(nowTitle);
        captions.addView(nowArtist);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(0, -2, 1);
        captionParams.leftMargin = dp(10);
        track.addView(captions, captionParams);
        lyrics = action("Lyrics", PLAYER_SURFACE, ACCENT, playbackPanels::showLyrics);
        queue = action("Queue", PLAYER_SURFACE, ACCENT, playbackPanels::showQueue);
        track.addView(lyrics, new LinearLayout.LayoutParams(-2, dp(48)));
        track.addView(queue, new LinearLayout.LayoutParams(-2, dp(48)));
        card.addView(track);

        timeline = row();
        position = text("0:00", 12, MUTED, false);
        duration = text("0:00", 12, MUTED, false);
        duration.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        timeline.addView(position, new LinearLayout.LayoutParams(dp(42), -2));
        seek = new SeekBar(this);
        seek.setContentDescription("Playback position");
        seek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                position.setText(time(value));
                if (!seeking && controller != null) controller.seekTo(value);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (controller != null) controller.seekTo(bar.getProgress());
                seeking = false;
            }
        });
        timeline.addView(seek, new LinearLayout.LayoutParams(0, dp(42), 1));
        timeline.addView(duration, new LinearLayout.LayoutParams(dp(42), -2));
        card.addView(timeline);

        LinearLayout controls = row();
        shuffle = action("Shuffle", PLAYER_SURFACE, MUTED, () -> {
            if (controller != null) controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled());
        });
        previous = action("|◀", PLAYER_SURFACE, TEXT, () -> {
            if (controller != null) controller.seekToPrevious();
        });
        playPause = action("▶", ACCENT, ON_ACCENT, this::togglePlayback);
        next = action("▶|", PLAYER_SURFACE, TEXT, () -> {
            if (controller != null) controller.seekToNextMediaItem();
        });
        repeat = action("Repeat", PLAYER_SURFACE, MUTED, () -> {
            if (controller == null) return;
            controller.setRepeatMode(controller.getRepeatMode() == Player.REPEAT_MODE_OFF
                    ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        });
        previous.setContentDescription("Previous track");
        next.setContentDescription("Next track");
        for (Button control : new Button[]{shuffle, previous, playPause, next, repeat}) {
            controls.addView(control, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        card.addView(controls);
        return card;
    }

    private void showImportChoices() {
        PopupMenu choices = new PopupMenu(this, add);
        choices.getMenu().add("Add files").setOnMenuItemClickListener(item -> {
            chooseAudio();
            return true;
        });
        choices.getMenu().add("Add folder").setOnMenuItemClickListener(item -> {
            openPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
            return true;
        });
        choices.show();
    }

    private void chooseAudio() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        openPicker(picker, PICK_AUDIO);
    }

    private void chooseLyrics(String track) {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        if (openPicker(picker, PICK_LYRICS)) pendingLyricsTrack = track;
    }

    private boolean openPicker(Intent picker, int request) {
        try {
            startActivityForResult(picker, request);
            return true;
        } catch (ActivityNotFoundException error) {
            showMessage("A file picker is unavailable on this device.");
            return false;
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_LYRICS) {
            String track = pendingLyricsTrack;
            pendingLyricsTrack = null;
            if (result == RESULT_OK && data != null && data.getData() != null && track != null) {
                Uri file = data.getData();
                FILES.execute(() -> importLyrics(track, file));
            }
            return;
        }
        if (result != RESULT_OK || data == null) return;
        if (request == PICK_FOLDER && data.getData() != null) {
            Uri folder = data.getData();
            startImport("Scanning folder…");
            FILES.execute(() -> {
                AudioFolder.Scan scan;
                try {
                    scan = AudioFolder.scan(getContentResolver(), folder);
                } catch (RuntimeException error) {
                    finishImport("The selected folder could not be read.");
                    return;
                }
                importAudio(scan.audioFiles(), scan.unreadableFolders());
            });
            return;
        }
        if (request != PICK_AUDIO) return;
        Set<Uri> selected = new LinkedHashSet<>();
        if (data.getData() != null) selected.add(data.getData());
        ClipData clips = data.getClipData();
        if (clips != null) {
            for (int index = 0; index < clips.getItemCount(); index++) {
                Uri uri = clips.getItemAt(index).getUri();
                if (uri != null) selected.add(uri);
            }
        }
        if (selected.isEmpty()) return;
        startImport("Importing music…");
        FILES.execute(() -> importAudio(selected, 0));
    }

    private void startImport(String progress) {
        importing = true;
        add.setEnabled(false);
        status.setText(progress);
        status.setVisibility(View.VISIBLE);
    }

    private void importAudio(Collection<Uri> selected, int unreadableFolders) {
        int added = 0;
        int copies = 0;
        int unavailable = 0;
        for (Uri uri : selected) {
            try {
                if (library.importTrack(getContentResolver(), uri)) added++;
                else copies++;
            } catch (IOException | RuntimeException error) {
                unavailable++;
            }
        }
        String message = selected.isEmpty() && unreadableFolders == 0 ? "No audio files found in the folder."
                : "Added " + added + (added == 1 ? " track" : " tracks");
        if (copies > 0) message += " · " + copies + " already in library";
        if (unavailable > 0) message += " · " + unavailable + " could not be imported";
        if (unreadableFolders > 0) message += " · " + unreadableFolders + " folders could not be read";
        finishImport(message);
    }

    private void finishImport(String message) {
        handler.post(() -> {
            if (isDestroyed()) return;
            importing = false;
            add.setEnabled(true);
            status.setText(message);
            refreshLibrary();
        });
    }

    private void importLyrics(String track, Uri uri) {
        String message;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            String content = TimedLyrics.read(input);
            message = library.setLyrics(track, content) ? "Synced lyrics saved" : "This track is no longer in the library";
        } catch (IOException | RuntimeException error) {
            message = "Choose a readable .lrc file with timed lyrics";
        }
        String result = message;
        handler.post(() -> {
            if (!isDestroyed()) showMessage(result);
        });
    }

    private void changeTab(Tab selected) {
        tab = selected;
        openPlaylist = null;
        resetSearch();
    }

    @Override
    public void onBackPressed() {
        if (openPlaylist != null) changeTab(Tab.PLAYLISTS);
        else super.onBackPressed();
    }

    private void resetSearch() {
        search.setText("");
        if (!importing) status.setVisibility(View.GONE);
        refreshLibrary();
        list.setSelection(0);
    }

    private void refreshLibrary() {
        shownTrackRevision = library.trackRevision();
        boolean live = tab == Tab.LIVE;
        boolean playlistIndex = tab == Tab.PLAYLISTS && openPlaylist == null;
        boolean inPlaylist = openPlaylist != null;
        styleTab(libraryTab, tab == Tab.LIBRARY);
        styleTab(playlistsTab, tab == Tab.PLAYLISTS);
        styleTab(liveTab, live);
        add.setVisibility(live ? View.GONE : View.VISIBLE);
        sectionTitle.setText(live ? "Live radio" : inPlaylist ? openPlaylist.name()
                : playlistIndex ? "Your playlists" : "All songs");
        sectionAction.setVisibility(tab == Tab.LIBRARY ? View.GONE : View.VISIBLE);
        sectionAction.setText(live ? "Add station" : inPlaylist ? "Playlist options" : "New playlist");
        search.setHint(live ? "Search stations" : playlistIndex ? "Search playlists" : "Search songs, artists, albums");

        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        rows.clear();
        if (live) {
            visibleTracks = Collections.emptyList();
            for (LibraryStore.Station station : library.stations()) {
                URI link = RadioStream.parseLink(station.url());
                String host = link == null ? "Live radio" : link.getHost();
                if (matches(station.name() + " " + host, query)) {
                    rows.add(new Row(station.name(), host, null, null, station));
                }
            }
        } else if (playlistIndex) {
            visibleTracks = Collections.emptyList();
            for (LibraryStore.Playlist playlist : library.playlists()) {
                if (matches(playlist.name(), query)) {
                    rows.add(new Row(playlist.name(), playlist.count() + (playlist.count() == 1 ? " song" : " songs"),
                            null, playlist, null));
                }
            }
        } else {
            List<LibraryStore.Track> tracks = new ArrayList<>();
            for (LibraryStore.Track track : library.tracks(inPlaylist ? openPlaylist.id() : null)) {
                String info = description(track);
                if (matches(track.title() + " " + info, query)) {
                    tracks.add(track);
                    String subtitle = info.isEmpty() ? time(track.duration()) : info + " · " + time(track.duration());
                    rows.add(new Row(track.title(), subtitle, track, null, null));
                }
            }
            visibleTracks = tracks;
        }
        empty.setText(!query.isEmpty() ? "No matches"
                : live ? "Add a station’s direct stream link to listen live."
                : playlistIndex ? "Create a playlist to group your songs."
                : inPlaylist ? "Add songs from the Library using the menu beside each track."
                : "Add audio files to start your offline library.");
        adapter.notifyDataSetChanged();
    }

    private void styleTab(Button button, boolean selected) {
        setButtonStyle(button, selected ? ACCENT : SURFACE, selected ? ON_ACCENT : TEXT);
        button.setSelected(selected);
    }

    private void selectRow(int index) {
        Row row = rows.get(index);
        if (row.playlist() != null) {
            openPlaylist = row.playlist();
            resetSearch();
        } else if (controller == null) {
            showMessage("The player is starting. Try again in a moment.");
        } else if (row.station() != null) {
            tuneIn(new MediaItem.Builder().setMediaId(RadioStream.mediaId(row.station().id()))
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(row.station().name()).build())
                    .build());
        } else {
            List<MediaItem> queue = new ArrayList<>(visibleTracks.size());
            for (LibraryStore.Track track : visibleTracks) {
                queue.add(new MediaItem.Builder().setMediaId(track.id())
                        .setMediaMetadata(new MediaMetadata.Builder().setTitle(track.title())
                                .setArtist(track.artist()).setAlbumTitle(track.album()).build())
                        .build());
            }
            controller.setMediaItems(queue, index, 0);
            controller.prepare();
            controller.play();
        }
    }

    private void stationDialog(LibraryStore.Station station) {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), dp(4));
        EditText name = field("Station name");
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        EditText url = field("HTTPS stream URL");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        if (station != null) {
            name.setText(station.name());
            url.setText(station.url());
        }
        margin(form, name, 0, 52);
        margin(form, url, 8, 52);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(station == null ? "Add station" : "Edit station")
                .setMessage("Use a direct MP3 or AAC stream, or an HLS link ending in .m3u8.")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) {
                name.setError("Enter a station name");
                return;
            }
            URI link = RadioStream.parseLink(url.getText().toString());
            if (link == null) {
                url.setError("Enter a direct HTTPS stream URL");
                return;
            }
            String stream = link.toASCIIString();
            if (!library.saveStation(station, label, stream)) {
                url.setError("This stream is already saved");
                return;
            }
            if (station != null && isCurrentStation(station.id()) && !station.url().equals(stream)) stopRadio();
            dialog.dismiss();
            refreshLibrary();
        }));
        dialog.show();
    }

    private void showStationMenu(View anchor, LibraryStore.Station station) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Edit station").setOnMenuItemClickListener(item -> {
            stationDialog(station);
            return true;
        });
        menu.getMenu().add("Remove station").setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle("Remove this station?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> {
                        if (isCurrentStation(station.id())) stopRadio();
                        library.deleteStation(station.id());
                        refreshLibrary();
                    }).show();
            return true;
        });
        menu.show();
    }

    private boolean isCurrentStation(long id) {
        MediaItem item = controller == null ? null : controller.getCurrentMediaItem();
        return item != null && RadioStream.mediaId(id).equals(item.mediaId);
    }

    private void tuneIn(MediaItem station) {
        controller.setMediaItem(station);
        controller.prepare();
        controller.play();
    }

    private void stopRadio() {
        controller.stop();
        controller.clearMediaItems();
    }

    private void showTrackMenu(View anchor, LibraryStore.Track track) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean hasLyrics = library.lyrics(track.id()) != null;
        menu.getMenu().add(hasLyrics ? "Replace synced lyrics" : "Add synced lyrics")
                .setOnMenuItemClickListener(item -> {
                    chooseLyrics(track.id());
                    return true;
                });
        if (hasLyrics) {
            menu.getMenu().add("Remove synced lyrics").setOnMenuItemClickListener(item -> {
                new AlertDialog.Builder(this).setTitle("Remove synced lyrics?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> {
                            if (library.setLyrics(track.id(), null)) showMessage("Synced lyrics removed");
                        }).show();
                return true;
            });
        }
        menu.getMenu().add("Add to playlist").setOnMenuItemClickListener(item -> {
            choosePlaylist(track.id());
            return true;
        });
        if (openPlaylist != null) {
            long id = openPlaylist.id();
            menu.getMenu().add("Remove from playlist").setOnMenuItemClickListener(item -> {
                library.removeFromPlaylist(id, track.id());
                refreshLibrary();
                return true;
            });
        }
        menu.getMenu().add("Remove from library").setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle("Remove this song?")
                    .setMessage("This removes the imported copy and its playlist entries. Your original file stays where it is.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove", (dialog, which) -> deleteTrack(track.id())).show();
            return true;
        });
        menu.show();
    }

    private void deleteTrack(String id) {
        if (controller != null) {
            for (int index = controller.getMediaItemCount() - 1; index >= 0; index--) {
                if (id.equals(controller.getMediaItemAt(index).mediaId)) controller.removeMediaItem(index);
            }
        }
        FILES.execute(() -> library.deleteTrack(id));
    }

    private void choosePlaylist(String track) {
        List<LibraryStore.Playlist> playlists = library.playlists();
        if (playlists.isEmpty()) {
            playlistName(null, track);
            return;
        }
        String[] names = playlists.stream().map(LibraryStore.Playlist::name).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Add to playlist")
                .setItems(names, (dialog, index) -> {
                    showMessage(library.addToPlaylist(playlists.get(index).id(), track)
                            ? "Added to " + names[index] : "Already in " + names[index]);
                    refreshLibrary();
                })
                .setPositiveButton("New playlist", (dialog, which) -> playlistName(null, track))
                .setNegativeButton("Cancel", null).show();
    }

    private void showPlaylistMenu(View anchor, LibraryStore.Playlist playlist) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Rename playlist").setOnMenuItemClickListener(item -> {
            playlistName(playlist, null);
            return true;
        });
        menu.getMenu().add("Delete playlist").setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle("Delete playlist?")
                    .setMessage("The songs will stay in your library.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (dialog, which) -> {
                        library.deletePlaylist(playlist.id());
                        if (openPlaylist != null && openPlaylist.id() == playlist.id()) changeTab(Tab.PLAYLISTS);
                        else refreshLibrary();
                    }).show();
            return true;
        });
        menu.show();
    }

    private void playlistName(LibraryStore.Playlist playlist, String track) {
        EditText input = field("Playlist name");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        if (playlist != null) {
            input.setText(playlist.name());
            input.selectAll();
        }
        FrameLayout form = new FrameLayout(this);
        form.setPadding(dp(20), dp(8), dp(20), dp(4));
        form.addView(input, new FrameLayout.LayoutParams(-1, dp(52)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(playlist == null ? "New playlist" : "Rename playlist")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("Enter a playlist name");
                return;
            }
            long id = playlist == null ? library.createPlaylist(name)
                    : library.renamePlaylist(playlist.id(), name) ? playlist.id() : -1;
            if (id == -1) {
                input.setError("A playlist with that name already exists");
                return;
            }
            if (track != null) {
                library.addToPlaylist(id, track);
                showMessage("Added to " + name);
            }
            if (openPlaylist != null && openPlaylist.id() == id) {
                openPlaylist = new LibraryStore.Playlist(id, name, openPlaylist.count());
            }
            dialog.dismiss();
            refreshLibrary();
        }));
        dialog.show();
    }

    private void togglePlayback() {
        if (controller == null) return;
        MediaItem item = controller.getCurrentMediaItem();
        if (item != null && RadioStream.isStation(item.mediaId)) {
            if (tuningOrPlaying()) stopRadio();
            else tuneIn(item);
            return;
        }
        if (controller.getPlayWhenReady() && controller.getPlaybackState() != Player.STATE_ENDED) {
            controller.pause();
        } else {
            if (controller.getPlaybackState() == Player.STATE_ENDED) controller.seekTo(0);
            if (controller.getPlaybackState() == Player.STATE_IDLE) controller.prepare();
            controller.play();
        }
    }

    @Override
    public void onEvents(Player player, Player.Events events) {
        updatePlayer();
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        MediaItem item = controller == null ? null : controller.getCurrentMediaItem();
        showMessage(item != null && RadioStream.isStation(item.mediaId)
                ? "Could not connect to the station. Check its stream URL and your connection."
                : "This audio file could not be played.");
    }

    private void updatePlayer() {
        MediaItem item = controller == null ? null : controller.getCurrentMediaItem();
        playerCard.setVisibility(item == null ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
        if (item == null) return;
        boolean radio = RadioStream.isStation(item.mediaId);
        int trackControls = radio ? View.GONE : View.VISIBLE;
        for (View control : new View[]{lyrics, queue, timeline, shuffle, previous, next, repeat}) {
            control.setVisibility(trackControls);
        }
        if (radio) {
            updateRadio(item);
            return;
        }
        MediaMetadata metadata = controller.getMediaMetadata();
        nowTitle.setText(metadata.title == null ? "Audio" : metadata.title);
        nowArtist.setText(TextUtils.isEmpty(metadata.artist) ? "Playing from your library" : metadata.artist);
        boolean playing = controller.getPlayWhenReady() && controller.getPlaybackState() != Player.STATE_ENDED;
        playPause.setText(playing ? "Ⅱ" : "▶");
        playPause.setContentDescription(playing ? "Pause" : "Play");
        shuffle.setTextColor(controller.getShuffleModeEnabled() ? ACCENT : MUTED);
        shuffle.setContentDescription(controller.getShuffleModeEnabled() ? "Shuffle on" : "Shuffle off");
        repeat.setTextColor(controller.getRepeatMode() == Player.REPEAT_MODE_ALL ? ACCENT : MUTED);
        repeat.setContentDescription(controller.getRepeatMode() == Player.REPEAT_MODE_ALL ? "Repeat on" : "Repeat off");
        next.setEnabled(controller.hasNextMediaItem());
        updateProgress();
    }

    private void updateRadio(MediaItem item) {
        CharSequence station = item.mediaMetadata.title;
        nowTitle.setText(TextUtils.isEmpty(station) ? "Live radio" : station);
        boolean active = tuningOrPlaying();
        playPause.setText(active ? "Stop radio" : "Play live");
        playPause.setContentDescription(active ? "Stop live radio" : "Play live radio");
        CharSequence program = controller.getMediaMetadata().title;
        if (controller.getPlayerError() != null) {
            nowArtist.setText("Station unavailable");
        } else if (active && controller.getPlaybackState() == Player.STATE_BUFFERING) {
            nowArtist.setText("Connecting…");
        } else if (active) {
            nowArtist.setText(TextUtils.isEmpty(program) || TextUtils.equals(station, program)
                    ? "Live radio" : program);
        } else {
            nowArtist.setText("Live radio · Stopped");
        }
    }

    private boolean tuningOrPlaying() {
        int state = controller.getPlaybackState();
        return controller.getPlayWhenReady() && (state == Player.STATE_BUFFERING || state == Player.STATE_READY);
    }

    private void updateProgress() {
        MediaItem item = controller == null ? null : controller.getCurrentMediaItem();
        if (item == null || RadioStream.isStation(item.mediaId)) return;
        long total = Math.max(0, controller.getDuration());
        long elapsed = Math.max(0, controller.getCurrentPosition());
        duration.setText(time(total));
        seek.setEnabled(total > 0 && controller.isCurrentMediaItemSeekable());
        if (!seeking) {
            position.setText(time(elapsed));
            seek.setMax((int) Math.min(Integer.MAX_VALUE, Math.max(1, total)));
            seek.setProgress((int) Math.min(Integer.MAX_VALUE, elapsed));
        }
    }

    private record Row(String title, String subtitle, LibraryStore.Track track,
            LibraryStore.Playlist playlist, LibraryStore.Station station) {}

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View existing, ViewGroup parent) {
            RowView view = existing == null ? new RowView() : (RowView) existing;
            view.setRow(getItem(position));
            return view;
        }
    }

    private final class RowView extends LinearLayout {
        private final TextView icon;
        private final TextView title;
        private final TextView subtitle;
        private final Button options;

        RowView() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(62));
            setPadding(dp(2), dp(4), 0, dp(4));
            icon = text("", 21, ACCENT, true);
            icon.setGravity(Gravity.CENTER);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setBackground(shape(SURFACE, 11));
            addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout captions = column();
            title = text("", 16, TEXT, true);
            subtitle = text("", 13, MUTED, false);
            captions.addView(title);
            captions.addView(subtitle);
            LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(0, -2, 1);
            middle.leftMargin = dp(12);
            addView(captions, middle);
            options = action("⋮", BACKGROUND, MUTED, () -> {});
            options.setFocusable(false);
            addView(options, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }

        void setRow(Row row) {
            title.setText(row.title());
            subtitle.setText(row.subtitle());
            MediaItem current = controller == null ? null : controller.getCurrentMediaItem();
            String mediaId = row.station() != null ? RadioStream.mediaId(row.station().id())
                    : row.track() == null ? null : row.track().id();
            boolean active = current != null && current.mediaId.equals(mediaId);
            icon.setText(active ? "▶" : row.station() != null ? "◉" : row.track() == null ? "≡" : "♪");
            options.setContentDescription("Options for " + row.title());
            options.setOnClickListener(view -> {
                if (row.station() != null) showStationMenu(view, row.station());
                else if (row.playlist() != null) showPlaylistMenu(view, row.playlist());
                else showTrackMenu(view, row.track());
            });
        }
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView text(CharSequence value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setMaxLines(1);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private EditText field(String hint) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setHint(hint);
        view.setTextSize(15);
        view.setTextColor(TEXT);
        view.setHintTextColor(MUTED);
        view.setPadding(dp(14), 0, dp(12), 0);
        view.setBackground(shape(SURFACE, 12));
        view.setSelectAllOnFocus(false);
        return view;
    }

    private Button action(String label, int background, int foreground, Runnable callback) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(5), 0, dp(5), 0);
        setButtonStyle(button, background, foreground);
        button.setOnClickListener(view -> callback.run());
        return button;
    }

    private void setButtonStyle(Button button, int background, int foreground) {
        button.setTextColor(foreground);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff), shape(background, 13), null));
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private void margin(LinearLayout parent, View view, int top, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height < 0 ? height : dp(height));
        params.topMargin = dp(top);
        parent.addView(view, params);
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String description(LibraryStore.Track item) {
        if (item.artist().isEmpty()) return item.album();
        return item.album().isEmpty() ? item.artist() : item.artist() + " · " + item.album();
    }

    private static boolean matches(String text, String query) {
        return text.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String time(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds / 60) % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60);
    }
}
