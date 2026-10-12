package precip;

import java.awt.GraphicsEnvironment;
import java.io.File;

/**
 * The game's sound as the app holds it (PLAN Phase P, PB3): told about the session, every painted frame, the settings
 * and the exit. This class itself does nothing at all: {@link #NONE} is what every {@link AppShell} has until
 * {@link #open} gives it a working one, so tests, snapshots and a computer without an audio device are silent by
 * construction and never open a line.
 * <p>
 * Every method is called on the Swing event thread.
 */
class Sound {
    /** No sound: every call is ignored. */
    static final Sound NONE = new Sound();
    /** Why the last {@link #open} gave {@link #NONE}; null = it has not been tried, or it worked. */
    static volatile String problem;

    Sound() {}

    /** A game began ({@code gs}) or was left (null): nothing of the old one is heard in the new one. */
    void session(GameSession gs) {}

    /** The frame hook of a game's {@link ViewerPanel}: once per painted frame, {@code dt} seconds after the previous one. */
    void frame(ViewerPanel p, double dt) {}

    /** True while something other than the game itself is in front (a dialog, the pause menu, a dead simulation). */
    void blocked(boolean b) {}

    /** The settings changed: volumes and the mute. */
    void apply(Settings s) {}

    /** The Test buttons of the Sound page: {@code "thunder"} or {@code "rain"}, also while the game is paused. */
    void preview(String what) {}

    /** False = there is no sound in this run (see {@link #problem}). */
    boolean available() { return false; }

    /** Fade out and release the audio line. */
    void close() {}

    /**
     * The sound of this run. {@link #NONE} when there is no display, when {@code -Dprecip.nosound} / {@code --no-sound} or
     * the environment variable {@code PRECIP_NOSOUND} says so, when {@code assets/sounds/manifest.json} is not there, or
     * when the computer gives no audio line; each with one line on stderr, and the game goes on without sound.
     * Called once, from {@code Main.launchApp}.
     */
    static Sound open(File root, Settings s) {
        problem = null;
        if (GraphicsEnvironment.isHeadless() || Boolean.getBoolean("java.awt.headless")) return none("Sound is off: there is no display.", false);
        if (switchedOff()) return none("Sound was switched off for this run (--no-sound).", false);
        File dir = new File(root, "assets" + File.separator + "sounds");
        if (!new File(dir, "manifest.json").isFile()) return none("The sound files were not found (assets/sounds) - the game is silent.", true);
        try {
            SoundBank bank = SoundBank.load(dir);
            if (!bank.manifestFound) return none("The sound files could not be read (assets/sounds) - the game is silent.", false);
            SoundDirector.Bed[] beds = SoundDirector.Bed.values();
            SoundBank.Clip[] clips = new SoundBank.Clip[beds.length];
            for (int i = 0; i < beds.length; i++) clips[i] = bank.clip(beds[i].id);
            SoundMixer mixer = new SoundMixer(clips, System.nanoTime());
            mixer.start();
            Live l = new Live(bank, mixer, SoundDirector.of(bank));
            l.apply(s);
            return l;
        } catch (javax.sound.sampled.LineUnavailableException | IllegalArgumentException | SecurityException ex) {
            return none("No audio device was found - the game is silent.", true);
        } catch (RuntimeException | LinkageError ex) {
            return none("Sound could not be started (" + ex + ") - the game is silent.", true);
        }
    }

    /** {@code -Dprecip.nosound} (any value but "false"; {@code --no-sound} sets it) or the environment variable {@code PRECIP_NOSOUND} (any value but empty and 0). */
    static boolean switchedOff() {
        String p = System.getProperty("precip.nosound");
        if (p != null && !p.equalsIgnoreCase("false")) return true;
        String e = System.getenv("PRECIP_NOSOUND");
        return e != null && !e.isEmpty() && !e.equals("0");
    }

    private static Sound none(String why, boolean say) {
        problem = why;
        if (say) System.err.println("[viewer] sound: " + why);
        return NONE;
    }

    /** A working sound on a mixer the caller made (the self-test brings one without a line). */
    static Sound live(SoundBank bank, SoundMixer mixer, SoundDirector director) { return new Live(bank, mixer, director); }

    /** The real thing: a bank, a mixer and a director. */
    private static final class Live extends Sound {
        private final SoundBank bank;
        private final SoundMixer mixer;
        private final SoundDirector director;
        private final SoundDirector.Input in = new SoundDirector.Input();
        private final SoundBank.Clip[] thunders, cars;
        private final java.util.Random pick = new java.util.Random();
        private GameSession session;
        private boolean blocked, failed;
        private double volRain = 0.8, volThunder = 0.9;

        Live(SoundBank bank, SoundMixer mixer, SoundDirector director) {
            this.bank = bank;
            this.mixer = mixer;
            this.director = director;
            thunders = bank.thunders();
            cars = bank.cars();
        }

        @Override boolean available() { return true; }

        @Override void session(GameSession gs) {
            if (gs == session) return;
            session = gs;
            failed = false;
            director.reset();
            mixer.flush();                                     // no voice of the old game in the new one
            mixer.setHold(true);                               // (the first frame that may be heard opens it again)
            for (int i = 0; i < mixer.beds(); i++) mixer.setBed(i, 0, 0);
            mixer.touch();
        }

        @Override void blocked(boolean b) { blocked = b; }

        @Override void apply(Settings s) {
            in.volRain = volRain = s.soundRain;
            in.volThunder = volThunder = s.soundThunder;
            in.volTraffic = s.soundTraffic;
            in.volTowns = s.soundTowns;
            in.volStorm = s.soundStorm;
            mixer.setMaster(s.soundOn ? (float) s.soundMaster : 0);
        }

        @Override void frame(ViewerPanel p, double dt) {
            GameSession gs = session;
            if (gs == null || failed) return;
            try {
                in.mode = gs.mode;
                synchronized (gs.state) {
                    in.hello = gs.state.hello;
                    in.frame = gs.state.cur;
                    in.hurricane = gs.state.hurricane == null ? null : gs.state.hurricane.cur;
                }
                Camera.View v = p.renderer().view;
                if (v != null) { in.lx = (v.wx0 + v.wx1) / 2; in.span = v.wx1 - v.wx0; }
                else { in.lx = in.hello != null ? in.hello.w / 2.0 : 0; in.span = in.hello != null ? in.hello.w : 1; }
                in.dt = dt;
                in.paused = false;                             // (the frame says so itself)
                in.blocked = blocked;
                SoundDirector.Mix m = director.step(in);
                for (int i = 0; i < m.bed.length; i++) mixer.setBed(i, m.bed[i], m.cutoff[i]);
                for (int k = 0; k < m.shots; k++) {
                    SoundDirector.Shot s = m.shot[k];
                    mixer.fire((s.id.charAt(0) == 't' ? thunders : cars)[s.variant], s.gain, s.cutoff, s.delay);
                }
                mixer.setHold(!m.open);
                mixer.touch();
            } catch (RuntimeException ex) {
                // a frame the director cannot read must never take the picture with it: say so once and fall silent
                failed = true;
                mixer.setHold(true);
                System.err.println("[viewer] sound: stopped after an error (" + ex + ") - the game is silent");
            }
        }

        @Override void preview(String what) {
            if ("thunder".equals(what)) mixer.preview(thunders[pick.nextInt(thunders.length)], (float) volThunder, 0);
            else if ("rain".equals(what)) mixer.preview(bank.clip("rain_heavy"), (float) volRain, 4);
        }

        @Override void close() { mixer.close(); }
    }
}
