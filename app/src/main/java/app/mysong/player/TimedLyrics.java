package app.mysong.player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TimedLyrics {
    record Cue(long timeMs, String text) {}

    private static final int MAX_BYTES = 512 * 1024;
    private static final String TIME = "(\\d{1,6}):([0-5]\\d)(?:[.:](\\d{1,3}))?";
    private static final Pattern LINE_TIME = Pattern.compile("\\[" + TIME + "\\]");
    private static final Pattern WORD_TIME = Pattern.compile("<" + TIME + ">");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d{1,9})\\s*\\]",
            Pattern.CASE_INSENSITIVE);

    private TimedLyrics() {}

    static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("The lyrics file could not be opened");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (output.size() + count > MAX_BYTES) throw new IOException("The lyrics file is too large");
            output.write(chunk, 0, count);
        }
        byte[] bytes = output.toByteArray();
        String raw;
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
            raw = StandardCharsets.UTF_16.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } else {
            raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
        raw = stripBom(raw);
        if (parse(raw).stream().noneMatch(cue -> !cue.text().isEmpty())) {
            throw new IOException("The lyrics file has no timed lines");
        }
        return raw;
    }

    static List<Cue> parse(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        String[] lines = stripBom(raw).split("\\r\\n|\\n|\\r");
        long offset = 0;
        for (String line : lines) {
            Matcher setting = OFFSET.matcher(line.trim());
            if (setting.matches()) offset = Long.parseLong(setting.group(1));
        }

        List<Cue> cues = new ArrayList<>();
        for (String line : lines) {
            String text = line.trim();
            Matcher times = LINE_TIME.matcher(text);
            List<Long> starts = new ArrayList<>();
            int end = 0;
            while (times.find() && times.start() == end) {
                starts.add(Math.max(0, timestamp(times) - offset));
                end = times.end();
            }
            if (starts.isEmpty()) continue;
            String words = WORD_TIME.matcher(text.substring(end)).replaceAll("").trim();
            for (long start : starts) cues.add(new Cue(start, words));
        }
        cues.sort(Comparator.comparingLong(Cue::timeMs));
        return cues;
    }

    static int indexAt(List<Cue> cues, long playbackMs) {
        int lo = 0;
        int hi = cues.size();
        while (lo < hi) {
            int middle = lo + (hi - lo) / 2;
            if (cues.get(middle).timeMs() <= playbackMs) lo = middle + 1;
            else hi = middle;
        }
        return lo - 1;
    }

    private static long timestamp(Matcher mark) {
        long minutes = Long.parseLong(mark.group(1));
        long seconds = Long.parseLong(mark.group(2));
        String fraction = mark.group(3);
        int milliseconds = fraction == null ? 0 : Integer.parseInt((fraction + "00").substring(0, 3));
        return (minutes * 60 + seconds) * 1000 + milliseconds;
    }

    private static String stripBom(String value) {
        return value.startsWith("\ufeff") ? value.substring(1) : value;
    }
}
