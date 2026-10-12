package precip;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The save slots: one directory per simulation under {@code saves/}.
 *
 * <pre>
 *   saves/&lt;slot-id&gt;/meta.json      what the menu shows (written by the viewer, last, so it marks a complete save)
 *                    state.sav      the simulation itself (written by Python through the save command)
 *                    state.sav.bak  the state before the latest save
 *                    history.json   the viewer's cloud lifetimes and event log
 *                    thumb.png      a small picture of the world at save time
 * </pre>
 * Every file is written to a temporary name and moved into place. Nothing here ever touches a
 * path outside {@code saves/}: {@link #delete} resolves the directory and refuses anything that is
 * not a direct child of the saves folder.
 */
final class SaveStore {
    /** Version of meta.json / history.json this viewer writes and can read. */
    static final int FORMAT = 1;
    static final String META = "meta.json", STATE = "state.sav", STATE_NEW = "state.sav.new", STATE_BAK = "state.sav.bak",
            HISTORY = "history.json", THUMB = "thumb.png";

    final File root;

    SaveStore(File savesDir) { this.root = savesDir; }

    /** One simulation as the menu knows it. */
    static final class Slot {
        String id = "", name = "";
        File dir;
        long created, lastPlayed;
        long seed;
        int worldW, worldH;
        String terrain = "", settlements = "";
        /** v2.2: the region preset the world was built from ("" = an ordinary world), and its display name. */
        String presetId = "", presetName = "";
        /** v2.3: the month (1..12.99) and local hour (0..24) the world was started in (NaN = left to the simulation), and the calendar month at the last save (NaN = unknown). */
        double startMonth = Double.NaN, startHour = Double.NaN, month = Double.NaN;
        double simT;
        long frame;
        double playSeconds;
        int alive, born, dead, settlementCount;
        double rainMm, snowMm, timeOfDay = Double.NaN;
        int format = FORMAT, protocol = Model.PROTOCOL_VERSION;
        // the view at save time
        boolean hasCamera;
        double camX, camY, camZoom;
        boolean viewWind = true, viewArrows = true, viewLabels = true, viewLegend = true;
        /** The player had the simulation paused (the state file stores the pause flag too). */
        boolean userPaused;
        /** Why the slot cannot be played (shown on its card); null when it is fine. */
        String problem;
        /** True when the slot only works because state.sav.bak stood in for a missing state.sav. */
        boolean usingBackup;
        volatile BufferedImage thumb;

        boolean playable() { return problem == null; }

        File file(String name) { return new File(dir, name); }

        /** The state file to load: state.sav, or the backup when that is gone. */
        File stateFile() {
            File f = file(STATE);
            return f.isFile() || !file(STATE_BAK).isFile() ? f : file(STATE_BAK);
        }
    }

    // ------------------------------------------------------------------ listing

    /** Every slot directory, most recently played first. Damaged slots are included (with {@code problem} set). */
    List<Slot> list() {
        List<Slot> out = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) for (File d : dirs) out.add(read(d));
        out.sort((a, b) -> a.lastPlayed != b.lastPlayed ? Long.compare(b.lastPlayed, a.lastPlayed) : a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Find a slot by id, else by name (case-insensitive). */
    Slot find(String idOrName) {
        List<Slot> all = list();
        for (Slot s : all) if (s.id.equals(idOrName)) return s;
        for (Slot s : all) if (s.name.equalsIgnoreCase(idOrName)) return s;
        return null;
    }

    /** Read one slot directory. Never throws: whatever is wrong ends up in {@code problem}. */
    Slot read(File dir) {
        Slot s = new Slot();
        s.dir = dir;
        s.id = dir.getName();
        s.name = dir.getName();
        s.lastPlayed = dir.lastModified();
        s.created = s.lastPlayed;
        File meta = new File(dir, META);
        if (!meta.isFile()) {
            s.problem = "Damaged — meta.json is missing";
        } else {
            try {
                Map<String, Object> m = Json.obj(Json.parse(Files.readString(meta.toPath(), StandardCharsets.UTF_8)));
                if (m == null) throw new IllegalArgumentException("not a JSON object");
                fromJson(s, m);
                if (s.format > FORMAT) s.problem = "Incompatible — saved by a newer version (format " + s.format + ")";
                else if (s.protocol != Model.PROTOCOL_VERSION) s.problem = "Incompatible — simulation protocol v" + s.protocol;
            } catch (IOException | RuntimeException ex) {
                s.problem = "Damaged — meta.json cannot be read";
            }
        }
        if (s.problem == null) {
            File st = new File(dir, STATE), bak = new File(dir, STATE_BAK);
            boolean ok = st.isFile() && st.length() > 0, bakOk = bak.isFile() && bak.length() > 0;
            if (!ok && bakOk) s.usingBackup = true;
            else if (!ok) s.problem = st.isFile() ? "Damaged — the state file is empty" : "Damaged — the state file is missing";
        }
        return s;
    }

    private static void fromJson(Slot s, Map<String, Object> m) {
        s.name = Json.str(m, "name", s.id);
        s.format = (int) Json.num(m, "format", FORMAT);
        s.protocol = (int) Json.num(m, "protocol", Model.PROTOCOL_VERSION);
        s.created = (long) Json.num(m, "created", s.created);
        s.lastPlayed = (long) Json.num(m, "lastPlayed", s.lastPlayed);
        s.seed = (long) Json.num(m, "seed", 0);
        s.worldW = (int) Json.num(m, "worldW", 0);
        s.worldH = (int) Json.num(m, "worldH", 0);
        s.terrain = Json.str(m, "terrain", "");
        s.settlements = Json.str(m, "settlements", "");
        s.settlementCount = (int) Json.num(m, "settlementCount", 0);
        s.presetId = Json.str(m, "preset", "");                 // absent in slots saved before v2.2
        s.presetName = Json.str(m, "presetName", Preset.nameOf(s.presetId));
        s.startMonth = Json.num(m, "startMonth", Double.NaN);       // absent in slots saved before v2.3
        s.startHour = Json.num(m, "startHour", Double.NaN);
        s.month = Json.num(m, "month", Double.NaN);
        s.simT = Json.num(m, "simT", 0);
        s.frame = (long) Json.num(m, "frame", 0);
        s.playSeconds = Json.num(m, "playSeconds", 0);
        s.timeOfDay = Json.num(m, "timeOfDay", Double.NaN);
        Map<String, Object> c = Json.obj(m, "counts");
        s.alive = (int) Json.num(c, "cloudsAlive", 0);
        s.born = (int) Json.num(c, "cloudsBorn", 0);
        s.dead = (int) Json.num(c, "cloudsDead", 0);
        s.rainMm = Json.num(c, "rainMm", 0);
        s.snowMm = Json.num(c, "snowMm", 0);
        Map<String, Object> cam = Json.obj(m, "camera");
        if (cam != null) {
            s.camX = Json.num(cam, "x", Double.NaN);
            s.camY = Json.num(cam, "y", Double.NaN);
            s.camZoom = Json.num(cam, "zoom", Double.NaN);
            s.hasCamera = !Double.isNaN(s.camX) && !Double.isNaN(s.camY) && s.camZoom > 0;
        }
        Map<String, Object> v = Json.obj(m, "view");
        if (v != null) {
            s.viewWind = Json.bool(v, "wind", true);
            s.viewArrows = Json.bool(v, "arrows", true);
            s.viewLabels = Json.bool(v, "labels", true);
            s.viewLegend = Json.bool(v, "legend", true);
        }
        s.userPaused = Json.bool(m, "paused", false);
    }

    static Map<String, Object> toJson(Slot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", s.format);
        m.put("protocol", s.protocol);
        m.put("id", s.id);
        m.put("name", s.name);
        m.put("created", s.created);
        m.put("createdIso", Instant.ofEpochMilli(s.created).toString());
        m.put("lastPlayed", s.lastPlayed);
        m.put("lastPlayedIso", Instant.ofEpochMilli(s.lastPlayed).toString());
        m.put("seed", s.seed);
        m.put("worldW", s.worldW);
        m.put("worldH", s.worldH);
        m.put("terrain", s.terrain);
        m.put("settlements", s.settlements);
        m.put("settlementCount", s.settlementCount);
        if (!s.presetId.isEmpty()) {
            m.put("preset", s.presetId);
            m.put("presetName", s.presetName);
        }
        if (!Double.isNaN(s.startMonth)) m.put("startMonth", Math.round(s.startMonth * 100) / 100.0);
        if (!Double.isNaN(s.startHour)) m.put("startHour", Math.round(s.startHour * 100) / 100.0);
        if (!Double.isNaN(s.month)) m.put("month", Math.round(s.month * 100) / 100.0);
        m.put("simT", Math.round(s.simT * 100) / 100.0);
        m.put("frame", s.frame);
        m.put("playSeconds", Math.round(s.playSeconds * 10) / 10.0);
        if (!Double.isNaN(s.timeOfDay)) m.put("timeOfDay", Math.round(s.timeOfDay * 1000) / 1000.0);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("cloudsAlive", s.alive);
        c.put("cloudsBorn", s.born);
        c.put("cloudsDead", s.dead);
        c.put("rainMm", Math.round(s.rainMm * 10) / 10.0);
        c.put("snowMm", Math.round(s.snowMm * 10) / 10.0);
        m.put("counts", c);
        if (s.hasCamera) {
            Map<String, Object> cam = new LinkedHashMap<>();
            cam.put("x", Math.round(s.camX * 10) / 10.0);
            cam.put("y", Math.round(s.camY * 10) / 10.0);
            cam.put("zoom", Math.round(s.camZoom * 10000) / 10000.0);
            m.put("camera", cam);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("wind", s.viewWind);
        v.put("arrows", s.viewArrows);
        v.put("labels", s.viewLabels);
        v.put("legend", s.viewLegend);
        m.put("view", v);
        m.put("paused", s.userPaused);
        return m;
    }

    /** Load the thumbnails of these slots (call off the UI thread). */
    static void loadThumbs(List<Slot> slots) {
        for (Slot s : slots) {
            if (s.thumb != null || s.dir == null) continue;
            File f = s.file(THUMB);
            if (!f.isFile()) continue;
            try {
                s.thumb = ImageIO.read(f);
            } catch (IOException | RuntimeException ignored) { }
        }
    }

    // ------------------------------------------------------------------ names and ids

    /** {@code base} if no slot has that name yet, else "base 2", "base 3", ... */
    String uniqueName(String base) {
        List<Slot> all = list();
        for (int n = 1; ; n++) {
            String name = n == 1 ? base : base + " " + n;
            boolean taken = false;
            for (Slot s : all) if (s.name.equalsIgnoreCase(name)) { taken = true; break; }
            if (!taken) return name;
        }
    }

    /** "Simulation N" with the smallest N not in use. */
    String nextDefaultName() {
        List<Slot> all = list();
        for (int n = all.size() + 1; ; n++) {
            String name = "Simulation " + n;
            boolean taken = false;
            for (Slot s : all) if (s.name.equalsIgnoreCase(name)) { taken = true; break; }
            if (!taken) return name;
        }
    }

    /** A new slot (not yet on disk) with a fresh directory name derived from the display name. */
    Slot create(String name) {
        Slot s = new Slot();
        s.name = cleanName(name);
        if (s.name.isEmpty()) s.name = nextDefaultName();
        s.id = newId(s.name);
        s.dir = new File(root, s.id);
        s.created = System.currentTimeMillis();
        s.lastPlayed = s.created;
        return s;
    }

    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").trim();
        return n.length() > 48 ? n.substring(0, 48).trim() : n;
    }

    /** Directory name: lower-case letters, digits and dashes only, unique inside saves/. */
    String newId(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "simulation";
        if (slug.length() > 28) slug = slug.substring(0, 28).replaceAll("-+$", "");
        String stamp = Long.toString(System.currentTimeMillis() / 1000, 36);
        String id = slug + "-" + stamp;
        for (int k = 2; new File(root, id).exists(); k++) id = slug + "-" + stamp + "-" + k;
        return id;
    }

    // ------------------------------------------------------------------ writing

    /** Write bytes to {@code target} through a temp file in the same directory and an atomic move. */
    static void atomicWrite(File target, byte[] data) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        Files.createDirectories(parent.toPath());
        Path tmp = new File(parent, target.getName() + ".tmp").toPath();
        Files.write(tmp, data);
        moveInto(tmp, target.toPath());
    }

    static void moveInto(Path tmp, Path target) throws IOException {
        // On Windows the move is refused ("access denied" / "in use") for a moment while another program - a virus
        // scanner, the search indexer - has the target or the fresh temp file open. Measured here: 1-2 % of writes in
        // a tight loop. Such a refusal passes within milliseconds, so try again a few times before reporting it.
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (java.nio.file.NoSuchFileException ex) {
                throw ex;                                    // nothing to move: waiting does not help
            } catch (java.nio.file.FileSystemException ex) {
                if (attempt >= 5 || !Files.exists(tmp) || Files.isDirectory(target)) throw ex;
                try {
                    Thread.sleep(10L << attempt);            // 10, 20, 40, 80, 160 ms
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    void writeMeta(Slot s) throws IOException {
        atomicWrite(s.file(META), Settings.pretty(toJson(s)).getBytes(StandardCharsets.UTF_8));
    }

    void writeHistory(Slot s, Map<String, Object> history) throws IOException {
        atomicWrite(s.file(HISTORY), Json.write(history).getBytes(StandardCharsets.UTF_8));
    }

    /** The saved history, or null when there is none or it cannot be read (the timeline then starts empty). */
    Map<String, Object> readHistory(Slot s) {
        File f = s.file(HISTORY);
        if (!f.isFile()) return null;
        try {
            return Json.obj(Json.parse(Files.readString(f.toPath(), StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException ex) {
            System.err.println("[viewer] " + f + " is unreadable, starting with an empty timeline: " + ex.getMessage());
            return null;
        }
    }

    void writeThumb(Slot s, BufferedImage img) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64_000);
        ImageIO.write(img, "png", bo);
        atomicWrite(s.file(THUMB), bo.toByteArray());
        s.thumb = img;
    }

    /**
     * Put a state file the simulation has just written as {@code state.sav.new} into place: the
     * current state becomes {@code state.sav.bak}, then the new file replaces {@code state.sav} in
     * one atomic move. {@code state.sav} exists at every instant.
     */
    void promoteState(Slot s) throws IOException {
        Path fresh = s.file(STATE_NEW).toPath(), cur = s.file(STATE).toPath(), bak = s.file(STATE_BAK).toPath();
        if (!Files.isRegularFile(fresh) || Files.size(fresh) == 0) throw new IOException("the simulation reported success but wrote no state file");
        if (Files.isRegularFile(cur)) {
            Path tmp = s.file(STATE_BAK + ".tmp").toPath();
            Files.copy(cur, tmp, StandardCopyOption.REPLACE_EXISTING);
            moveInto(tmp, bak);
        }
        moveInto(fresh, cur);
    }

    void rename(Slot s, String name) throws IOException {
        String n = cleanName(name);
        if (n.isEmpty()) throw new IOException("The name cannot be empty");
        s.name = n;
        writeMeta(s);
    }

    /** Copy a slot into a new directory ("Name copy"); the copy is independent of the original. */
    Slot duplicate(Slot src) throws IOException {
        String base = src.name + " copy", name = base;
        List<Slot> all = list();
        for (int k = 2; ; k++) {
            boolean taken = false;
            for (Slot o : all) if (o.name.equalsIgnoreCase(name)) { taken = true; break; }
            if (!taken) break;
            name = base + " " + k;
        }
        Slot d = create(name);
        Files.createDirectories(d.dir.toPath());
        File[] files = src.dir.listFiles(File::isFile);
        if (files != null) for (File f : files) {
            String fn = f.getName();
            if (fn.endsWith(".tmp") || fn.equals(STATE_NEW) || fn.equals(META)) continue;
            Files.copy(f.toPath(), new File(d.dir, fn).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Slot copy = read(src.dir);          // every field of the original ...
        copy.id = d.id;                     // ... under the new identity
        copy.dir = d.dir;
        copy.name = d.name;
        copy.created = d.created;
        copy.lastPlayed = d.created;
        copy.problem = null;
        writeMeta(copy);
        return read(d.dir);
    }

    /**
     * Delete one slot: only its own directory, and only when that directory really is a direct child
     * of the saves folder (checked on the resolved paths, so a link or a crafted id cannot point the
     * delete anywhere else).
     */
    void delete(Slot s) throws IOException {
        if (s == null || s.dir == null) throw new IOException("No slot to delete");
        if (!Files.isDirectory(root.toPath())) throw new IOException("There is no saves folder");
        Path base = root.toPath().toRealPath();
        Path raw = s.dir.toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(raw)) throw new IOException("Refusing to delete a linked folder: " + raw);
        if (!Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a slot folder: " + raw);
        Path dir = raw.toRealPath();
        if (dir.getParent() == null || !dir.getParent().equals(base) || dir.equals(base) || !dir.startsWith(base))
            throw new IOException("Refusing to delete outside the saves folder: " + dir);
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {     // does not follow links
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ------------------------------------------------------------------ display helpers

    /** "1 h 04 min", "12 min 30 s", "45 s". */
    static String duration(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        if (s >= 3600) return String.format("%d h %02d min", s / 3600, s % 3600 / 60);
        if (s >= 60) return String.format("%d min %02d s", s / 60, s % 60);
        return s + " s";
    }

    /** "just now", "5 min ago", "yesterday", or a date. */
    static String ago(long thenMillis, long nowMillis) {
        long d = Math.max(0, nowMillis - thenMillis) / 1000;
        if (d < 60) return "just now";
        if (d < 3600) return d / 60 + " min ago";
        if (d < 86_400) return d / 3600 + (d / 3600 == 1 ? " hour ago" : " hours ago");
        if (d < 2 * 86_400) return "yesterday";
        if (d < 14 * 86_400) return d / 86_400 + " days ago";
        return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(thenMillis));
    }
}
