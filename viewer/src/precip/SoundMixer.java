package precip;

import java.util.concurrent.ConcurrentLinkedQueue;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Mixes the game's sound: looping beds and one-shot voices into one stereo stream (PLAN Phase P, PB2).
 * <p>
 * The game thread only writes numbers: a target gain and a cutoff per bed ({@link #setBed}), requests for one-shots
 * ({@link #fire}, queued), the hold and the master, and {@link #touch} once per painted frame. {@link #render} does the
 * work; with a line ({@link #start}) the daemon thread {@code sound-mixer} calls it and nothing else does, without one a
 * test calls it and needs no audio device.
 * <ul>
 *   <li>A bed always advances, also while it is silent; it is only summed while it can be heard. Its gain follows the
 *       target with a time constant of {@link #GAIN_TAU}.</li>
 *   <li>The <b>gate</b> closes in {@link #GATE_FADE} seconds while the game holds the sound (pause, a dialog, no game) or
 *       has not called {@link #touch} for {@link #DEAD_MAN} seconds (a minimised window). Once it is closed the one-shots
 *       stand still, and go on from where they were when it opens again.</li>
 *   <li>{@link #flush} ends every one-shot (a game was left): faded out in {@link #RELEASE} seconds, or dropped at once
 *       behind a closed gate.</li>
 *   <li>A preview ({@link #preview}, the Test buttons of the settings) passes the gate; the master applies to everything.</li>
 *   <li>The sum goes through a soft limiter and never leaves -1..1.</li>
 * </ul>
 */
final class SoundMixer {
    static final int RATE = SoundBank.RATE;
    /** Frames mixed at a time (about 23 ms) and the size of the line's buffer. */
    static final int BLOCK = 1024, LINE_FRAMES = 4096;
    /** One-shots that can sound at once (3 thunders, 3 cars, a preview, and room for those still fading). */
    static final int VOICES = 12;
    static final double GAIN_TAU = 0.25, GATE_FADE = 0.3, RELEASE = 0.4, DEAD_MAN = 1.0, MASTER_TAU = 0.05;
    /** A cutoff from here up (and 0) means "no filter". */
    static final float OPEN_HZ = 15900;
    /** Every line this process has opened (the self-test proves it stays 0). */
    static volatile int linesOpened;

    private static final float TO_FLOAT = 1 / 32768f;
    private static final int NEVER = Integer.MAX_VALUE / 2;

    /** A request for a one-shot, on its way to the audio thread. */
    private static final class Shot {
        final SoundBank.Clip clip;
        final float gain, cutoff, delay, seconds;
        final boolean preview;

        Shot(SoundBank.Clip clip, float gain, float cutoff, float delay, float seconds, boolean preview) {
            this.clip = clip; this.gain = gain; this.cutoff = cutoff; this.delay = delay; this.seconds = seconds; this.preview = preview;
        }
    }

    /** A one-shot that is sounding (or waiting for its delay). Only the audio thread touches it. */
    private static final class Voice {
        SoundBank.Clip clip;        // null = free
        int pos, end, wait, fadeAt;
        float gain, a, zl, zr, amp, ampStep;
        boolean preview;
    }

    // ---- beds: the clips are fixed, the targets are written by the game thread and published by touch()
    private final SoundBank.Clip[] bedClip;
    private final float[] bedTarget, bedCutoff;
    private final int[] bedPos;
    private final float[] bedGain, bedA, bedZl, bedZr;

    private final ConcurrentLinkedQueue<Shot> queue = new ConcurrentLinkedQueue<>();
    private final Voice[] voices = new Voice[VOICES];
    private volatile int touches, flushes;
    private volatile boolean hold = true, closing;
    private volatile float master = 1;

    // ---- the audio thread's own state
    private int seenTouch, seenFlush, sinceTouch = NEVER;
    private float gate, masterNow = 1;
    private final short[] scratch = new short[BLOCK * 2];
    private final float[] aux = new float[BLOCK * 2];

    private SourceDataLine line;
    private Thread thread;

    /** {@code beds}: the looping clips, addressed by their index from here on. {@code seed} places each one somewhere in its loop. */
    SoundMixer(SoundBank.Clip[] beds, long seed) {
        bedClip = beds.clone();
        int n = beds.length;
        bedTarget = new float[n]; bedCutoff = new float[n]; bedPos = new int[n];
        bedGain = new float[n]; bedA = new float[n]; bedZl = new float[n]; bedZr = new float[n];
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(seed);
        for (int i = 0; i < n; i++) {
            bedPos[i] = rnd.nextInt(Math.max(1, beds[i].frames));     // two beds of different length never meet at the same phase twice
            bedA[i] = 1;
        }
        for (int i = 0; i < voices.length; i++) voices[i] = new Voice();
    }

    // ------------------------------------------------------------------ the game thread

    /** Where bed {@code i} should be: gain 0..1 and a low-pass cutoff in Hz (0 = none). Heard after the next {@link #touch}. */
    void setBed(int i, float target, float cutoffHz) {
        bedTarget[i] = target < 0 ? 0 : Math.min(1, target);
        bedCutoff[i] = cutoffHz;
    }

    /** The game painted a frame (publishes the bed targets and feeds the dead-man). */
    void touch() { touches++; }

    /**
     * Play {@code clip} once. {@code delaySeconds} from now; a negative delay starts that far into the file (a car that is
     * already closer than the file's lead-in). {@code cutoffHz} 0 = no low-pass.
     */
    void fire(SoundBank.Clip clip, float gain, float cutoffHz, float delaySeconds) {
        if (clip == null || clip.standIn || gain <= 0 || closing) return;
        queue.add(new Shot(clip, gain, cutoffHz, delaySeconds, 0, false));
    }

    /** The Test buttons: play {@code clip} (its first {@code seconds}; 0 = all of it) whatever the gate says. A second preview replaces the first. */
    void preview(SoundBank.Clip clip, float gain, float seconds) {
        if (clip == null || clip.standIn || closing) return;
        queue.add(new Shot(clip, gain, 0, 0, seconds, true));
    }

    /** True = the game is paused, behind a dialog, or there is none: close the gate and keep the one-shots where they are. */
    void setHold(boolean h) { hold = h; }

    boolean hold() { return hold; }

    void setMaster(float g) { master = g < 0 ? 0 : Math.min(1, g); }

    /** End every one-shot and forget those still queued (the game was left; previews go too). */
    void flush() {
        queue.clear();
        flushes++;
    }

    // ------------------------------------------------------------------ the line

    /** Open the output line and start the thread. Throws what the caller turns into "no sound" ({@code Sound.open}). */
    void start() throws LineUnavailableException {
        AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
        SourceDataLine l = AudioSystem.getSourceDataLine(fmt);
        l.open(fmt, LINE_FRAMES * 4);
        l.start();
        linesOpened++;
        line = l;
        thread = new Thread(this::run, "sound-mixer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
    }

    boolean running() { return thread != null && thread.isAlive(); }

    /** CPU time the mixer thread has used so far, ns (-1 = not running or not measurable). */
    long cpuNanos() {
        Thread t = thread;
        if (t == null || !t.isAlive()) return -1;
        try {
            return java.lang.management.ManagementFactory.getThreadMXBean().getThreadCpuTime(t.threadId());
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private void run() {
        float[] f = new float[BLOCK * 2];
        byte[] b = new byte[BLOCK * 4];
        try {
            while (true) {
                render(f, BLOCK);
                for (int i = 0, j = 0; i < f.length; i++) {
                    int s = (int) (f[i] * 32767f);
                    b[j++] = (byte) s;
                    b[j++] = (byte) (s >> 8);
                }
                if (line.write(b, 0, b.length) < b.length && !closing) break;      // the device went away
                if (closing && masterNow < 0.002f) break;
            }
            if (closing) line.drain();                         // (what is left in the buffer: at most LINE_FRAMES; close() does not wait for longer)
        } catch (RuntimeException ex) {
            if (!closing) System.err.println("[viewer] sound: the mixer stopped (" + ex + ") - the game is silent");
        }
    }

    /** Fade out, let the line play what it holds (at most 200 ms are waited for) and close it. Safe to call twice, and without a line. */
    void close() {
        if (closing) return;
        closing = true;
        queue.clear();
        Thread t = thread;
        SourceDataLine l = line;
        if (t != null) {
            try { t.join(380); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (l != null) {
            try { l.stop(); l.flush(); l.close(); } catch (RuntimeException ignored) { }
        }
        line = null;
    }

    // ------------------------------------------------------------------ the audio thread (or a test)

    /** The next {@code frames} stereo frames, interleaved, each sample in -1..1. Not thread safe: one caller only. */
    void render(float[] out, int frames) {
        for (int done = 0; done < frames; ) {
            int n = Math.min(BLOCK, frames - done);
            block(out, done * 2, n);
            done += n;
        }
    }

    private static float coefficient(float cutoffHz) {
        return cutoffHz <= 0 || cutoffHz >= OPEN_HZ ? 1 : (float) (1 - Math.exp(-2 * Math.PI * cutoffHz / RATE));
    }

    private void block(float[] out, int o, int n) {
        // ---- what the game said since the last block
        int t = touches;
        if (t != seenTouch) { seenTouch = t; sinceTouch = 0; }
        else sinceTouch = Math.min(NEVER, sinceTouch + n);
        boolean shut = gate <= 0;
        int fl = flushes;
        if (fl != seenFlush) {
            seenFlush = fl;
            for (Voice v : voices) if (v.clip != null) release(v, shut && !v.preview ? 0 : RELEASE);
        }
        for (Shot s; (s = queue.poll()) != null; ) begin(s);

        boolean open = !hold && sinceTouch < RATE * DEAD_MAN;
        float g0 = gate, g1 = open ? Math.min(1, g0 + (float) (n / (GATE_FADE * RATE))) : Math.max(0, g0 - (float) (n / (GATE_FADE * RATE)));
        gate = g1;
        boolean audible = g0 > 0 || g1 > 0;
        float mk = (float) (1 - Math.exp(-n / ((closing ? 0.02 : MASTER_TAU) * RATE)));     // (quitting: silent within an eighth of a second)
        float m0 = masterNow, m1 = m0 + ((closing ? 0 : master) - m0) * mk;
        masterNow = m1;

        for (int i = o, e = o + n * 2; i < e; i++) out[i] = 0;
        boolean previews = false;

        // ---- beds
        float k = (float) (1 - Math.exp(-n / (GAIN_TAU * RATE)));
        for (int b = 0; b < bedClip.length; b++) {
            SoundBank.Clip c = bedClip[b];
            float target = c.standIn ? 0 : bedTarget[b], cur = bedGain[b], next = cur + (target - cur) * k;
            if (Math.abs(next - target) < 1e-4f) next = target;
            bedGain[b] = next;
            float a0 = bedA[b], a1 = a0 + (coefficient(bedCutoff[b]) - a0) * k;
            if (a1 > 0.9995f) a1 = 1;
            bedA[b] = a1;
            int pos = bedPos[b];
            if (!audible || (cur <= 0 && next <= 0)) {         // not heard: it only moves on
                bedPos[b] = (int) (((long) pos + n) % c.frames);
                bedZl[b] = 0; bedZr[b] = 0;
                continue;
            }
            float cg = (float) c.gain * TO_FLOAT, gs = (next - cur) / n, zl = bedZl[b], zr = bedZr[b];
            for (int i = 0; i < n; ) {
                int m = Math.min(n - i, c.frames - pos);
                c.read(pos, scratch, 0, m);
                for (int j = 0; j < m; j++, i++) {
                    float l = scratch[j * 2] * cg, r = scratch[j * 2 + 1] * cg;
                    if (a1 < 1) { zl += a1 * (l - zl); zr += a1 * (r - zr); l = zl; r = zr; } else { zl = l; zr = r; }
                    float g = cur + gs * i;
                    out[o + i * 2] += l * g;
                    out[o + i * 2 + 1] += r * g;
                }
                pos += m;
                if (pos >= c.frames) pos = 0;
            }
            bedPos[b] = pos; bedZl[b] = zl; bedZr[b] = zr;
        }

        // ---- one-shots
        for (Voice v : voices) {
            SoundBank.Clip c = v.clip;
            if (c == null) continue;
            if (!v.preview && !audible) continue;              // behind the closed gate it stands still
            float[] bus = out;
            int bo = o;
            if (v.preview) {
                if (!previews) { previews = true; for (int i = 0; i < n * 2; i++) aux[i] = 0; }
                bus = aux; bo = 0;
            }
            int i = 0;
            if (v.wait > 0) { i = Math.min(v.wait, n); v.wait -= i; }
            float cg = v.gain * (float) c.gain * TO_FLOAT, zl = v.zl, zr = v.zr, a = v.a, amp = v.amp, st = v.ampStep;
            int pos = v.pos;
            boolean over = false;
            while (i < n && !over) {
                int m = Math.min(n - i, v.end - pos);
                if (m <= 0) { over = true; break; }
                c.read(pos, scratch, 0, m);
                for (int j = 0; j < m; j++, i++) {
                    if (pos + j == v.fadeAt) st = -(float) (1 / (0.3 * RATE));          // a preview of part of a file ends softly
                    amp += st;
                    if (amp >= 1) { amp = 1; if (st > 0) st = 0; }
                    else if (amp <= 0 && st < 0) { amp = 0; over = true; break; }
                    float l = scratch[j * 2] * cg, r = scratch[j * 2 + 1] * cg;
                    if (a < 1) { zl += a * (l - zl); zr += a * (r - zr); l = zl; r = zr; }
                    bus[bo + i * 2] += l * amp;
                    bus[bo + i * 2 + 1] += r * amp;
                }
                pos += m;
            }
            if (over || pos >= v.end) v.clip = null;
            else { v.pos = pos; v.zl = zl; v.zr = zr; v.amp = amp; v.ampStep = st; }
        }

        // ---- gate, previews, master, limiter
        float gs = (g1 - g0) / n, ms = (m1 - m0) / n;
        for (int i = 0; i < n; i++) {
            float g = g0 + gs * i, m = m0 + ms * i;
            float l = out[o + i * 2] * g, r = out[o + i * 2 + 1] * g;
            if (previews) { l += aux[i * 2]; r += aux[i * 2 + 1]; }
            out[o + i * 2] = limit(l * m);
            out[o + i * 2 + 1] = limit(r * m);
        }
    }

    /** Straight up to 0.8, then bent so that nothing reaches beyond 1. */
    static float limit(float x) {
        float a = x < 0 ? -x : x;
        if (a <= 0.8f) return x;
        float y = Math.min(1f, 0.8f + 0.2f * (float) Math.tanh((a - 0.8f) / 0.2f));
        return x < 0 ? -y : y;
    }

    private void begin(Shot s) {
        if (s.preview) for (Voice v : voices) if (v.clip != null && v.preview) release(v, 0.05);
        Voice v = null;
        for (Voice c : voices) if (c.clip == null) { v = c; break; }
        if (v == null) return;                                 // every voice is busy: this one is not heard
        v.clip = s.clip;
        v.preview = s.preview;
        v.gain = s.gain;
        v.a = coefficient(s.cutoff);
        v.zl = 0; v.zr = 0;
        v.end = s.clip.frames;
        v.fadeAt = -1;
        v.amp = 1; v.ampStep = 0;
        v.wait = 0; v.pos = 0;
        if (s.delay >= 0) v.wait = Math.round(s.delay * RATE);
        else {
            v.pos = Math.min(Math.max(0, s.clip.frames - 1), Math.round(-s.delay * RATE));
            v.amp = 0; v.ampStep = (float) (1 / (0.03 * RATE));              // into the middle of a file: no click
        }
        if (s.seconds > 0 && s.seconds * RATE < s.clip.frames) {
            v.end = Math.round(s.seconds * RATE);
            v.fadeAt = Math.max(v.end / 2, v.end - (int) (0.3 * RATE));
            v.amp = 0; v.ampStep = (float) (1 / (0.05 * RATE));
        }
    }

    private static void release(Voice v, double seconds) {
        if (seconds <= 0 || v.wait > 0) { v.clip = null; return; }
        v.ampStep = -(float) (1 / (seconds * RATE));
        v.fadeAt = -1;
    }

    // ------------------------------------------------------------------ what a test may look at (same thread as render)

    int activeVoices() {
        int n = 0;
        for (Voice v : voices) if (v.clip != null) n++;
        return n;
    }

    /** The frame the first sounding one-shot is at (-1 = none, or it still waits for its delay). */
    int voicePosition() {
        for (Voice v : voices) if (v.clip != null) return v.wait > 0 ? -1 : v.pos;
        return -1;
    }

    int bedPosition(int i) { return bedPos[i]; }

    /** Put bed {@code i} at a frame of its loop (a test of the seam). */
    void seekBed(int i, int frame) { bedPos[i] = Math.floorMod(frame, bedClip[i].frames); }

    float bedGain(int i) { return bedGain[i]; }

    float gate() { return gate; }

    int beds() { return bedClip.length; }
}
