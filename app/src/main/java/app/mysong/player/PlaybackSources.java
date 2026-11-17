package app.mysong.player;

import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import java.io.IOException;

@UnstableApi
final class PlaybackSources implements MediaSource.Factory {
    private final MediaSource.Factory files = new DefaultMediaSourceFactory(FileDataSource::new);
    private final MediaSource.Factory radio;

    PlaybackSources() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("my-song")
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(false);
        // A live playlist can refer to other URLs. Keep those requests in the HTTPS data source.
        ResolvingDataSource.Factory streams = new ResolvingDataSource.Factory(http, request -> {
            if (RadioStream.parseLink(request.uri.toString()) == null) {
                throw new IOException("Radio URLs must use HTTPS");
            }
            return request;
        });
        radio = new DefaultMediaSourceFactory(streams);
    }

    @Override
    public MediaSource createMediaSource(MediaItem item) {
        return (RadioStream.isStation(item.mediaId) ? radio : files).createMediaSource(item);
    }

    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(DrmSessionManagerProvider provider) {
        files.setDrmSessionManagerProvider(provider);
        radio.setDrmSessionManagerProvider(provider);
        return this;
    }

    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy policy) {
        files.setLoadErrorHandlingPolicy(policy);
        radio.setLoadErrorHandlingPolicy(policy);
        return this;
    }

    @Override
    public int[] getSupportedTypes() {
        return radio.getSupportedTypes();
    }
}
