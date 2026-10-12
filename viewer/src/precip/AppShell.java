package precip;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * The application around the simulation: one window for the whole session, showing either the
 * main menu or a game, with the pause menu, settings and dialogs drawn on top.
 * <p>
 * Both modes use a {@link ViewerPanel}. In a game it shows the player's world; in the menu it
 * shows a small decorative world (the built-in fake simulation with every HUD element off) so the
 * menu sits over a live sky, or over the player's background picture. The shell itself is the
 * panel's {@link ViewerPanel.Overlay}: it paints the screens of {@link #stack} and gets the input
 * first.
 * <p>
 * Everything here runs on the Swing event thread; slow work (image decoding, thumbnails, stopping
 * the simulation process) goes to {@link #io} or a short-lived thread and comes back with
 * {@code invokeLater}. The shell never calls {@code System.exit}: when the player quits it runs
 * {@link #onExit}.
 */
final class AppShell implements ViewerPanel.Overlay {
    final File root;
    final Settings settings;
    final SaveStore store;
    final Ui ui = new Ui();
    final GameHud hud = new GameHud(this);
    /** One background thread for file work, in order. */
    final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shell-io");
        t.setDaemon(true);
        return t;
    });
    private final List<Screen> stack = new ArrayList<>();
    private final List<Thread> stoppers = new ArrayList<>();

    JFrame frame;
    /** The panel on screen: the menu's decorative world or the game. */
    ViewerPanel panel;
    /** The running game; null while the menu is shown. */
    GameSession session;
    MainMenuScreen menu;
    private FrameSource menuSim;
    Runnable onExit = () -> { };

    private BufferedImage backdrop;
    private int backdropW, backdropH;
    private String backdropError;
    private boolean backdropBusy;

    private String toast;
    private long toastUntil;
    private javax.swing.Timer ticker;
    private boolean settingsDirty, quitting, reseedConfirmed;
    private long settingsDirtySince;
    private Rectangle windowedBounds;
    /** Fake slot list for UI snapshots; null = read the real saves folder. */
    List<SaveStore.Slot> slotsOverride;
    /** The mode chosen in the main menu (0 Sandbox, 1 Hurricane Formation, 2 Cloud Formations, 3 World Simulation, 4 Debug once unlocked); remembered while the app runs. */
    int menuMode;
    /**
     * Clicks on the menu's cloud so far (the tenth unlocks the Debug mode). Kept here, not on the menu screen: a new
     * {@link MainMenuScreen} is built every time the player comes back from a game.
     */
    int debugClicks;
    /** v2.3: the simulation arguments of the sandbox last opened from the world map ({@code --preset}, {@code --month}, {@code --hour}, ...); null before. */
    List<String> lastWorldLaunch;
    /** Run the lesson modes on the built-in fakes instead of Python ({@code --fake}, tests). */
    boolean fakeModes;
    /** The screen of the running lesson mode (hurricane / cloud formations); null otherwise. */
    Screen modeScreen;
    /**
     * Phase P: the game's sound. {@link Sound#NONE} unless {@code Main.launchApp} opened one, so every shell a test or a
     * snapshot builds is silent and opens no audio line.
     */
    Sound sound = Sound.NONE;

    AppShell(File root, Settings settings, SaveStore store) {
        this.root = root;
        this.settings = settings;
        this.store = store;
    }

    // =====================================================================================
    // screens

    void push(Screen s) { stack.add(s); }

    void pop(Screen s) { stack.remove(s); }

    Screen top() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

    boolean has(Class<? extends Screen> type) {
        for (Screen s : stack) if (type.isInstance(s)) return true;
        return false;
    }

    void toast(String s) {
        toast = s;
        toastUntil = System.nanoTime() + (2_200_000_000L + Math.min(60, s.length()) * 40_000_000L);
    }

    /** The message on screen right now, or null (tests). */
    String toastText() { return toast != null && System.nanoTime() < toastUntil ? toast : null; }

    void openNewSimulation() { push(new NewSimScreen(this)); }

    void openSettings(int tab) { push(new SettingsScreen(this, tab)); }

    // =====================================================================================
    // the window

    /** Create the window (not yet visible). Call {@link #showMenu} or start a session, then {@link #showWindow}. */
    void createWindow() {
        frame = new JFrame("Precipitation");
        try {
            File icon = new File(root, "assets" + File.separator + "precipitation.png");
            if (icon.isFile()) frame.setIconImage(ImageIO.read(icon));
        } catch (Exception ignored) { }
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { requestQuit(); }
        });
        frame.setMinimumSize(new java.awt.Dimension(1040, 620));
        Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int w = Math.min(settings.hasWindow ? settings.winW : 1680, (int) (scr.width * 0.98));
        int h = Math.min(settings.hasWindow ? settings.winH : 760, (int) (scr.height * 0.96));
        frame.setSize(w, h);
        if (settings.hasWindow && onAScreen(new Rectangle(settings.winX, settings.winY, w, h))) frame.setLocation(settings.winX, settings.winY);
        else frame.setLocationRelativeTo(null);
        windowedBounds = frame.getBounds();
        if (settings.winMax) frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentMoved(java.awt.event.ComponentEvent e) { rememberWindow(); }

            @Override public void componentResized(java.awt.event.ComponentEvent e) { rememberWindow(); }
        });
        frame.addWindowStateListener(e -> rememberWindow());
        // an image file dropped anywhere on the window becomes the background
        new java.awt.dnd.DropTarget(frame, java.awt.dnd.DnDConstants.ACTION_COPY, new java.awt.dnd.DropTargetAdapter() {
            @Override public void drop(java.awt.dnd.DropTargetDropEvent e) {
                try {
                    e.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);
                    Object data = e.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    List<?> files = (List<?>) data;
                    e.dropComplete(true);
                    if (!files.isEmpty() && files.get(0) instanceof File f) chooseBackground(f);
                } catch (Exception ex) {
                    e.dropComplete(false);
                    toast("That could not be dropped here — drop an image file.");
                }
            }
        }, true);
        ticker = new javax.swing.Timer(200, e -> tick());
        ticker.start();
    }

    /** Tests: close the (possibly never shown) window again. */
    void disposeWindow() {
        if (ticker != null) ticker.stop();
        if (frame != null) frame.dispose();
    }

    void showWindow() {
        if (settings.fullscreen) applyFullscreen();
        frame.setVisible(true);
        if (panel != null) panel.requestFocusInWindow();
    }

    private static boolean onAScreen(Rectangle r) {
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle b = d.getDefaultConfiguration().getBounds();
            Rectangle i = b.intersection(r);
            if (i.width >= 200 && i.height >= 120 && r.y >= b.y - 10) return true;
        }
        return false;
    }

    private void rememberWindow() {
        if (frame == null || !frame.isVisible() || frame.isUndecorated() || quitting) return;
        boolean max = (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0;
        if (!max && (frame.getExtendedState() & JFrame.ICONIFIED) == 0) {
            Rectangle b = frame.getBounds();
            windowedBounds = b;
            settings.winX = b.x; settings.winY = b.y; settings.winW = b.width; settings.winH = b.height;
        }
        settings.winMax = max;
        settings.hasWindow = true;
        markDirty();
    }

    void toggleFullscreen() {
        settings.fullscreen = !settings.fullscreen;
        applyFullscreen();
        settingsChanged();
    }

    /** Borderless window covering the whole screen, or back to the remembered window. */
    private void applyFullscreen() {
        if (frame == null || settings.fullscreen == frame.isUndecorated()) return;
        boolean visible = frame.isVisible();
        GraphicsConfiguration gc = frame.getGraphicsConfiguration();
        frame.dispose();
        frame.setUndecorated(settings.fullscreen);
        if (settings.fullscreen) {
            frame.setExtendedState(JFrame.NORMAL);
            frame.setBounds(gc.getBounds());
        } else {
            frame.setBounds(windowedBounds != null ? windowedBounds : new Rectangle(80, 60, 1680, 760));
            if (settings.winMax) frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        if (visible) {
            frame.setVisible(true);
            if (panel != null) panel.requestFocusInWindow();
        }
    }

    private void setPanel(ViewerPanel p) {
        ViewerPanel old = panel;
        panel = p;
        if (frame != null) {
            frame.setContentPane(p);
            frame.revalidate();
            frame.repaint();
            p.requestFocusInWindow();
        }
        if (old != null) old.dispose();
    }

    /** Diagnostics: write what the window shows right now to {@code png} and a few facts about it next to it. */
    void windowReport(File png) {
        ViewerPanel p = panel;
        if (frame == null || p == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("title=").append(frame.getTitle()).append('\n');
        sb.append("showing=").append(frame.isShowing()).append('\n');
        sb.append("bounds=").append(frame.getBounds()).append('\n');
        sb.append("panel=").append(p.getWidth()).append('x').append(p.getHeight()).append(" focus=").append(p.isFocusOwner()).append('\n');
        sb.append("mode=").append(session != null ? "game" : "menu").append('\n');
        sb.append("slots=").append(menu != null ? menu.slots.size() : -1).append('\n');
        sb.append("fps=").append(Math.round(p.fps())).append('\n');
        try {
            if (p.getWidth() > 0 && p.getHeight() > 0) {
                BufferedImage img = new BufferedImage(p.getWidth(), p.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                p.paint(g);
                g.dispose();
                if (png.getAbsoluteFile().getParentFile() != null) png.getAbsoluteFile().getParentFile().mkdirs();
                ImageIO.write(img, "png", png);
            }
            java.nio.file.Files.writeString(new File(png.getPath() + ".txt").toPath(), sb.toString());
        } catch (Exception ex) {
            System.err.println("[viewer] window report failed: " + ex);
        }
        System.out.print("[viewer] window report:\n" + sb);
    }

    /** Save the frame as a PNG in {@code screenshots/} (what is on screen, menus included). */
    void screenshot() {
        ViewerPanel p = panel;
        if (p == null || p.getWidth() <= 0 || p.getHeight() <= 0) return;
        double sc = frame != null ? frame.getGraphicsConfiguration().getDefaultTransform().getScaleX() : 1;
        BufferedImage img = new BufferedImage((int) Math.ceil(p.getWidth() * sc), (int) Math.ceil(p.getHeight() * sc), BufferedImage.TYPE_INT_RGB);
        String oldToast = toast;
        toast = null;                                    // not in the picture
        Graphics2D g = img.createGraphics();
        g.scale(sc, sc);
        p.paint(g);
        g.dispose();
        toast = oldToast;
        String name = "precipitation-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png";
        File out = new File(new File(root, "screenshots"), name);
        io.execute(() -> {
            String msg;
            try {
                out.getParentFile().mkdirs();
                File f = out;
                for (int k = 2; f.exists(); k++) f = new File(out.getParentFile(), name.replace(".png", "-" + k + ".png"));
                ImageIO.write(img, "png", f);
                msg = "Screenshot saved: screenshots/" + f.getName();
            } catch (Exception ex) {
                msg = "Screenshot failed: " + ex.getMessage();
            }
            final String m = msg;
            SwingUtilities.invokeLater(() -> toast(m));
        });
    }

    // =====================================================================================
    // settings and background

    private void markDirty() {
        if (!settingsDirty) settingsDirtySince = System.nanoTime();
        settingsDirty = true;
    }

    /** A setting changed: apply it to what is on screen now and write the file (shortly). */
    void settingsChanged() {
        applySettings();
        if (ticker != null) markDirty();
        else settings.save();
    }

    void applySettings() {
        CloudPainter.quality = settings.cloudQuality;
        // the Advanced tab can only lower the render scale: the Graphics value stays the upper limit
        ViewerPanel.maxRenderScale = settings.adv.capScale(Settings.SCALE_VALUES[settings.renderScale]);
        Renderer.epilepsySafe = settings.epilepsySafe;       // every view: sandbox, lesson modes, the menu's backdrop
        Advanced.publish(settings.adv);                      // (likewise; the renderers pick it up at their next frame)
        sound.apply(settings);                               // volumes and the mute
        ViewerPanel p = panel;
        if (p == null) return;
        if (session != null) {
            if (!session.mode.equals("sandbox")) return;     // the lesson modes style their own view
            styleGame(p.renderer());
            p.setBindings(KeyMap.table(settings.keys));
        } else {
            styleMenu(p.renderer());
        }
    }

    private void styleBackdrop(Renderer r) {
        r.backdropImage = backdrop;
        r.backdropFit = settings.bgFit;
        r.backdropDim = settings.bgDim;
        r.backdropTint = settings.bgTint && !settings.adv.noTint;    // Advanced "No tint" wins; the Background tab keeps its own value
        r.showErrorBox = false;
    }

    /** The decorative world behind the menu: no HUD at all. */
    void styleMenu(Renderer r) {
        styleBackdrop(r);
        r.showRightPanel = false; r.showLeftPanel = false; r.showMinimap = false;
        r.showLabels = false; r.showArrows = false; r.showWind = false; r.showHelp = false;
        r.decorative = true;
        r.animatePanels = false;
    }

    void styleGame(Renderer r) {
        styleBackdrop(r);
        r.showLeftPanel = settings.panelLeft;
        r.showRightPanel = settings.panelRight;
        r.showMinimap = settings.panelMinimap;
        r.showClimate = settings.panelClimate;
        r.helpRows = KeyMap.helpRows(settings.keys);
        r.hudReserveW = 236;
        r.shadeOn = settings.cloudShade;
        r.shadeStrength = settings.cloudShadeStrength;
    }

    /** The menu's camera: mostly sky, the land along the bottom edge. */
    static void menuCamera(Camera cam) {
        cam.set(cam.worldW * 0.5, 0, Math.min(Camera.MAX_ZOOM, cam.fillZoom() * 1.28), true);
    }

    BufferedImage backdrop() { return backdrop; }

    String backdropInfo() { return backdrop == null ? "" : backdropW + " × " + backdropH + " px"; }

    String backdropError() { return backdropError; }

    boolean backdropBusy() { return backdropBusy; }

    /** Load the background of the previous session (startup; blocks while decoding when {@code wait}). */
    void loadStoredBackdrop(boolean wait) {
        File f = settings.backgroundFile();
        if (f == null) return;
        Runnable work = () -> {
            BufferedImage src = null;
            int w = 0, h = 0;
            try {
                BufferedImage full = Backdrops.decode(f);
                w = full.getWidth(); h = full.getHeight();
                src = Backdrops.prescale(full);
            } catch (Exception | OutOfMemoryError ex) {
                System.err.println("[viewer] background " + f + " could not be loaded: " + ex.getMessage());
            }
            final BufferedImage img = src;
            final int fw = w, fh = h;
            Runnable apply = () -> {
                if (img == null) {
                    toast("The background picture \"" + settings.bgFile + "\" could not be loaded — showing the default sky.");
                    return;
                }
                backdrop = img; backdropW = fw; backdropH = fh;
                applySettings();
            };
            if (wait) apply.run(); else SwingUtilities.invokeLater(apply);
        };
        if (wait) work.run(); else io.execute(work);
    }

    /** "Choose image..." — a file chooser starting in Pictures, with a preview. */
    void chooseBackgroundDialog() {
        if (frame == null || backdropBusy) return;
        File pics = new File(System.getProperty("user.home", "."), "Pictures");
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser(pics.isDirectory() ? pics : new File(System.getProperty("user.home", ".")));
        fc.setDialogTitle("Choose a background picture");
        String[] ext = Backdrops.readableExtensions();
        if (ext.length > 0) fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Pictures (" + String.join(", ", ext) + ")", ext));
        fc.setAcceptAllFileFilterUsed(true);
        javax.swing.JLabel preview = new javax.swing.JLabel("", javax.swing.SwingConstants.CENTER);
        preview.setPreferredSize(new java.awt.Dimension(240, 180));
        preview.setBorder(javax.swing.BorderFactory.createTitledBorder("Preview"));
        fc.setAccessory(preview);
        fc.addPropertyChangeListener(javax.swing.JFileChooser.SELECTED_FILE_CHANGED_PROPERTY, ev -> {
            File sel = fc.getSelectedFile();
            preview.setIcon(null);
            preview.setText("");
            if (sel == null || !sel.isFile()) return;
            io.execute(() -> {                                   // decode off the event thread
                javax.swing.Icon icon = null;
                String text = "";
                try {
                    BufferedImage img = Backdrops.decode(sel);
                    double k = Math.min(220.0 / img.getWidth(), 140.0 / img.getHeight());
                    int pw = Math.max(1, (int) (img.getWidth() * k)), ph = Math.max(1, (int) (img.getHeight() * k));
                    BufferedImage small = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = small.createGraphics();
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(img, 0, 0, pw, ph, null);
                    g.dispose();
                    icon = new javax.swing.ImageIcon(small);
                } catch (Exception | OutOfMemoryError ex) {
                    text = "not a picture";
                }
                final javax.swing.Icon ic = icon;
                final String tx = text;
                SwingUtilities.invokeLater(() -> {
                    if (sel.equals(fc.getSelectedFile())) { preview.setIcon(ic); preview.setText(tx); }
                });
            });
        });
        if (fc.showOpenDialog(frame) == javax.swing.JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) chooseBackground(fc.getSelectedFile());
        if (panel != null) panel.requestFocusInWindow();
    }

    /** Use this file as the background (chooser and drag-and-drop). Decoding happens off the event thread. */
    void chooseBackground(File f) {
        if (backdropBusy) return;
        backdropBusy = true;
        io.execute(() -> {
            Backdrops.Result r = Backdrops.choose(f, settings.backgroundsDir());
            SwingUtilities.invokeLater(() -> applyBackdrop(r));
        });
    }

    /** The same as {@link #chooseBackground} but finished when it returns (command line, tests). Returns the message shown. */
    String chooseBackgroundNow(File f) {
        applyBackdrop(Backdrops.choose(f, settings.backgroundsDir()));
        return backdropError != null ? backdropError : "Background set to \"" + settings.bgFile + "\" (" + backdropInfo() + ")";
    }

    private void applyBackdrop(Backdrops.Result r) {
        backdropBusy = false;
        if (r.error != null) {
            backdropError = r.error;                // the previous picture and setting stay as they were
            toast(r.error);
            return;
        }
        backdropError = null;
        backdrop = r.image;
        backdropW = r.sourceW; backdropH = r.sourceH;
        settings.bgFile = r.storedName;
        settingsChanged();
        toast("Background set to \"" + r.storedName + "\"");
    }

    /** Show a background problem the way a failed choice does (message on the settings page and a toast). */
    void reportBackdropError(String message) {
        backdropError = message;
        toast(message);
    }

    void clearBackground() {
        backdrop = null;
        backdropError = null;
        settings.bgFile = "";
        settingsChanged();
        toast("Back to the default sky");
    }

    // =====================================================================================
    // menu <-> game

    void reloadSlots(String selectId) {
        if (menu == null) return;
        List<SaveStore.Slot> list = slotsOverride != null ? slotsOverride : store.list();
        menu.setSlots(list, selectId != null ? selectId : settings.lastSlot);
        if (slotsOverride == null) io.execute(() -> SaveStore.loadThumbs(list));
    }

    /** Show the main menu (creates its decorative world). */
    void showMenu(String selectId) {
        stack.clear();
        session = null;
        modeScreen = null;
        sound.session(null);                                  // the menu's decorative sky is silent (its panel gets no frame hook)
        FakeSim sim = new FakeSim(ThreadLocalRandom.current().nextInt(1, 1_000_000), 0.40, 3200, 1080);
        ViewerState st = new ViewerState();
        ViewerPanel p = new ViewerPanel(st, sim, null);
        p.setBindings(new HashMap<>());           // no game keys in the menu
        styleMenu(p.renderer());
        p.cameraDirector = AppShell::menuCamera;
        p.overlay = this;
        menuSim = sim;
        sim.start(st);
        menu = new MainMenuScreen(this);
        stack.add(menu);
        reloadSlots(selectId);
        setPanel(p);
        if (settings.loadWarning != null) {
            toast(settings.loadWarning);
            settings.loadWarning = null;
        }
    }

    private void closeMenu() {
        if (menuSim != null) menuSim.stop();
        menuSim = null;
        menu = null;
        stack.clear();
    }

    /** Start a brand-new simulation in a new slot. */
    void startNew(NewSimScreen.Params p) {
        SaveStore.Slot slot = store.create(p.name);
        long seed = p.seed != null ? p.seed : ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
        slot.seed = seed;
        slot.worldW = p.width();
        slot.worldH = p.height();
        slot.terrain = p.preset.isEmpty() ? p.terrain : "preset";
        slot.settlements = p.settlements;
        slot.presetId = p.preset;
        slot.presetName = Preset.nameOf(p.preset);
        List<String> extra = new ArrayList<>(List.of("--seed", String.valueOf(seed), "--terrain", p.terrain, "--settlements", p.settlements));
        if (!p.preset.isEmpty()) { extra.add("--preset"); extra.add(p.preset); }     // a real region: the simulation ignores --terrain
        extra.addAll(p.climateArgs());                                               // v2.3: --month / --hour when the player chose them
        slot.startMonth = p.month;
        slot.startHour = p.hour;
        begin(new GameSession(this, slot, new SimProcess(root, p.width(), p.height(), extra), false, slot.name), false);
    }

    /** Continue a saved simulation. */
    void startLoad(SaveStore.Slot slot) {
        if (!slot.playable()) { toast(slot.problem); return; }
        List<String> extra = new ArrayList<>(List.of("--load", slot.stateFile().getAbsolutePath()));
        int w = slot.worldW > 0 ? slot.worldW : 3840, h = slot.worldH > 0 ? slot.worldH : 1080;
        GameSession gs = new GameSession(this, slot, new SimProcess(root, w, h, extra), true, slot.name);
        gs.state.importHistory(store.readHistory(slot));
        begin(gs, true);
    }

    // ---- v2.2 lesson modes: their own simulation process, no slot, no autosave; Esc leaves like any other game

    /** "Hurricane Formation": the guided tour, then the stage explorer. */
    HurricaneScreen startHurricane() {
        FrameSource src = fakeModes ? new FakeHurricane() : new SimProcess(root, 1920, 1080, new ArrayList<>(List.of("--mode", "hurricane")));
        HurricaneScreen hs = (HurricaneScreen) beginMode("hurricane", src, "Hurricane Formation", null);
        hs.restore(settings);                                 // the chapter / stage / view of last time
        return hs;
    }

    /** The cloud type of the Cloud Formations tab shown last; the first type when none (or one this version does not know) is remembered. */
    String rememberedCloudType() {
        for (String t : Model.CLOUD_TYPES) if (t.equals(settings.showcaseType)) return t;
        return Model.CLOUD_TYPES[0];
    }

    /**
     * "Cloud Formations": one cloud type at a time, starting with {@code type}; null or empty = the tab shown last.
     * A type given here (the menu's choice, {@code --cloud-type}) wins and is not written to the settings; a tab the
     * player picks inside the mode is.
     */
    ShowcaseScreen startShowcase(String type) {
        String t = type == null || type.isEmpty() ? rememberedCloudType() : type;
        FrameSource src = fakeModes ? FakeSim.showcase(ThreadLocalRandom.current().nextInt(1, 1_000_000), 0.40, t)
                : new SimProcess(root, ShowcaseScreen.STAGE_W, ShowcaseScreen.STAGE_H, new ArrayList<>(List.of("--mode", "showcase", "--cloud-type", t)));
        ShowcaseScreen ss = (ShowcaseScreen) beginMode("showcase", src, "Cloud Formations", t);
        ss.remember = true;
        return ss;
    }

    /** v2.3 "World Simulation": the whole Earth; a click on a location opens a sandbox world of it ({@link #openWorldLocation}). */
    WorldScreen startWorld() {
        FrameSource src = fakeModes ? new FakeWorld(root) : new SimProcess(root, 1920, 1080, new ArrayList<>(List.of("--mode", "world")));
        return (WorldScreen) beginMode("world", src, "World Simulation", null);
    }

    // ---- the hidden Debug mode (PLAN Phase I): viewer only, no simulation process, no slot

    static final int DEBUG_CLICKS = 10;

    /**
     * A click on the cloud in the menu's upper left. Clicks 1-4 show nothing, 5-9 say how many are left, the tenth
     * unlocks the Debug game mode for good ({@code modes.debugUnlocked} in settings.json) and selects its row.
     */
    void debugCloudClicked() {
        if (settings.debugUnlocked) { menuMode = MainMenuScreen.DEBUG; return; }
        debugClicks++;
        if (debugClicks >= DEBUG_CLICKS) {
            settings.debugUnlocked = true;
            menuMode = MainMenuScreen.DEBUG;
            settingsChanged();
            toast("Debug mode unlocked — it is the fifth game mode now.");
        } else if (debugClicks >= 5) {
            toast((DEBUG_CLICKS - debugClicks) + " more…");
        }
    }

    /** "Debug": every cloud type, seed, building, car and effect on the game's own painters. No process, never saved; Esc leaves like a lesson mode. */
    DebugScreen startDebug() {
        return (DebugScreen) beginMode("debug", new DebugScreen.NoSource(), "Debug", null);
    }

    /** The simulation arguments of a sandbox opened from the world map: that preset, in the season and at the local hour the map showed. */
    static List<String> worldLaunchArgs(long seed, String terrain, String settlements, String presetId, double month, double localHour) {
        double m = Model.clamp(Double.isNaN(month) ? 4 : month, 1, 12.99), h = Double.isNaN(localHour) ? 12 : ((localHour % 24) + 24) % 24;
        return new ArrayList<>(List.of("--seed", String.valueOf(seed), "--terrain", terrain, "--settlements", settlements, "--preset", presetId,
                "--month", String.format(java.util.Locale.ROOT, "%.2f", m), "--hour", String.format(java.util.Locale.ROOT, "%.2f", Math.min(23.99, h))));
    }

    /**
     * "Open this location" on the world map: leave the world simulation and start a <b>new sandbox slot</b>
     * ("Oklahoma — May") of that preset. Leaving that sandbox returns to the main menu like any other world.
     * With {@link #fakeModes} the sandbox is the built-in fake too (a diagnostic session, never saved).
     */
    void openWorldLocation(String presetId, String presetName, double month, double localHour) {
        if (session != null && session.mode.equals("debug")) {
            // the Debug mode shows the map only: it never creates a save slot or starts the simulation
            toast("Debug only shows the map. Open World Simulation from the main menu to visit " + presetName + ".");
            return;
        }
        GameSession old = session;
        long seed = ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
        String name = presetName + " — " + WorldModel.monthName(Double.isNaN(month) ? 4 : month);
        List<String> extra = worldLaunchArgs(seed, settings.defaultTerrain, settings.defaultSettlements, presetId, month, localHour);
        lastWorldLaunch = new ArrayList<>(extra);
        modeScreen = null;
        menuMode = 0;                                         // back in the menu, the new world is in the Sandbox list
        if (fakeModes) {
            double h = Double.isNaN(localHour) ? 12 : ((localHour % 24) + 24) % 24;
            startDiagnostic(new FakeSim(seed, h / 24.0, 3840, 1080, null, presetId), name);
        } else {
            SaveStore.Slot slot = store.create(store.uniqueName(name));
            int si = Math.max(0, Math.min(Settings.SIZES.length - 1, settings.defaultSize));
            slot.seed = seed;
            slot.worldW = Settings.SIZES[si][0];
            slot.worldH = Settings.SIZES[si][1];
            slot.terrain = "preset";
            slot.settlements = settings.defaultSettlements;
            slot.presetId = presetId;
            slot.presetName = presetName;
            begin(new GameSession(this, slot, new SimProcess(root, slot.worldW, slot.worldH, extra), false, slot.name), false);
        }
        if (old != null) stopInBackground(old);
        toast("Opened " + presetName + " — " + WorldModel.monthText(Double.isNaN(month) ? 4 : month) + ", " + WorldModel.clock(localHour) + " local time");
    }

    /** Open a lesson mode on any source (the command line and the tests bring their own). */
    Screen beginMode(String mode, FrameSource src, String label, String cloudType) {
        closeMenu();
        GameSession gs = new GameSession(this, null, src, false, label);
        gs.mode = mode;
        session = gs;
        menuMode = mode.equals("hurricane") ? 1 : mode.equals("world") ? 3 : mode.equals("debug") ? MainMenuScreen.DEBUG : 2;
        ViewerPanel p = new ViewerPanel(gs.state, gs.source, null);
        gs.panel = p;
        p.setBindings(new HashMap<>());           // the mode's screen handles every key itself
        p.overlay = this;
        Renderer r = p.renderer();
        r.showErrorBox = false;
        Screen s;
        if (mode.equals("hurricane")) {
            HurricaneScreen hs = new HurricaneScreen(this, gs);
            p.scene = hs;
            s = hs;
        } else if (mode.equals("world")) {
            WorldScreen ws = new WorldScreen(this, gs);
            p.scene = ws;
            s = ws;
        } else if (mode.equals("debug")) {
            DebugScreen ds = new DebugScreen(this, gs);
            p.scene = ds;
            s = ds;
        } else {
            ShowcaseScreen ss = new ShowcaseScreen(this, gs, cloudType);
            ShowcaseScreen.style(r);
            p.layoutHook = (w, h) -> ss.applyLayout(r, w, h);
            p.cameraDirector = ss::camera;
            s = ss;
        }
        modeScreen = s;
        stack.add(s);
        // sound in Cloud Formations and Hurricane Formation; the world map and the Debug mode are silent (PLAN Phase P, question 118)
        boolean heard = mode.equals("hurricane") || mode.equals("showcase");
        if (heard) p.frameHook = this::soundFrame;
        sound.session(heard ? gs : null);
        gs.source.start(gs.state);
        setPanel(p);
        return s;
    }

    /** A game on a source that is not a saved simulation (--fake, --replay): playable, never saved. */
    void startDiagnostic(FrameSource source, String label) {
        begin(new GameSession(this, null, source, false, label), false);
    }

    private void begin(GameSession gs, boolean loaded) {
        closeMenu();
        session = gs;
        ViewerPanel p = new ViewerPanel(gs.state, gs.source, this::openPause);
        gs.panel = p;
        Renderer r = p.renderer();
        styleGame(r);
        r.animatePanels = true;
        r.snapPanels();
        p.setBindings(KeyMap.table(settings.keys));
        p.overlay = this;
        p.actionListener = this::onAction;
        p.afterAction = this::afterAction;
        p.frameHook = this::soundFrame;
        sound.session(gs);
        SaveStore.Slot slot = gs.slot;
        if (loaded && slot != null) {
            r.showWind = slot.viewWind; r.showArrows = slot.viewArrows; r.showLabels = slot.viewLabels; r.showLegend = slot.viewLegend;
            if (slot.hasCamera) p.restoreCamera(slot.camX, slot.camY, slot.camZoom);
        }
        gs.source.start(gs.state);
        // the state file remembers the pause flag; a save made from the pause menu must not come back frozen
        if (loaded && slot != null) gs.source.send(slot.userPaused ? "{\"cmd\":\"pause\"}" : "{\"cmd\":\"resume\"}");
        if (slot != null) {
            settings.lastSlot = slot.id;
            markDirty();
        }
        setPanel(p);
    }

    // ---- sound (Phase P)

    /**
     * True while the game must not be heard: there is none, its simulation has died, or something other than the lesson
     * mode's own screen is in front of it (the pause menu, Settings, a question, "Saving").
     */
    boolean soundBlocked() {
        GameSession gs = session;
        Screen t = top();
        return gs == null || gs.simError() != null || (t != null && t != modeScreen);
    }

    /** The frame hook of a game's panel: what the frame just painted should sound like. Allocates nothing. */
    private void soundFrame(ViewerPanel p, double dt) {
        if (p != panel) return;                               // a panel that is on its way out
        sound.blocked(soundBlocked());
        sound.frame(p, dt);
    }

    // ---- actions of a running game

    /** Sees every game action first (see {@code ViewerPanel.actionListener}); true = handled here. */
    private boolean onAction(Action a) {
        switch (a) {
            case QUICK_SAVE -> { saveNow("Saved"); return true; }
            case FULLSCREEN -> { toggleFullscreen(); return true; }
            case SCREENSHOT -> { screenshot(); return true; }
            case RESEED -> {
                if (reseedConfirmed || session == null || session.slot == null) return false;
                push(new Screen.Confirm(this, "Reseed this world?",
                        "\"" + session.slot.name + "\" would be replaced by a brand-new world, and the next save overwrites the saved one.",
                        "Reseed", "Cancel", true, () -> {
                    reseedConfirmed = true;
                    try { if (panel != null) panel.perform(Action.RESEED); } finally { reseedConfirmed = false; }
                }, null));
                return true;
            }
            default -> { return false; }
        }
    }

    /** The side panels are remembered between sessions. */
    private void afterAction(Action a) {
        if (panel == null || session == null) return;
        switch (a) {
            case TOGGLE_LEFT_PANEL, TOGGLE_RIGHT_PANEL, TOGGLE_MINIMAP, TOGGLE_ALL_PANELS, TOGGLE_CLIMATE -> {
                Renderer r = panel.renderer();
                settings.panelLeft = r.showLeftPanel;
                settings.panelRight = r.showRightPanel;
                settings.panelMinimap = r.showMinimap;
                settings.panelClimate = r.showClimate;
                markDirty();
            }
            default -> { }
        }
    }

    /** Esc in a game: pause the simulation and open the pause menu. */
    void openPause() {
        GameSession gs = session;
        if (gs == null || !stack.isEmpty()) return;
        Model.Frame f;
        synchronized (gs.state) { f = gs.state.cur; }
        gs.pausedBeforeMenu = f != null && f.paused;
        gs.pauseMenuOpen = true;
        gs.source.send("{\"cmd\":\"pause\"}");
        if (panel != null) panel.renderer().showHelp = false;
        push(new PauseScreen(this, gs));
    }

    void resumeFromPause() {
        stack.clear();
        GameSession gs = session;
        if (gs == null) return;
        if (gs.pauseMenuOpen && !gs.pausedBeforeMenu) gs.source.send("{\"cmd\":\"resume\"}");
        gs.pauseMenuOpen = false;
    }

    /** Save (button or quick-save key) and say how it went. */
    void saveNow(String verb) {
        GameSession gs = session;
        if (gs == null) return;
        if (gs.slot == null) { toast("This is a diagnostic session — it cannot be saved."); return; }
        gs.requestSave(verb, err -> toast(err == null ? "Simulation saved" : "Save failed: " + err + (gs.everSaved() ? "  Your previous save is intact." : "")));
    }

    /** Save, then go back to the main menu. A failed save keeps the player in the game unless they insist. */
    void saveAndLeave() {
        GameSession gs = session;
        if (gs == null) return;
        if (!gs.canSave()) { leaveNow(); return; }
        Screen busy = new Screen.Busy("Saving");
        push(busy);
        gs.requestSave("Saved", err -> {
            pop(busy);
            if (session != gs) return;
            if (err == null) leaveNow();
            else push(new Screen.Message(this, "The simulation could not be saved",
                    err + "\n\n" + (gs.everSaved() ? "Your previous save is intact." : "Nothing has been saved yet.") + " Leave anyway?", true,
                    new String[]{"Leave without saving", "Stay"}, new Runnable[]{this::leaveNow, null}));
        });
    }

    void confirmLeaveWithoutSaving() {
        GameSession gs = session;
        if (gs == null) return;
        push(new Screen.Confirm(this, "Leave without saving?",
                gs.everSaved() ? "Everything since the last save (" + gs.saveStatus() + ") will be lost." : "This simulation has not been saved yet and will be lost.",
                "Leave without saving", "Stay", true, this::leaveNow, null));
    }

    /** Back to the main menu right now; the simulation process is stopped in the background. */
    void leaveNow() {
        GameSession gs = session;
        if (gs == null) return;
        showMenu(gs.slot != null ? gs.slot.id : null);       // also disposes the game panel
        stopInBackground(gs);
    }

    private void stopInBackground(GameSession gs) {
        Thread t = new Thread(gs::stop, "sim-stop");
        t.setDaemon(false);
        synchronized (stoppers) { stoppers.add(t); }
        t.start();
    }

    /** Wait until every simulation process that was told to stop is really gone. */
    void awaitStops() {
        List<Thread> ts;
        synchronized (stoppers) { ts = new ArrayList<>(stoppers); }
        for (Thread t : ts) {
            try { t.join(4000); } catch (InterruptedException ignored) { }
        }
    }

    /** Window close button, "Quit": save the running game first, then exit. */
    void requestQuit() {
        if (quitting) return;
        GameSession gs = session;
        if (gs != null && gs.canSave()) {
            if (has(Screen.Busy.class)) return;              // a save-and-something is already under way
            Screen busy = new Screen.Busy("Saving");
            push(busy);
            gs.requestSave("Saved", err -> {
                pop(busy);
                if (err == null) exitNow();
                else push(new Screen.Message(this, "The simulation could not be saved",
                        err + "\n\n" + (gs.everSaved() ? "Your previous save is intact." : "Nothing has been saved yet.") + " Quit anyway?", true,
                        new String[]{"Quit without saving", "Stay"}, new Runnable[]{this::exitNow, null}));
            });
        } else exitNow();
    }

    /** Close everything and run {@link #onExit}. No simulation process survives this. */
    void exitNow() {
        if (quitting) return;
        quitting = true;
        sound.close();                                        // first: fade out, so no note hangs while the simulation is stopped
        if (ticker != null) ticker.stop();
        settings.save();
        if (frame != null) frame.setVisible(false);
        GameSession gs = session;
        FrameSource ms = menuSim;
        ViewerPanel p = panel;
        Thread t = new Thread(() -> {
            if (gs != null) gs.stop();
            if (ms != null) ms.stop();
            awaitStops();
            SwingUtilities.invokeLater(() -> {
                if (p != null) p.dispose();
                if (frame != null) frame.dispose();
                onExit.run();
            });
        }, "shell-exit");
        t.start();
    }

    /** Last resort (JVM shutdown hook): make sure the simulation process is told to stop. */
    void emergencyStop() {
        GameSession gs = session;
        if (gs != null && !gs.stopped()) gs.stop();
    }

    /** A few times a second: autosave and save timeouts, a dead simulation, writing changed settings. */
    void tick() {
        GameSession gs = session;
        if (gs != null) {
            gs.tick();
            String err = gs.simError();
            if (err != null && !gs.errorShown) {
                gs.errorShown = true;
                // a lesson mode keeps its screen (drawn dimmed under the message); a game closes its pause menu
                if (modeScreen == null) stack.clear(); else while (top() != null && top() != modeScreen) stack.remove(stack.size() - 1);
                gs.pauseMenuOpen = false;
                push(simProblem(gs, err));
            }
        }
        if (settingsDirty && System.nanoTime() - settingsDirtySince > 400_000_000L) {
            settingsDirty = false;
            settings.save();
        }
    }

    /** The in-window report of a simulation that failed to start or died, with a way back to the menu. */
    Screen simProblem(GameSession gs, String err) {
        String msg = err.replace("\n\nRestart the viewer, or run it with --fake to preview the visuals.", "")
                .replace("\n\nBuild the Python half first, or run the viewer with --fake.", "")
                .replace("\n\nInstall Python 3 or run with --fake.", "\n\nInstall Python 3 (python.org) and make sure \"python\" works in a terminal.");
        // a save the simulation refused to read is not "intact": say what is true (the simulation prints "cannot load state")
        if (gs.slot != null && gs.everSaved() && err.contains("cannot load state"))
            msg += "\n\n\"" + gs.slot.name + "\" could not be opened: its saved state is damaged or from another version.";
        else if (gs.slot != null) msg += "\n\n" + (gs.everSaved() ? "Your last save of \"" + gs.slot.name + "\" is intact." : "This simulation had not been saved yet.");
        return new Screen.Message(this, "Simulation problem", msg, true,
                new String[]{"Quit", "Back to the main menu"}, new Runnable[]{this::exitNow, this::leaveNow});
    }

    // =====================================================================================
    // ViewerPanel.Overlay

    @Override public void paint(Graphics2D g, int w, int h) { paintOverlay(g, w, h, panel != null ? panel.renderer() : null); }

    /** Draw the HUD chrome (in a game) and every open screen. {@code r} is the renderer of the frame underneath. */
    void paintOverlay(Graphics2D g, int w, int h, Renderer r) {
        ui.begin(g);
        if (session != null && session.mode.equals("sandbox")) {
            ui.interactive = stack.isEmpty();
            hud.paint(ui, w, h, r, session);
        }
        boolean dimmed = false;
        for (int k = 0; k < stack.size(); k++) {
            Screen s = stack.get(k);
            ui.interactive = k == stack.size() - 1;
            if (s.dims()) {
                ui.dim(w, h, dimmed ? 70 : 120);
                dimmed = true;
            }
            s.paint(ui, w, h);
        }
        ui.interactive = true;
        if (toast != null && System.nanoTime() < toastUntil) paintToast(w);
    }

    private void paintToast(int w) {
        double maxW = Math.min(w - 40, 760);
        List<String> lines = ui.wrap(toast, Ui.F_BOLD, maxW - 32);
        double tw = 0;
        for (String l : lines) tw = Math.max(tw, ui.width(l, Ui.F_BOLD));
        double bw = tw + 32, bh = 14 + lines.size() * 17, x = (w - bw) / 2, y = 18;
        ui.fill(x, y, bw, bh, 12, new Color(10, 14, 24, 235));
        ui.stroke(x, y, bw, bh, 12, new Color(255, 226, 140, 150), 1f);
        double ty = y + 20;
        for (String l : lines) { ui.textCenter(l, w / 2.0, ty, Ui.F_BOLD, Color.WHITE); ty += 17; }
    }

    @Override public boolean mouse(int type, int x, int y, int button) {
        switch (type) {
            case MouseEvent.MOUSE_PRESSED -> {
                Screen ts = top();
                if (button != MouseEvent.BUTTON1) { if (ts != null) ts.mouse(type, x, y, button); return !stack.isEmpty(); }
                boolean hit = ui.press(x, y);
                if (!hit && ts != null) ts.mouse(type, x, y, button);      // not on a widget: the screen's own surface (the world map)
                return hit || !stack.isEmpty();
            }
            case MouseEvent.MOUSE_DRAGGED -> { ui.drag(x, y); Screen ts = top(); if (ts != null) ts.mouse(type, x, y, 0); }
            case MouseEvent.MOUSE_RELEASED -> {
                if (button == MouseEvent.BUTTON1) ui.release(x, y);
                Screen ts = top();
                if (ts != null) ts.mouse(type, x, y, button);
            }
            case MouseEvent.MOUSE_EXITED -> ui.move(-1, -1);
            default -> ui.move(x, y);
        }
        return false;
    }

    @Override public boolean wheel(int x, int y, double rotation) {
        Screen s = top();
        if (s == null) return false;
        s.wheel(x, y, rotation);
        return true;
    }

    @Override public boolean key(KeyEvent e) {
        Screen s = top();
        if (s == null) return false;                          // a running game: the bindings handle it
        if (!s.capturesKeys()) {
            Action a = KeyMap.owner(settings.keys, e.getKeyCode());
            if (a == Action.FULLSCREEN) { toggleFullscreen(); return true; }
            if (a == Action.SCREENSHOT) { screenshot(); return true; }
        }
        keyScreen = s;
        s.key(e);
        return true;
    }

    /** The screen that received the last key press: a character is only typed into that same screen. */
    private Screen keyScreen;

    @Override public boolean typed(char c) {
        Screen s = top();
        if (s == null) return false;
        // the key that opened a dialog (N, F2 ...) must not also be typed into its text field
        if (s == keyScreen) s.typed(c);
        return true;
    }
}
