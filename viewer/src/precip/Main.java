package precip;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Precipitation viewer entry point.
 * <p>
 * With no arguments the app opens its main menu ({@link AppShell}). {@code --new} starts a fresh
 * simulation directly, {@code --load <slot id or name>} continues a saved one. The diagnostic modes
 * below skip the menu: {@code --fake} / {@code --replay} open straight into a (never saved) world,
 * {@code --snapshot} / {@code --bench} / {@code --ui-snapshot} / {@code --selftest} run without a window.
 *
 * <pre>
 *   java -cp viewer/out precip.Main [--new | --load SLOT] [--config-dir DIR] [--saves-dir DIR]
 *        [--ui-snapshot SCREEN out.png [--size WxH] [--dpi S]]   screens: see UiSnapshot.SCREENS
 *        [--selftest]                                            interaction test + end-to-end check against the real simulation
 *        [--selftest-ui]                                         only the interaction test (no Python needed)
 *        [--set-background IMAGE | --clear-background] [--rebind ACTION=KEY ...] [--reset-keys] [--print-keys]
 *        [--no-sound]                                            this run is silent and opens no audio line (also: PRECIP_NOSOUND=1)
 *   java -cp viewer/out precip.Main [--fake | --replay file.jsonl] [--seed N] [--tod 0..1]
 *        [--world WxH] [--terrain flat|rolling|hilly|mountainous] [--settlements none|sparse|normal|dense] [--load FILE]
 *        [--quality low|medium|high] [--backdrop image] [--render-scale S (0 = native)]
 *        [--snapshot out.png --frames N [--size WxH] [--dpi S] [--cam SPEC] [--hide a,b,..] [--select auto|dead|ID] [--show-help]]
 *        [--bench N [--warm F] [--bench-scale S] [--bench-pan] [--profile]]
 *        [--bolt X[,ALPHA]] [--epilepsy-safe]                    lightning test hook (see {@link #optBolt}); the Epilepsy-safe setting for a diagnostic run
 *        [--adv name[=value],...]                                Settings &gt; Advanced for a diagnostic run (see {@link Advanced#parse}); never written to settings.json
 *        [--cloud-sheet out.png]
 * </pre>
 * {@code --cam}: {@code fit} | {@code x,y,zoom} | {@code cloud:<id|type>[,zoom]} | {@code obj:<kind>[,zoom]} |
 * {@code settlement:<kind|name>[,zoom]}. {@code --hide}: any of {@code panel,left,minimap,labels,arrows,wind,legend}.
 * <p>
 * This class only wires things together: the game view itself ({@link ViewerPanel}) can be created
 * and disposed any number of times and never exits the process.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        // The canvas is drawn in parallel horizontal bands (ParallelRenderer). Marlin's optional
        // path clipping rasterizes long strokes very slightly differently under a narrower clip,
        // which would show as faint seams; without it the bands are pixel-identical to one pass.
        // Must be set before Java2D initialises.
        if (System.getProperty("sun.java2d.renderer.clip") == null) System.setProperty("sun.java2d.renderer.clip", "false");
        boolean fake = false, showHelp = false, startNew = false, selftest = false, printKeys = false, resetKeys = false, clearBg = false, sizeGiven = false;
        String loadSlot = null, uiScreen = null, uiOut = null, configDir = null, savesDir = null, setBg = null;
        List<String> rebinds = new ArrayList<>();
        String terrainArg = null, settlementsArg = null;
        double autosaveSeconds = 0;
        boolean selftestUi = false;
        String snapshot = null, select = null, replay = null, camSpec = null, hide = "", sheet = null, backdrop = null;
        String shadeArg = null, mode = null, presetArg = null, stageArg = null, cloudTypeArg = null, hviewArg = null, untilStage = null;
        double shadeSeconds = -1;
        int chapterArg = -1;
        boolean exploreArg = false, labelsOff = false;
        // v2.3 world mode (snapshots): calendar month, UTC hour, data layer, pretend pointer / click as "lon,lat"
        double worldMonth = Double.NaN, worldHour = Double.NaN;
        String worldLayer = null, worldHover = null, worldClick = null;
        // v2.3 sandbox diagnostics: a scripted fake world, a condition to run to, commands to send first
        String fakeScene = null, untilArg = null;
        List<String> startCmds = new ArrayList<>();
        int frames = 300, bench = 0;
        Integer warm = null;
        int spawn = 0;
        double cold = 0, simSpeed = 0, dpi = 1;
        int feed = 2;
        double benchScale = 1;
        boolean profile = false, benchPan = false;
        Long seed = null;
        double tod = 0.38;
        int worldW = 3840, worldH = 1080, sizeW = 1280 + Renderer.PANEL_W, sizeH = 720;
        List<String> simExtra = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            try {
            switch (a) {
                case "--fake" -> fake = true;
                case "--replay" -> replay = args[++i];
                case "--snapshot" -> snapshot = args[++i];
                case "--frames" -> frames = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--tod" -> tod = Double.parseDouble(args[++i]);
                case "--select" -> select = args[++i];
                case "--show-help" -> showHelp = true;
                case "--bench" -> bench = Integer.parseInt(args[++i]);
                case "--warm" -> warm = Integer.parseInt(args[++i]);
                case "--bench-scale" -> benchScale = Double.parseDouble(args[++i]);
                case "--bench-volatile" -> System.err.println("[viewer] --bench-volatile is gone: the viewer always renders in software bands");
                case "--bench-pan" -> benchPan = true;
                case "--profile" -> profile = true;
                case "--fps-log" -> ViewerPanel.fpsLog = true;
                case "--threads" -> ParallelRenderer.configuredThreads = Math.max(1, Integer.parseInt(args[++i]));
                case "--spawn" -> spawn = Integer.parseInt(args[++i]);
                case "--cold" -> cold = Double.parseDouble(args[++i]);
                case "--sim-speed" -> simSpeed = Double.parseDouble(args[++i]);
                case "--bench-feed" -> feed = Math.max(1, Integer.parseInt(args[++i]));
                case "--cam" -> camSpec = args[++i];
                case "--hide" -> hide = args[++i];
                case "--dpi" -> dpi = Double.parseDouble(args[++i]);
                case "--size", "--snap-size" -> { int[] wh = parseSize(args[++i]); sizeW = wh[0]; sizeH = wh[1]; sizeGiven = true; }
                case "--world" -> { int[] wh = parseSize(args[++i]); worldW = wh[0]; worldH = wh[1]; }
                case "--labels-off" -> labelsOff = true;
                case "--terrain" -> { terrainArg = args[++i]; simExtra.add("--terrain"); simExtra.add(terrainArg); }
                case "--settlements" -> { settlementsArg = args[++i]; simExtra.add("--settlements"); simExtra.add(settlementsArg); }
                case "--load" -> loadSlot = args[++i];
                case "--new" -> startNew = true;
                case "--ui-snapshot" -> { uiScreen = args[++i]; uiOut = args[++i]; }
                case "--selftest" -> selftest = true;
                case "--selftest-ui" -> selftestUi = true;
                case "--config-dir" -> configDir = args[++i];
                case "--saves-dir" -> savesDir = args[++i];
                case "--set-background" -> setBg = args[++i];
                case "--clear-background" -> clearBg = true;
                case "--rebind" -> rebinds.add(args[++i]);
                case "--reset-keys" -> resetKeys = true;
                case "--print-keys" -> printKeys = true;
                case "--autosave-seconds" -> autosaveSeconds = Double.parseDouble(args[++i]);
                case "--quality" -> CloudPainter.quality = switch (args[++i]) { case "low", "0" -> 0; case "high", "2" -> 2; default -> 1; };
                case "--cloud-sheet" -> sheet = args[++i];
                case "--render-scale" -> ViewerPanel.maxRenderScale = Double.parseDouble(args[++i]);
                case "--backdrop" -> backdrop = args[++i];
                case "--shade" -> shadeArg = args[++i];
                case "--shade-seconds" -> shadeSeconds = Double.parseDouble(args[++i]);
                case "--mode" -> mode = args[++i];
                case "--preset" -> presetArg = args[++i];
                case "--stage" -> stageArg = args[++i];
                case "--chapter" -> chapterArg = Integer.parseInt(args[++i]);
                case "--explore" -> exploreArg = true;
                case "--cloud-type" -> cloudTypeArg = args[++i];
                case "--hview" -> hviewArg = args[++i];
                case "--until-stage" -> untilStage = args[++i];
                case "--fake-scene" -> { fakeScene = args[++i]; fake = true; }
                case "--until" -> untilArg = args[++i];
                case "--cmd" -> startCmds.add(args[++i]);
                case "--then" -> optThen.add(args[++i]);
                case "--then-frames" -> optThenFrames = Integer.parseInt(args[++i]);
                case "--month" -> worldMonth = Double.parseDouble(args[++i]);
                case "--hour" -> worldHour = Double.parseDouble(args[++i]);
                case "--layer" -> worldLayer = args[++i];
                case "--hover" -> worldHover = args[++i];
                case "--click" -> worldClick = args[++i];
                case "--bolt" -> optBolt = args[++i];
                case "--epilepsy-safe" -> Renderer.epilepsySafe = true;
                case "--no-sound" -> System.setProperty("precip.nosound", "true");      // Phase P: Sound.open gives Sound.NONE
                case "--adv" -> {
                    // Settings > Advanced for the diagnostic modes (--snapshot, --bench) and --ui-snapshot: nothing is written anywhere
                    try { optAdv = (optAdv != null ? optAdv : Advanced.DEFAULT).parse(args[++i]); }
                    catch (IllegalArgumentException ex) { System.err.println("[viewer] --adv: " + ex.getMessage()); System.exit(2); return; }
                    Advanced.publish(optAdv);
                }
                case "-h", "--help" -> {
                    System.out.println("usage: precip.Main                      open the main menu\n"
                            + "       precip.Main --new [--seed N] [--world WxH] [--terrain K] [--settlements K] [--preset REGION]   start a new simulation\n"
                            + "       precip.Main --mode hurricane|showcase|world [--cloud-type T] [--fake]   open a lesson mode / the world map directly\n"
                            + "            --mode world --snapshot out.png: [--month M] [--hour H] [--layer temp|cloud|precip|wind|pressure|snow|map] [--select PRESET]\n"
                            + "                 [--cam lon,lat[,zoom]] [--hover lon,lat] [--click lon,lat] [--show-help] [--frames N] [--bench N]\n"
                            + "            with --snapshot out.png: [--chapter N | --explore] [--stage ID] [--hview outside|inside|both] [--until-stage S] [--frames N] [--replay F]\n"
                            + "       precip.Main --load SLOT              continue a saved simulation (slot id or name)\n"
                            + "       precip.Main --ui-snapshot SCREEN out.png [--size WxH] [--dpi S]   (" + String.join("|", UiSnapshot.SCREENS) + ")\n"
                            + "       precip.Main --selftest               end-to-end check with the real simulation\n"
                            + "       precip.Main --set-background IMAGE | --clear-background | --rebind ACTION=KEY | --reset-keys | --print-keys\n"
                            + "       options: --config-dir DIR  --saves-dir DIR  --no-sound\n"
                            + "diagnostics:");
                    System.out.println("usage: precip.Main [--fake | --replay file.jsonl] [--seed N] [--tod 0..1] [--world WxH] [--terrain K] [--settlements K] [--load FILE]"
                            + " [--quality low|medium|high] [--backdrop image] [--preset REGION] [--shade off|on|0..1] [--shade-seconds S]"
                            + " [--snapshot out.png --frames N [--size WxH] [--dpi S] [--cam fit|x,y,zoom|cloud:<id|type>[,zoom]|obj:<kind>[,zoom]|settlement:<kind|name>[,zoom]]"
                            + " [--hide panel,left,minimap,labels,arrows,wind,legend] [--select auto|dead|ID] [--show-help]]"
                            + " [--bench N [--warm F] [--bench-scale S] [--bench-pan] [--profile] [--spawn K] [--cold C] [--sim-speed X] [--bench-feed K]]"
                            + " [--cloud-sheet out.png] [--threads K] [--fps-log] [--bolt X|settlement:K|obj:K[+DX][,ALPHA]] [--epilepsy-safe]"
                            + " [--adv all|particles=100|75|50|25,render=1|0.75|0.5,glint=dot|star," + String.join("[=off],", Advanced.KEYS) + "[=off]]\n"
                            + "v2.3: [--month 1..12.99] [--hour 0..24] [--fake-scene " + String.join("|", FakeSim.SCENES) + "|flood:steep|flood:cliff] [--cmd JSON ...]"
                            + " [--until merge:F|flatband:F|tornado:STAGE|car:STATE|damage:D|hit:first|hit:lift|hit:done|step:ID|t:S[+SECONDS]]"
                            + " [--cam cloud:merged|cloud:flatband|tornado[,zoom]|car:<state|variant|lights>[,zoom]|damage:<kind|any>[,zoom]] [--hide ...,climate]");
                    return;
                }
                default -> System.err.println("[viewer] ignoring unknown argument " + a);
            }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
                // a missing or malformed value used to end in a Java stack trace: say which option, and stop
                System.err.println("[viewer] " + a + ": " + (ex instanceof NumberFormatException ? "not a valid value (" + ex.getMessage() + ")" : "a value is missing or incomplete")
                        + ". See --help.");
                System.exit(2);
                return;
            }
        }
        if (sheet != null) {
            CloudPainter.sheet(sheet, tod, seed != null ? seed : 1234L);
            return;
        }
        if (shadeArg != null) {
            switch (shadeArg) {
                case "off" -> optShadeOn = Boolean.FALSE;
                case "on" -> optShadeOn = Boolean.TRUE;
                default -> { optShadeOn = Boolean.TRUE; optShadeStrength = Model.clamp(Double.parseDouble(shadeArg), 0, 1); }
            }
        }
        optShadeSeconds = shadeSeconds;
        optCam = camSpec;
        File root = projectRoot();
        final File cfgDir = configDir != null ? new File(configDir).getAbsoluteFile() : new File(root, "config");
        final File svDir = savesDir != null ? new File(savesDir).getAbsoluteFile() : new File(root, "saves");
        if (selftestUi || selftest) {
            // the interaction test needs no Python; the end-to-end test runs the real simulation
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8));
            // (Phase I: then the hidden Debug mode's own checks; Phase J: then those of Settings > Advanced; all three always run)
            // (Phase L: then those of standing water, drains and flooding; Phase N: then those of the film that runs over the ground)
            // (Phase O: then those of the wreck - pieces, rubble piles, road wear. then FloatTest (nothing floats) and LifeTest (the people), added in OI.)
            // (Phase P: then those of the sound - the director's rules, the mixer without a line, the sound files, the Sound tab. No audio line is opened here.)
            int ui = UiDriveTest.run() | DebugTest.run() | AdvancedTest.run() | DrainTest.run() | FlowTest.run() | WreckTest.run() | TreeTest.run() | FloatTest.run() | LifeTest.run()
                    | SoundTest.run();
            // the end-to-end test never works in the player's saves/: --saves-dir when given, else a temp folder it removes again
            int e2e = selftest ? SelfTest.run(root, savesDir != null ? new File(savesDir).getAbsoluteFile() : null) : 0;
            System.exit(ui != 0 || e2e != 0 ? 1 : 0);
            return;
        }
        // ---- v2.2: the lesson modes and region presets from the command line
        boolean lesson = "hurricane".equals(mode) || "showcase".equals(mode) || "world".equals(mode);
        if (mode != null && !lesson && !mode.equals("sandbox")) {
            System.err.println("[viewer] unknown --mode \"" + mode + "\" (sandbox, hurricane, showcase or world)");
            System.exit(2);
            return;
        }
        if (presetArg != null) {
            presetArg = presetArg.toLowerCase().replace('-', '_').replace(' ', '_');
            if (!Preset.known(presetArg)) {
                System.err.println("[viewer] unknown --preset \"" + presetArg + "\" (" + String.join(", ", Preset.IDS) + ")");
                System.exit(2);
                return;
            }
            simExtra.add("--preset");
            simExtra.add(presetArg);
        }
        // v2.3 sandbox: --month / --hour are the world's starting month and local hour (PROTOCOL.md, Launch v2.3)
        if (!lesson && replay == null) {
            if (!Double.isNaN(worldMonth)) { simExtra.add("--month"); simExtra.add(String.valueOf(Model.clamp(worldMonth, 1, 12.99))); }
            if (!Double.isNaN(worldHour)) { simExtra.add("--hour"); simExtra.add(String.valueOf(Model.clamp(worldHour, 0, 24))); }
        }
        if (fakeScene != null && !FakeSim.knownScene(fakeScene)) {
            System.err.println("[viewer] unknown --fake-scene \"" + fakeScene + "\" (" + String.join(", ", FakeSim.SCENES) + ", biome:" + String.join("|", Preset.Biome.IDS) + ")");
            System.exit(2);
            return;
        }
        optUntil = untilArg;
        optCmds = startCmds;
        if (lesson && (snapshot != null || bench > 0)) {
            if (snapshot == null) { System.err.println("[viewer] with --mode " + mode + ", --bench needs --snapshot too (it times the screen, then writes the picture)"); System.exit(2); return; }
            ModeSnapshot.Opts o = new ModeSnapshot.Opts();
            o.bench = bench;
            o.mode = mode; o.out = snapshot; o.replay = replay; o.fake = fake; o.seed = seed;
            o.frames = frames; o.w = sizeGiven ? sizeW : 1680; o.h = sizeGiven ? sizeH : 760; o.dpi = dpi;
            o.stage = stageArg; o.chapter = chapterArg; o.explore = exploreArg; o.hview = hviewArg; o.labelsOff = labelsOff;
            o.cloudType = cloudTypeArg; o.untilStage = untilStage;
            o.month = worldMonth; o.hour = worldHour; o.layer = worldLayer; o.select = select; o.cam = camSpec; o.hover = worldHover; o.click = worldClick; o.help = showHelp;
            System.exit(ModeSnapshot.run(root, Settings.load(cfgDir), o));
            return;
        }
        boolean diagnostic = bench > 0 || snapshot != null;
        // --load used to hand a state file to the simulation; that still works for the diagnostic modes
        if (loadSlot != null && (diagnostic || fake || replay != null)) {
            simExtra.add("--load");
            simExtra.add(loadSlot);
            loadSlot = null;
        }
        if (!diagnostic) {
            // ---- the app (menu, saves, settings) and its command-line helpers
            Settings settings = Settings.load(cfgDir);
            if (autosaveSeconds > 0) settings.autosaveSecondsOverride = autosaveSeconds;
            int rc = configCommands(root, settings, new SaveStore(svDir), setBg, clearBg, rebinds, resetKeys, printKeys);
            if (uiScreen != null) {
                if (optAdv != null) settings.adv = optAdv;      // (in memory only: a snapshot never writes settings)
                System.exit(UiSnapshot.run(root, settings, uiScreen, uiOut, sizeGiven ? sizeW : 1680, sizeGiven ? sizeH : 760, dpi, lastBackdropError));
                return;
            }
            if (rc >= 0) {
                System.exit(rc);
                return;
            }
            FrameSource diag = null;
            String diagLabel = null;
            if (lesson) { }                                   // the mode brings its own source (see launchApp)
            else if (replay != null) { diag = new ReplaySource(new File(replay)); diagLabel = "Replay of " + new File(replay).getName(); }
            else if (fake) {
                FakeSim fs = new FakeSim(seed != null ? seed : 1234L, tod, worldW, worldH, null, presetArg, worldMonth, worldHour, fakeScene);
                for (String c : startCmds) fs.handle(c);
                diag = fs;
                diagLabel = "Built-in fake simulation" + (fakeScene != null ? " (" + fakeScene + " scene)" : "");
            }
            NewSimScreen.Params fresh = null;
            if (startNew) {
                fresh = new NewSimScreen.Params();
                fresh.seed = seed;
                fresh.sizeIdx = settings.defaultSize;
                for (int k = 0; k < Settings.SIZES.length; k++) if (worldGiven(args) && Settings.SIZES[k][0] == worldW && Settings.SIZES[k][1] == worldH) fresh.sizeIdx = k;
                fresh.terrain = terrainArg != null ? terrainArg : settings.defaultTerrain;
                fresh.settlements = settlementsArg != null ? settlementsArg : settings.defaultSettlements;
                if (presetArg != null) fresh.preset = presetArg;
                fresh.month = worldMonth;
                fresh.hour = worldHour;
            }
            launchApp(root, settings, new SaveStore(svDir), diag, diagLabel, fresh, loadSlot, lesson ? mode : null, cloudTypeArg, fake);
            return;
        }
        ViewerState state = new ViewerState();
        FrameSource source;
        if (replay != null) {
            source = new ReplaySource(new File(replay));
        } else if (fake) {
            source = new FakeSim(seed != null ? seed : 1234L, tod, worldW, worldH, null, presetArg, worldMonth, worldHour, fakeScene);
        } else {
            List<String> extra = new ArrayList<>(simExtra);
            if (seed != null) { extra.add("--seed"); extra.add(String.valueOf(seed)); }
            source = new SimProcess(root, worldW, worldH, extra);
        }
        BufferedImage backdropImg = backdrop != null ? ImageIO.read(new File(backdrop)) : null;

        if (bench > 0) {
            System.exit(Bench.run(state, source, bench, warm != null ? warm : (replay != null ? 0 : fake ? 600 : 150), benchScale, profile, spawn, cold, simSpeed, feed,
                    sizeW, sizeH, benchPan));
            return;
        }
        if (snapshot != null) {
            System.exit(snapshot(state, source, snapshot, frames, select, showHelp, sizeW, sizeH, dpi, camSpec, hide, backdropImg));
            return;
        }

    }

    /** The message of a rejected {@code --set-background} (shown by a following {@code --ui-snapshot}). */
    private static String lastBackdropError;
    /** {@code --adv}: the Advanced options given on the command line (null = none given). */
    static Advanced optAdv;

    // ---- diagnostics: --shade off|on|<strength>, --shade-seconds S (stills), --cam for the bench
    static Boolean optShadeOn;
    static double optShadeStrength = Double.NaN, optShadeSeconds = -1;
    static String optCam;
    /**
     * Test hook {@code --bolt X[,ALPHA]} (snapshot, and the bench with a scripted source): a lightning bolt put into the frame
     * that is drawn, from the cloud base above world x = X down to the ground. X is a number, {@code settlement:<kind|name>} or
     * {@code obj:<kind>}, each optionally followed by {@code +DX} / {@code -DX}. Viewer only: the simulation never hears of it.
     */
    static String optBolt;

    /** Add the {@code --bolt} bolt to the current frame (once per frame; call after every accepted frame). */
    static void injectBolt(ViewerState st) {
        if (optBolt == null) return;
        synchronized (st) {
            Model.Frame f = st.cur;
            if (f == null || st.hello == null || f == boltFrame) return;
            boltFrame = f;
            String[] p = optBolt.split(",");
            String head = p[0].trim();
            double dx = 0;
            int cut = Math.max(head.lastIndexOf('+'), head.lastIndexOf('-'));
            if (cut > 0 && head.contains(":")) { dx = Double.parseDouble(head.substring(cut)); head = head.substring(0, cut); }
            double x = Double.NaN;
            if (head.startsWith("settlement:")) {
                for (Model.Settlement s : st.hello.settlements) if (s.kind.equals(head.substring(11)) || s.name.equalsIgnoreCase(head.substring(11))) { x = (s.x0 + s.x1) / 2; break; }
            } else if (head.startsWith("obj:")) {
                Model.Obj pick = null;
                for (Model.Obj o : st.objects) if (o.kind.equals(head.substring(4)) && (pick == null || o.h > pick.h)) pick = o;
                if (pick != null) x = pick.x;
            } else x = Double.parseDouble(head);
            if (Double.isNaN(x)) { System.err.println("[viewer] --bolt: nothing matches " + head); optBolt = null; return; }
            x += dx;
            Model.Cloud host = boltHost(f, x);
            Model.Bolt b = testBolt(st.hello, f, x, p.length > 1 ? Model.clamp(Double.parseDouble(p[1]), 0, 1) : 0.85);
            List<Model.Bolt> all = new ArrayList<>(f.lightning);
            all.add(b);
            f.lightning = all;
            if (!boltSaid) {
                boltSaid = true;
                System.out.println(String.format(java.util.Locale.ROOT, "[viewer] --bolt: %.0f,%.0f -> %.0f,%.0f alpha %.2f, %s", b.pts[0], b.pts[1], b.pts[b.pts.length - 2], b.pts[b.pts.length - 1],
                        b.alpha, host != null ? "from the base of " + host.name + " (" + host.type + ")" : "no cloud above: free-standing"));
            }
        }
    }

    /** The darkest formed cloud above world x (a storm cloud when there is one), or null. */
    static Model.Cloud boltHost(Model.Frame f, double x) {
        Model.Cloud host = null;
        for (Model.Cloud c : f.clouds) {
            if (c.form < 0.3) continue;
            double x0 = c.x - c.w / 2, x1 = c.x + c.w / 2;
            for (double[] q : c.puffs) { x0 = Math.min(x0, c.x + q[0] - q[2]); x1 = Math.max(x1, c.x + q[0] + q[2]); }
            if (x < x0 || x > x1) continue;
            if (host == null || c.darkness > host.darkness) host = c;
        }
        return host;
    }

    /** A jagged bolt (the same for the same x) from the base of {@link #boltHost}, or from 430 px up when the sky above x is clear, down to the ground. */
    static Model.Bolt testBolt(Model.Hello hello, Model.Frame f, double x, double alpha) {
        Model.Terrain terr = hello.terrain;
        Model.Cloud host = boltHost(f, x);
        double cx = x, cy = host != null ? Renderer.cloudBase(host) : terr.at(x) - 430;
        java.util.Random r = new java.util.Random(Math.round(x * 16) * 31 + 7);
        List<Double> pts = new ArrayList<>();
        pts.add(cx); pts.add(cy);
        while (true) {
            cy += 18 + r.nextDouble() * 22;
            cx += r.nextGaussian() * 16;
            double gy = terr.at(cx);
            if (cy >= gy) { pts.add(cx); pts.add(gy); break; }
            pts.add(cx); pts.add(cy);
        }
        Model.Bolt b = new Model.Bolt();
        b.pts = new double[pts.size()];
        for (int k = 0; k < b.pts.length; k++) b.pts[k] = pts.get(k);
        b.alpha = alpha;
        return b;
    }

    private static Model.Frame boltFrame;
    private static boolean boltSaid;
    /** v2.3 diagnostics: {@code --until SPEC} (see {@link Until}) and {@code --cmd JSON} (sent before the first frame). */
    static String optUntil;
    static List<String> optCmds = new ArrayList<>();
    /** {@code --then JSON}: commands sent once the {@code --until} condition is met (e.g. set the clock to noon), then {@code --then-frames} more frames pass. */
    static List<String> optThen = new ArrayList<>();
    static int optThenFrames = 60;

    /**
     * The {@code --cmd} list of a diagnostic run. A plain JSON command is sent before the first frame;
     * {@code @12.5:JSON} is sent 12.5 simulated seconds in, {@code @every:5:JSON} every 5 simulated seconds.
     */
    static final class Cmds {
        private final List<String> json = new ArrayList<>();
        private final List<double[]> when = new ArrayList<>();      // {at, period (0 = once), done}
        private double t0 = Double.NaN;

        Cmds(List<String> specs) {
            for (String s : specs) {
                double at = 0, every = 0;
                String j = s;
                if (s.startsWith("@every:")) { int c = s.indexOf(':', 7); every = Double.parseDouble(s.substring(7, c)); at = every; j = s.substring(c + 1); }
                else if (s.startsWith("@")) { int c = s.indexOf(':'); at = Double.parseDouble(s.substring(1, c)); j = s.substring(c + 1); }
                json.add(j);
                when.add(new double[]{at, every, 0});
            }
        }

        /** The commands that are due at sim time {@code t} (call with the time of every frame; before the first frame with NaN). */
        List<String> due(double t) {
            List<String> out = new ArrayList<>();
            if (!Double.isNaN(t) && Double.isNaN(t0)) t0 = t;
            for (int k = 0; k < json.size(); k++) {
                double[] w = when.get(k);
                if (w[2] != 0) continue;
                if (w[0] <= 0 && w[1] <= 0) { out.add(json.get(k)); w[2] = 1; continue; }
                if (Double.isNaN(t) || t - t0 < w[0]) continue;
                out.add(json.get(k));
                if (w[1] > 0) w[0] += w[1]; else w[2] = 1;
            }
            return out;
        }
    }

    /**
     * {@code --until SPEC}: keep the simulation running until something is on screen, then take the picture / start the bench.
     * <pre>
     *   merge:F            a cloud is absorbing another and the merger is at least F (0..1; merge:1 = complete)
     *   tornado:STAGE      a tornado is in that stage (funnel | touchdown | mature | roping)
     *   car:STATE          a car is in that state (tossed | wrecked | driving)
     *   damage:D           an object has taken at least D damage (0..1)
     *   hit:first|lift|done  Phase O: a piece torn off a building is in the air | the funnel has lifted after a hit | all of it has come down
     *   step:ID            the showcase's lifetime tour is at that step
     *   t:S                S simulated seconds have passed since the first frame
     * </pre>
     * {@code +S} after any of them waits S more simulated seconds, e.g. {@code tornado:mature+8}.
     */
    static final class Until {
        final String kind, arg;
        final double extra;
        private double t0 = Double.NaN, metAt = Double.NaN;

        Until(String spec) {
            int plus = spec.lastIndexOf('+');
            extra = plus > 0 ? Double.parseDouble(spec.substring(plus + 1)) : 0;
            String head = plus > 0 ? spec.substring(0, plus) : spec;
            int c = head.indexOf(':');
            kind = c < 0 ? head : head.substring(0, c);
            arg = c < 0 ? "" : head.substring(c + 1);
        }

        /** Call once per frame (with the state lock held or on the feeding thread). */
        boolean reached(ViewerState st) {
            Model.Frame f = st.cur;
            if (f == null) return false;
            if (Double.isNaN(t0)) t0 = f.t;
            if (Double.isNaN(metAt) && met(st, f)) metAt = f.t;
            return !Double.isNaN(metAt) && f.t >= metAt + extra;
        }

        private boolean met(ViewerState st, Model.Frame f) {
            switch (kind) {
                case "merge" -> {
                    double want = arg.isEmpty() ? 0 : Double.parseDouble(arg);
                    for (Model.Cloud c : f.clouds) if (c.parts.length > 0 && c.merge >= want - 1e-9) return true;
                    return false;
                }
                case "flatband" -> {
                    double want = arg.isEmpty() ? 0 : Double.parseDouble(arg);
                    for (Model.Cloud c : f.clouds) if (flatBand(c) && c.merge >= want - 1e-9) return true;
                    return false;
                }
                case "tornado" -> {
                    for (Model.Tornado t : f.tornadoes) if (arg.isEmpty() || t.stage.equals(arg)) return true;
                    return false;
                }
                case "car" -> {
                    for (Model.Car c : f.cars) if (c.state.equals(arg)) return true;
                    return false;
                }
                case "damage" -> {
                    double want = arg.isEmpty() ? 0.05 : Double.parseDouble(arg);
                    for (Model.Obj o : st.objects) if (o.damage >= want) return true;
                    return false;
                }
                // Phase O: hit:first = the first piece is in the air, hit:lift = the funnel has left the ground after a hit,
                // hit:done = everything has come down again (the default)
                case "hit" -> { return st.wreck.reached(arg.isEmpty() ? "done" : arg); }
                case "step" -> { return arg.equals(f.tourStep); }
                case "t" -> { return f.t - t0 >= Double.parseDouble(arg); }
                default -> { return true; }
            }
        }
    }

    /** A cumulus or cumulonimbus that has absorbed (or is absorbing) a flat layer. */
    static boolean flatBand(Model.Cloud c) {
        if (!"cumulus".equals(c.type) && !"cumulonimbus".equals(c.type)) return false;
        for (Model.Part p : c.parts) if (Model.sheetKind(p.kind)) return true;
        return false;
    }

    /** Apply the {@code --shade} options to a diagnostic renderer (snapshot, bench). */
    static void applyShade(Renderer r) {
        if (optShadeOn != null) r.shadeOn = optShadeOn;
        if (!Double.isNaN(optShadeStrength)) r.shadeStrength = optShadeStrength;
        r.shadeStillSeconds = optShadeSeconds;
    }

    private static boolean worldGiven(String[] args) {
        for (String a : args) if (a.equals("--world")) return true;
        return false;
    }

    /**
     * Command-line edits of the settings, through the same code the settings screen uses. Returns
     * the process exit code when at least one of them was asked for (the app then does not open),
     * else -1.
     */
    private static int configCommands(File root, Settings settings, SaveStore store, String setBg, boolean clearBg, List<String> rebinds,
                                      boolean resetKeys, boolean printKeys) {
        if (setBg == null && !clearBg && rebinds.isEmpty() && !resetKeys && !printKeys) return -1;
        int rc = 0;
        AppShell app = new AppShell(root, settings, store);
        if (setBg != null) {
            String before = settings.bgFile;
            String msg = app.chooseBackgroundNow(new File(setBg));
            System.out.println("[viewer] " + msg);
            if (app.backdropError() != null) {
                rc = 3;
                lastBackdropError = app.backdropError();
                System.out.println("[viewer] background in use is still: " + (before.isEmpty() ? "the default sky" : before));
            }
        }
        if (clearBg) {
            app.clearBackground();
            System.out.println("[viewer] background cleared: default sky");
        }
        if (resetKeys) {
            settings.keys = KeyMap.defaults();
            app.settingsChanged();
            System.out.println("[viewer] keys reset to defaults");
        }
        SettingsScreen screen = new SettingsScreen(app, SettingsScreen.CONTROLS);
        for (String rb : rebinds) {
            String[] kv = rb.split("=", 2);
            Action a = null;
            for (Action x : Action.values()) if (x.name().equalsIgnoreCase(kv[0].trim())) a = x;
            int code = kv.length == 2 ? KeyMap.parseKey(kv[1]) : -1;
            if (a == null || code <= 0 || !KeyMap.bindable(code)) {
                System.out.println("[viewer] cannot rebind \"" + rb + "\": " + (a == null ? "unknown action" : "unknown key") + " (use ACTION=KEY, e.g. TOGGLE_MINIMAP=N)");
                rc = 2;
                continue;
            }
            screen.bind(a, code);
            System.out.println("[viewer] " + a.name() + " (" + a.label + ") is now on " + KeyMap.keyName(code)
                    + (screen.keyWarning != null ? "  [warning: " + console(screen.keyWarning) + "]" : ""));
        }
        if (printKeys) {
            System.out.println("[viewer] key bindings (" + (KeyMap.isDefault(settings.keys) ? "defaults" : "custom") + ") from " + settings.file());
            for (Action a : Action.values())
                System.out.println(String.format("  %-20s %-26s %s", a.name(), console(a.label), console(KeyMap.keysLabel(settings.keys.get(a)))));
        }
        app.io.shutdown();
        return rc;
    }

    /**
     * Text for the terminal: the on-screen labels use arrows and typographic dashes that most Windows console code
     * pages cannot show (--print-keys listed the arrow keys as "?"). When the output encoding lacks them, plain words are used.
     */
    static String console(String s) {
        try {
            if (System.out.charset().newEncoder().canEncode(s)) return s;
        } catch (RuntimeException ignored) { }
        return s.replace("←", "Left").replace("→", "Right").replace("↑", "Up").replace("↓", "Down")
                .replace("−", "-").replace("—", "-").replace("–", "-").replace("…", "...").replace("·", "-");
    }

    /** Open the window: the main menu, or straight into a game when one was asked for on the command line. */
    private static void launchApp(File root, Settings settings, SaveStore store, FrameSource diag, String diagLabel, NewSimScreen.Params fresh, String loadSlot,
                                  String lessonMode, String cloudType, boolean fakeModes) {
        AppShell app = new AppShell(root, settings, store);
        app.fakeModes = fakeModes;
        // Phase P: the one place the sound is switched on. The selftests, --snapshot, --bench and --ui-snapshot never come here.
        app.sound = Sound.open(root, settings);
        app.onExit = () -> System.exit(0);
        Runtime.getRuntime().addShutdownHook(new Thread(app::emergencyStop, "stop-sim"));
        SwingUtilities.invokeLater(() -> {
            try {
                javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());   // for the file chooser
            } catch (Exception ignored) { }
            app.applySettings();
            app.loadStoredBackdrop(false);
            app.createWindow();
            if ("hurricane".equals(lessonMode)) app.startHurricane();
            else if ("showcase".equals(lessonMode)) app.startShowcase(cloudType);
            else if ("world".equals(lessonMode)) app.startWorld();
            else if (diag != null) app.startDiagnostic(diag, diagLabel);
            else if (fresh != null) {
                fresh.name = fresh.preset.isEmpty() ? store.nextDefaultName() : store.uniqueName(Preset.nameOf(fresh.preset));
                app.startNew(fresh);
            } else if (loadSlot != null) {
                SaveStore.Slot s = store.find(loadSlot);
                if (s != null && s.playable()) app.startLoad(s);
                else {
                    app.showMenu(s != null ? s.id : null);
                    app.toast(s == null ? "There is no saved simulation called \"" + loadSlot + "\"." : "\"" + s.name + "\" cannot be opened: " + s.problem);
                }
            } else app.showMenu(null);
            app.showWindow();
            // launch check (see run.bat tests): PRECIP_WINDOW_REPORT=<file.png> makes the window describe itself once
            String report = System.getenv("PRECIP_WINDOW_REPORT");
            if (report != null && !report.isEmpty()) {
                javax.swing.Timer t = new javax.swing.Timer(4000, e -> app.windowReport(new File(report)));
                t.setRepeats(false);
                t.start();
            }
        });
    }

    private static int[] parseSize(String s) {
        String[] p = s.toLowerCase().split("x");
        return new int[]{Math.max(320, Integer.parseInt(p[0].trim())), Math.max(240, Integer.parseInt(p[1].trim()))};
    }

    /** Point a camera according to a {@code --cam} spec (see the class comment). */
    static void applyCam(Camera cam, String spec, ViewerState state) {
        if (spec == null || spec.isEmpty()) { cam.home(); cam.snap(); return; }
        if (spec.equals("fit")) { cam.fit(); cam.snap(); return; }
        if (spec.startsWith("tornado") && !spec.contains(":")) spec = "tornado:" + (spec.contains(",") ? "any" + spec.substring(spec.indexOf(',')) : "any");
        String[] head = spec.split(":", 2);
        if (head.length == 1) {
            String[] p = spec.split(",");
            cam.set(Double.parseDouble(p[0]), Double.parseDouble(p[1]), p.length > 2 ? Double.parseDouble(p[2]) : cam.fillZoom(), true);
            return;
        }
        String[] p = head[1].split(",");
        String key = p[0].trim().toLowerCase();
        Double zoom = p.length > 1 ? Double.parseDouble(p[1]) : null;
        synchronized (state) {
            switch (head[0]) {
                case "cloud" -> {
                    Model.Cloud pick = null;
                    if (state.cur != null) for (Model.Cloud c : state.cur.clouds) {
                        // v2.3: "merged" = a cloud that has absorbed another, "absorbed" = one that is being absorbed
                        boolean m = key.matches("\\d+") ? c.id == Long.parseLong(key) : key.equals("merged") ? c.parts.length > 0
                                : key.equals("flatband") ? flatBand(c)      // a heaped cloud that absorbed a flat layer (drawn as a straight band)
                                : key.equals("absorbed") ? c.mergingInto >= 0 : c.type.equals(key);
                        if (m && (pick == null || c.form > pick.form)) pick = c;
                    }
                    if (pick == null) { System.err.println("[viewer] --cam: no cloud matches " + key); cam.home(); break; }
                    double[] b = SharedClouds.bounds(pick);
                    double z = zoom != null ? zoom : Math.min(cam.vw / ((b[2] - b[0]) * 1.25), cam.vh / ((b[3] - b[1]) * 1.5));
                    cam.set((b[0] + b[2]) / 2, (b[1] + b[3]) / 2, z, true);
                    System.out.println("[viewer] camera on " + pick.name + " (" + pick.type + ", form " + pick.form + ", stage " + pick.stage + ")"
                            + (pick.kind == Model.SUPERCELL || pick.parts.length > 0 ? String.format(java.util.Locale.ROOT,
                            "  [x %.0f y %.0f w %.0f h %.0f, %d puffs, tint %d,%d,%d, darkness %.2f, opacity %.2f, anvil %.2f, shear %.0f, rotation %.2f, wall %.2f, overshoot %.2f, hook %.2f, meso_x %.0f, parts %d, merge %.2f]",
                            pick.x, pick.y, pick.w, pick.h, pick.puffs.length, pick.tint[0], pick.tint[1], pick.tint[2], pick.darkness, pick.opacity, pick.anvil, pick.shear, pick.rotation, pick.wall,
                            pick.overshoot, pick.hook, pick.mesoX, pick.parts.length, pick.merge) : ""));
                }
                case "tornado" -> {
                    // v2.3: the whole funnel from the cloud base to the ground, or (with a zoom) its foot
                    Model.Tornado pick = state.cur == null || state.cur.tornadoes.isEmpty() ? null : state.cur.tornadoes.get(0);
                    if (pick == null) { System.err.println("[viewer] --cam: no tornado on the ground or in the air"); cam.home(); break; }
                    double span = pick.groundY - pick.baseY;
                    if (zoom != null) cam.set(pick.x, pick.groundY - cam.vh / zoom * 0.34, zoom, true);
                    else cam.set(pick.x + pick.lean * 0.5, pick.baseY + span * 0.42, Math.min(Camera.MAX_ZOOM, cam.vh / (span * 1.75)), true);
                    System.out.println(String.format(java.util.Locale.ROOT, "[viewer] camera on tornado %d (%s, EF%d, %.0f m/s, reach %.2f, width %.0f, lean %.0f, debris %.2f)",
                            pick.id, pick.stage, pick.ef, pick.windMs, pick.reach, pick.width, pick.lean, pick.debris));
                }
                case "car" -> {
                    // v2.3: a car by state (tossed | wrecked | driving | stopped), variant, or "lights"
                    Model.Car pick = null;
                    if (state.cur != null) for (Model.Car c : state.cur.cars) {
                        boolean m = key.equals("any") || c.state.equals(key) || c.variant.equals(key) || (key.equals("lights") && c.lights);
                        if (m && (pick == null || c.lift > pick.lift)) pick = c;
                    }
                    if (pick == null) { System.err.println("[viewer] --cam: no car matches " + key); cam.home(); break; }
                    double z = zoom != null ? zoom : 2.2;
                    // a car the tornado has lifted high: pull back far enough that both it and the road are in the picture
                    if (pick.lift > 40) z = Math.min(z, cam.vh / (pick.lift * 1.5 + 150));
                    cam.set(pick.x, pick.y - Math.max(cam.vh / z * 0.22, pick.lift * 0.5), z, true);
                    System.out.println(String.format(java.util.Locale.ROOT, "[viewer] camera on car %d (%s, %s, lift %.0f, spin %.2f, lights %s, wipers %s)", pick.id, pick.variant, pick.state,
                            pick.lift, pick.spin, pick.lights, pick.wipers));
                }
                case "damage" -> {
                    // v2.3: the most damaged object (optionally of one kind)
                    Model.Obj pick = null;
                    for (Model.Obj o : state.objects) if ((key.equals("any") || o.kind.equals(key)) && o.damage > 0 && (pick == null || o.damage * o.w > pick.damage * pick.w)) pick = o;
                    if (pick == null) { System.err.println("[viewer] --cam: nothing is damaged"); cam.home(); break; }
                    double z = zoom != null ? zoom : 1.6;
                    cam.set(pick.x, pick.y - cam.vh / z * 0.26, z, true);
                    System.out.println(String.format(java.util.Locale.ROOT, "[viewer] camera on damaged %s %d (damage %.2f)", pick.kind, pick.id, pick.damage));
                }
                case "obj" -> {
                    Model.Obj pick = null;
                    for (Model.Obj o : state.objects) if (o.kind.equals(key) || o.variant.equals(key)) { if (pick == null || o.h > pick.h) pick = o; }
                    if (pick == null) { System.err.println("[viewer] --cam: no object of kind " + key); cam.home(); break; }
                    cam.set(pick.x, pick.y - pick.h * 0.5, zoom != null ? zoom : Math.min(Camera.MAX_ZOOM, cam.vh / (pick.h * 2.6)), true);
                }
                case "settlement" -> {
                    Model.Settlement pick = null;
                    if (state.hello != null) for (Model.Settlement s : state.hello.settlements)
                        if (s.kind.equals(key) || s.name.toLowerCase().equals(key)) { pick = s; break; }
                    if (pick == null) { System.err.println("[viewer] --cam: no settlement " + key); cam.home(); break; }
                    double mid = (pick.x0 + pick.x1) / 2, gy = state.hello.terrain.at(mid);
                    double z = zoom != null ? zoom : Math.min(Camera.MAX_ZOOM, cam.vw / ((pick.x1 - pick.x0) * 1.15));
                    cam.set(mid, gy - cam.vh / z * 0.30, z, true);
                }
                default -> { System.err.println("[viewer] --cam: unknown spec " + spec); cam.home(); }
            }
        }
        cam.snap();
    }

    private static int snapshot(ViewerState state, FrameSource source, String out, int frames, String select, boolean help,
                                int w, int h, double dpi, String camSpec, String hide, BufferedImage backdrop) throws Exception {
        Until until = optUntil == null ? null : new Until(optUntil);
        final Cmds cmds = new Cmds(optCmds);
        if (source instanceof Scripted fs) {
            state.acceptLine(fs.hello());
            FakeSim sim = fs instanceof FakeSim f0 ? f0 : null;
            if (sim != null) for (String c : cmds.due(Double.NaN)) sim.handle(c);
            int max = until == null ? frames : 40_000, k = 0;
            for (; k < max; k++) {
                state.acceptLine(fs.step());
                if (sim != null) for (String c : cmds.due(state.cur.t)) sim.handle(c);
                if (until != null && until.reached(state)) break;
            }
            // v2.3: run to the condition (at most 40 000 frames), then --frames more are not needed
            if (until != null) System.out.println("[viewer] --until " + optUntil + (k < 40_000 ? " reached after " + (k + 1) + " frames" : " NOT reached in 40000 frames"));
            if (sim != null && !optThen.isEmpty()) {
                for (String c : optThen) sim.handle(c);
                for (int j = 0; j < optThenFrames; j++) state.acceptLine(fs.step());
            }
        } else {
            source.start(state);
            for (String c : cmds.due(Double.NaN)) source.send(c);
            long deadline = System.currentTimeMillis() + (until != null ? 300_000 : Math.max(30_000, frames * 150L));
            if (until != null) source.send("{\"cmd\":\"speed\",\"value\":4}");
            boolean met = false;
            while (System.currentTimeMillis() < deadline) {
                synchronized (state) {
                    if (state.cur != null) for (String c : cmds.due(state.cur.t)) source.send(c);
                    if (state.error != null) break;
                    if (until != null ? until.reached(state) : state.framesReceived >= frames) { met = true; break; }
                }
                Thread.sleep(20);
            }
            if (until != null) System.out.println("[viewer] --until " + optUntil + (met ? " reached" : " NOT reached in time"));
            if (!optThen.isEmpty()) {
                // e.g. put the clock at noon for the picture: the sim glides there, so give it a moment
                source.send("{\"cmd\":\"speed\",\"value\":1}");
                for (String c : optThen) source.send(c);
                long f0;
                synchronized (state) { f0 = state.framesReceived; }
                long end = System.currentTimeMillis() + 20_000;
                while (System.currentTimeMillis() < end) {
                    synchronized (state) { if (state.framesReceived - f0 >= optThenFrames) break; }
                    Thread.sleep(20);
                }
            }
            source.stop();
            synchronized (state) {
                System.err.println("[viewer] received " + state.framesReceived + " frames" + (state.error != null ? " (error: " + state.error.split("\n")[0] + ")" : ""));
            }
        }
        if (select != null) {
            synchronized (state) {
                if (select.equals("auto")) {
                    // pick the most interesting alive cloud: precipitating and biggest
                    double best = -1;
                    if (state.cur != null) for (Model.Cloud c : state.cur.clouds) {
                        double s = c.w * (c.stage.equals("precipitating") ? 3 : 1);
                        if (s > best) { best = s; state.selectedId = c.id; }
                    }
                } else if (select.equals("dead")) {
                    for (ViewerState.Life l : state.lives) if (!l.alive) { state.selectedId = l.id; break; }
                } else {
                    state.selectedId = Long.parseLong(select);
                }
            }
        }
        ParallelRenderer pr = new ParallelRenderer(ParallelRenderer.defaultThreads());
        pr.shared.setAsync(false); // a single still frame: everything exact, nothing mid-fade
        Renderer r = pr.primary();
        r.showHelp = help;
        r.backdropImage = backdrop;
        applyShade(r);
        for (String hk : hide.split(",")) {
            switch (hk.trim()) {
                case "panel" -> r.showRightPanel = false;
                case "left" -> r.showLeftPanel = false;
                case "minimap" -> r.showMinimap = false;
                case "labels" -> r.showLabels = false;
                case "arrows" -> r.showArrows = false;
                case "wind" -> r.showWind = false;
                case "legend" -> r.showLegend = false;
                case "climate" -> r.showClimate = false;
                case "all" -> { r.showRightPanel = false; r.showLeftPanel = false; r.showMinimap = false; r.showLabels = false; r.showArrows = false; r.showWind = false; r.showClimate = false; }
                default -> { }
            }
        }
        double t;
        synchronized (state) { t = state.cur != null ? state.cur.t : 0; }
        injectBolt(state);
        Camera cam = new Camera();
        pr.layout(cam, state, w, h);
        applyCam(cam, camSpec, state);
        int dw = (int) Math.round(w * dpi), dh = (int) Math.round(h * dpi);
        BufferedImage img = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        // --adv render=0.75|0.5: drawn smaller and stretched to the picture's size, as the window does it (ViewerPanel.paintComponent)
        double rs = Advanced.cur.capScale(dpi);
        if (rs < dpi - 1e-6) {
            int sw = Math.max(1, (int) Math.ceil(w * rs)), sh = Math.max(1, (int) Math.ceil(h * rs));
            BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
            pr.render(small, rs, 0, 0, w, h, state, cam, -1, t, source.describe());
            java.awt.Graphics2D sg = img.createGraphics();
            sg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            sg.drawImage(small, 0, 0, dw, dh, 0, 0, sw, sh, null);
            sg.dispose();
        } else
        pr.render(img, dpi, 0, 0, w, h, state, cam, -1, t, source.describe());
        pr.shutdown();
        File f = new File(out);
        if (f.getAbsoluteFile().getParentFile() != null) f.getAbsoluteFile().getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
        System.out.println(String.format(java.util.Locale.ROOT, "[viewer] snapshot written: %s (%dx%d, t=%.1f, camera %.0f,%.0f zoom %.2f, under-cloud dimming %.3f%s)",
                f.getAbsolutePath(), dw, dh, t, cam.cx, cam.cy, cam.zoom, r.shadeOn ? r.shadeLevel * r.shadeStrength * CloudShade.MAX_ALPHA : 0, r.shadeOn ? "" : " (off)"));
        return 0;
    }

    /** Project root = the directory holding viewer/ (resolved from the class location, else cwd). */
    static File projectRoot() {
        String prop = System.getProperty("precip.root");
        if (prop != null) return new File(prop).getAbsoluteFile();
        try {
            File loc = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsoluteFile();
            for (File d = loc; d != null; d = d.getParentFile()) {
                if (new File(d, "sim").isDirectory() || new File(d, "PROTOCOL.md").isFile()) return d;
            }
        } catch (Exception ignored) { }
        File cwd = new File("").getAbsoluteFile();
        for (File d = cwd; d != null; d = d.getParentFile()) {
            if (new File(d, "sim").isDirectory() || new File(d, "PROTOCOL.md").isFile()) return d;
        }
        return cwd;
    }
}
