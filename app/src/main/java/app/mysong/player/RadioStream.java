package app.mysong.player;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class RadioStream {
    private static final String STATION_PREFIX = "station:";

    private RadioStream() {}

    static URI parseLink(String value) {
        try {
            URI link = new URI(value.trim());
            int port = link.getPort();
            if (!"https".equalsIgnoreCase(link.getScheme()) || link.getHost() == null
                    || link.getRawUserInfo() != null || link.getRawFragment() != null
                    || port == 0 || port > 65535) {
                return null;
            }
            return link;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    static boolean isHls(URI link) {
        return link.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    static String mediaId(long stationId) {
        return STATION_PREFIX + stationId;
    }

    static boolean isStation(String mediaId) {
        return mediaId != null && mediaId.startsWith(STATION_PREFIX);
    }

    static long stationId(String mediaId) {
        if (!isStation(mediaId)) return -1;
        try {
            long id = Long.parseLong(mediaId.substring(STATION_PREFIX.length()));
            return id > 0 && mediaId.equals(mediaId(id)) ? id : -1;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}
