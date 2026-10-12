package precip;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * The sounds of {@code assets/sounds/}: {@code manifest.json} and the WAV files it names (PLAN Phase P, PB1).
 * <p>
 * Everything is brought to the mixer's format (44100 Hz, 16-bit, stereo). Short sounds are kept on the heap as
 * interleaved {@code short[]}; a long bed that already has that format is memory-mapped from its data chunk, so the
 * heap does not grow with it. A file that is missing, unreadable, empty or of a format Java cannot decode becomes one
 * second of silence and one line on stderr: loading never throws, and {@link #clip} never returns null.
 * <p>
 * Nothing here opens an audio line; {@code javax.sound.sampled} is only used to decode a WAV that is not already in
 * the mixer's format.
 */
final class SoundBank {
    static final int RATE = 44100;
    /** The sound groups of the settings (index = the group of a {@link Clip}). */
    static final int RAIN = 0, THUNDER = 1, TRAFFIC = 2, TOWNS = 3, STORM = 4;
    static final String[] GROUPS = {"rain", "thunder", "traffic", "towns", "storm"};
    static final int THUNDERS = 7, CARS = 5;
    /** The 19 sounds the game knows, in the order of the plan's table. */
    static final String[] IDS = {"rain_heavy", "rain_light", "thunder_1", "thunder_2", "thunder_3", "thunder_4", "thunder_5", "thunder_6", "thunder_7",
            "car_1", "car_2", "car_3", "car_4", "car_5", "city_a", "city_b", "siren", "tornado", "hurricane"};
    /** A file in the mixer's own format is mapped instead of read from this size of its data chunk on (about 34 s). */
    static final long MAP_BYTES = 6L << 20;
    /** A file that has to be decoded onto the heap is refused above this (the beds are far larger and must arrive in the mixer's format). */
    static final long DECODE_LIMIT = 48L << 20;

    /** One sound, ready to be mixed. Immutable once loaded. */
    static final class Clip {
        final String id;
        final int group;
        final boolean loop;
        /** Level correction of the manifest (linear; the files are not levelled themselves). */
        final double gain;
        /** Cars: seconds from the start of the file to its loudest moment (NaN = not measured). */
        final double peak;
        /** The length the manifest states, seconds (NaN = it states none). */
        final double manifestSeconds;
        /** Stereo frames. */
        final int frames;
        /** True for the one second of silence that stands for a file that could not be loaded. */
        final boolean standIn;
        private final short[] pcm;
        private final ShortBuffer mapped;

        private Clip(String id, int group, boolean loop, double gain, double peak, double manifestSeconds, short[] pcm, ShortBuffer mapped, boolean standIn) {
            this.id = id; this.group = group; this.loop = loop; this.gain = gain; this.peak = peak; this.manifestSeconds = manifestSeconds;
            this.pcm = pcm; this.mapped = mapped; this.standIn = standIn;
            frames = pcm != null ? pcm.length / 2 : mapped.limit() / 2;
        }

        boolean mapped() { return mapped != null; }

        double seconds() { return frames / (double) RATE; }

        /** Bytes this clip holds on the heap (0 for a mapped one). */
        long heapBytes() { return pcm != null ? pcm.length * 2L : 0; }

        /** Copy {@code n} stereo frames from frame {@code from} on into {@code dst} at frame {@code at}. The caller keeps inside the clip. */
        void read(int from, short[] dst, int at, int n) {
            if (pcm != null) System.arraycopy(pcm, from * 2, dst, at * 2, n * 2);
            else mapped.get(from * 2, dst, at * 2, n * 2);
        }

        /** One sample (tests). */
        short sample(int frame, int channel) { return pcm != null ? pcm[frame * 2 + channel] : mapped.get(frame * 2 + channel); }
    }

    private final Map<String, Clip> clips = new LinkedHashMap<>();
    private final Clip silent = standIn("(none)", RAIN, false, 1, Double.NaN, Double.NaN);
    /** False when the folder has no readable {@code manifest.json}: every clip is then a stand-in. */
    boolean manifestFound;
    /** What went wrong while loading, one line each (also printed to stderr). */
    final List<String> problems = new ArrayList<>();
    double loadMs;

    private SoundBank() {}

    /** A bank of 19 silent stand-ins (tests, and what the director is built from when there is nothing to play). */
    static SoundBank silent() {
        SoundBank b = new SoundBank();
        for (String id : IDS) b.clips.put(id, standIn(id, defaultGroup(id), defaultLoop(id), 1, Double.NaN, Double.NaN));
        return b;
    }

    /** A clip made of samples the caller brings (interleaved stereo): the self-test's sines and noise. */
    static Clip of(String id, boolean loop, double gain, short[] stereo) {
        return new Clip(id, defaultGroup(id), loop, gain, Double.NaN, Double.NaN, stereo, null, false);
    }

    static SoundBank load(File dir) { return load(dir, MAP_BYTES); }

    /** Read the manifest of {@code dir} and every file it names. Never throws; see {@link #problems}. */
    static SoundBank load(File dir, long mapBytes) {
        long t0 = System.nanoTime();
        SoundBank b = new SoundBank();
        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        File mf = new File(dir, "manifest.json");
        try {
            if (!mf.isFile()) throw new IOException("not found");
            entries = entries(Json.parse(Files.readString(mf.toPath(), StandardCharsets.UTF_8)));
            b.manifestFound = true;
        } catch (IOException | RuntimeException ex) {
            b.problem("sound: " + mf + " could not be read (" + ex.getMessage() + ") - no sound files are loaded");
        }
        // the 19 the game uses first (one that the manifest does not name is looked for under its own name), then whatever else is listed
        List<String> ids = new ArrayList<>(List.of(IDS));
        for (String id : entries.keySet()) if (!ids.contains(id)) ids.add(id);
        for (String id : ids) {
            Map<String, Object> e = entries.get(id);
            int group = group(Json.str(e, "group", ""), id);
            boolean loop = Json.bool(e, "loop", defaultLoop(id));
            double gain = Json.num(e, "gain", Double.NaN);
            if (Double.isNaN(gain)) gain = e != null && e.get("gain_db") instanceof Number n ? Math.pow(10, n.doubleValue() / 20) : 1;
            gain = Model.clamp(gain, 0, 8);
            double peak = Json.num(e, "peak", Double.NaN), seconds = Json.num(e, "seconds", Double.NaN);
            Clip c = null;
            if (b.manifestFound) {
                if (e == null) b.problem("sound: manifest.json does not name \"" + id + "\" - trying " + id + ".wav");
                File f = new File(dir, new File(Json.str(e, "file", id + ".wav")).getName());      // a bare file name, never a path
                try {
                    c = read(f, id, group, loop, gain, peak, seconds, mapBytes);
                } catch (IOException | RuntimeException | OutOfMemoryError ex) {
                    b.problem("sound: " + f.getName() + " could not be loaded (" + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()) + ") - it stays silent");
                }
            }
            b.clips.put(id, c != null ? c : standIn(id, group, loop, gain, peak, seconds));
        }
        b.loadMs = (System.nanoTime() - t0) / 1e6;
        return b;
    }

    private void problem(String s) {
        problems.add(s);
        System.err.println("[viewer] " + s);
    }

    /**
     * The entries of a manifest by id. The plan fixes the fields of an entry, not what holds them, so every plain form is
     * read: {@code {id: {..}}}, {@code {"sounds": {id: {..}}}}, and a list of entries with an {@code "id"} (at the top or under
     * {@code "sounds"} / {@code "files"}).
     */
    private static Map<String, Map<String, Object>> entries(Object root) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, Object> m = Json.obj(root);
        Object holder = root;
        if (m != null) for (String k : new String[]{"sounds", "files", "clips"}) if (m.get(k) instanceof Map || m.get(k) instanceof List) { holder = m.get(k); break; }
        List<Object> list = Json.arr(holder);
        if (list != null) {
            for (Object o : list) {
                Map<String, Object> e = Json.obj(o);
                if (e == null) continue;
                String id = Json.str(e, "id", null);
                if (id == null) { String f = Json.str(e, "file", ""); id = f.endsWith(".wav") ? f.substring(0, f.length() - 4) : f; }
                if (!id.isEmpty()) out.put(id, e);
            }
            return out;
        }
        Map<String, Object> byId = Json.obj(holder);
        if (byId == null) throw new IllegalArgumentException("not a JSON object");
        for (Map.Entry<String, Object> en : byId.entrySet()) {
            Map<String, Object> e = Json.obj(en.getValue());
            if (e != null) out.put(en.getKey(), e);
        }
        return out;
    }

    private static int group(String name, String id) {
        for (int k = 0; k < GROUPS.length; k++) if (GROUPS[k].equals(name)) return k;
        return defaultGroup(id);
    }

    static int defaultGroup(String id) {
        return id.startsWith("rain") ? RAIN : id.startsWith("thunder") ? THUNDER : id.startsWith("car") ? TRAFFIC : id.startsWith("city") ? TOWNS : STORM;
    }

    static boolean defaultLoop(String id) { return !id.startsWith("thunder") && !id.startsWith("car"); }

    private static Clip standIn(String id, int group, boolean loop, double gain, double peak, double seconds) {
        return new Clip(id, group, loop, gain, peak, seconds, new short[RATE * 2], null, true);
    }

    /** The clip of an id; an unknown id is a silent stand-in, never null. */
    Clip clip(String id) {
        Clip c = clips.get(id);
        return c != null ? c : silent;
    }

    Clip[] thunders() {
        Clip[] a = new Clip[THUNDERS];
        for (int k = 0; k < a.length; k++) a[k] = clip("thunder_" + (k + 1));
        return a;
    }

    Clip[] cars() {
        Clip[] a = new Clip[CARS];
        for (int k = 0; k < a.length; k++) a[k] = clip("car_" + (k + 1));
        return a;
    }

    List<Clip> all() { return new ArrayList<>(clips.values()); }

    /** How many of the 19 are real files. */
    int loaded() {
        int n = 0;
        for (String id : IDS) if (!clip(id).standIn) n++;
        return n;
    }

    long heapBytes() {
        long n = 0;
        for (Clip c : clips.values()) if (!c.standIn) n += c.heapBytes();
        return n;
    }

    long mappedBytes() {
        long n = 0;
        for (Clip c : clips.values()) if (c.mapped()) n += c.frames * 4L;
        return n;
    }

    // ------------------------------------------------------------------ WAV

    /** What the header of a RIFF/WAVE file says. */
    static final class Wav {
        int format, channels, rate, bits;
        long dataOff, dataLen;

        /** PCM in exactly the mixer's format. */
        boolean exact() { return format == 1 && channels == 2 && rate == RATE && bits == 16; }
    }

    /** Walk the chunks of a WAV up to its data. */
    static Wav header(RandomAccessFile f) throws IOException {
        byte[] h = new byte[12];
        f.seek(0);
        f.readFully(h);
        if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F' || h[8] != 'W' || h[9] != 'A' || h[10] != 'V' || h[11] != 'E') throw new IOException("not a WAV file");
        Wav w = new Wav();
        long pos = 12, len = f.length();
        byte[] ch = new byte[8];
        while (pos + 8 <= len) {
            f.seek(pos);
            f.readFully(ch);
            long size = (ch[4] & 0xffL) | (ch[5] & 0xffL) << 8 | (ch[6] & 0xffL) << 16 | (ch[7] & 0xffL) << 24;
            String id = new String(ch, 0, 4, StandardCharsets.ISO_8859_1);
            if (id.equals("fmt ")) {
                byte[] b = new byte[(int) Math.min(40, Math.min(size, len - pos - 8))];
                f.readFully(b);
                if (b.length < 16) throw new IOException("short fmt chunk");
                ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
                w.format = bb.getShort(0) & 0xffff;
                w.channels = bb.getShort(2) & 0xffff;
                w.rate = bb.getInt(4);
                w.bits = bb.getShort(14) & 0xffff;
                if (w.format == 0xFFFE && b.length >= 26) w.format = bb.getShort(24) & 0xffff;     // WAVE_FORMAT_EXTENSIBLE: the real tag is in the sub-format
            } else if (id.equals("data")) {
                w.dataOff = pos + 8;
                w.dataLen = Math.max(0, Math.min(size, len - w.dataOff));                         // a cut-off file plays as far as it goes
                if (w.channels == 0) throw new IOException("data before fmt");
                return w;
            }
            pos += 8 + size + (size & 1);
        }
        throw new IOException("no data chunk");
    }

    private static Clip read(File f, String id, int group, boolean loop, double gain, double peak, double seconds, long mapBytes) throws IOException {
        if (!f.isFile()) throw new IOException("not found");
        short[] pcm;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            Wav w = header(raf);
            if (w.exact()) {
                long bytes = w.dataLen - w.dataLen % 4;
                if (bytes < 4) throw new IOException("no samples");
                if (bytes >= mapBytes && bytes <= Integer.MAX_VALUE) {
                    ShortBuffer sb = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, w.dataOff, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    return new Clip(id, group, loop, gain, peak, seconds, null, sb, false);
                }
                if (bytes > DECODE_LIMIT) throw new IOException("too large");
                byte[] raw = new byte[(int) bytes];
                raf.seek(w.dataOff);
                raf.readFully(raw);
                pcm = new short[raw.length / 2];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                return new Clip(id, group, loop, gain, peak, seconds, pcm, null, false);
            }
            if (w.dataLen > DECODE_LIMIT) throw new IOException("not 44100 Hz 16-bit stereo, and too large to convert");
        }
        pcm = decode(f);
        if (pcm.length < 2) throw new IOException("no samples");
        return new Clip(id, group, loop, gain, peak, seconds, pcm, null, false);
    }

    /** Any WAV Java can read, as 44100 Hz 16-bit stereo: mono is doubled, more channels keep the first two, another rate is resampled (linear). */
    private static short[] decode(File f) throws IOException {
        byte[] raw;
        int ch;
        double rate;
        try (AudioInputStream in0 = AudioSystem.getAudioInputStream(f)) {
            AudioFormat src = in0.getFormat();
            ch = src.getChannels();
            rate = src.getSampleRate();
            if (ch < 1 || rate < 1000) throw new IOException("unsupported format " + src);
            AudioFormat want = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, ch, ch * 2, src.getSampleRate(), false);
            AudioInputStream in = src.matches(want) ? in0 : AudioSystem.getAudioInputStream(want, in0);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                bo.write(buf, 0, n);
                if (bo.size() > DECODE_LIMIT) throw new IOException("too large");
            }
            raw = bo.toByteArray();
        } catch (javax.sound.sampled.UnsupportedAudioFileException | IllegalArgumentException ex) {
            throw new IOException("not a WAV Java can read");
        }
        int n = raw.length / (2 * ch);
        ShortBuffer sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int out = (int) Math.max(0, Math.round(n * (RATE / rate)));
        short[] pcm = new short[out * 2];
        double step = rate / RATE;
        for (int i = 0; i < out; i++) {
            double p = i * step;
            int a = (int) Math.min(n - 1, Math.floor(p)), b = Math.min(n - 1, a + 1);
            double fr = p - a;
            for (int c = 0; c < 2; c++) {
                int sc = Math.min(c, ch - 1);
                pcm[i * 2 + c] = (short) Math.round(sb.get(a * ch + sc) * (1 - fr) + sb.get(b * ch + sc) * fr);
            }
        }
        return pcm;
    }
}
