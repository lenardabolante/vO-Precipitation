package precip;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.TreeSet;
import javax.imageio.ImageIO;

/**
 * The custom background picture: decoding any file {@code ImageIO} has a reader for, copying it
 * into {@code config/backgrounds/} (so it keeps working when the original moves) and scaling it
 * down once to a size the renderer can fit to the window cheaply. All of this is slow-ish file and
 * pixel work: call it off the UI thread.
 */
final class Backdrops {
    private Backdrops() {}

    /** No side of the prepared picture is longer than this (4K is plenty behind clouds and terrain). */
    static final int MAX_W = 3840, MAX_H = 2160;

    /** What became of a picture the player chose. Exactly one of {@code image} / {@code error} is set. */
    static final class Result {
        BufferedImage image;
        /** File name inside the backgrounds folder. */
        String storedName;
        String error;
        int sourceW, sourceH;
    }

    /** File extensions ImageIO can read on this JVM, lower case (for the file chooser filter). */
    static String[] readableExtensions() {
        TreeSet<String> set = new TreeSet<>();
        for (String s : ImageIO.getReaderFileSuffixes()) if (!s.isEmpty()) set.add(s.toLowerCase(Locale.ROOT));
        return set.toArray(new String[0]);
    }

    /** Decode a file as a picture, or explain plainly why that is not possible. */
    static BufferedImage decode(File f) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("the file does not exist");
        BufferedImage img;
        try {
            img = ImageIO.read(f);
        } catch (IOException | RuntimeException ex) {
            throw new IOException("it is damaged or not a picture Java can read (" + ex.getClass().getSimpleName() + ")");
        } catch (OutOfMemoryError ex) {
            throw new IOException("the picture is too large to open");
        }
        if (img == null) throw new IOException("it is not a picture format Java can read (supported: "
                + String.join(", ", Arrays.asList(readableExtensions())) + ")");
        if (img.getWidth() < 2 || img.getHeight() < 2) throw new IOException("the picture is empty");
        return img;
    }

    /** An opaque RGB copy no larger than {@link #MAX_W} x {@link #MAX_H} (aspect kept). */
    static BufferedImage prescale(BufferedImage src) {
        double k = Math.min(1, Math.min(MAX_W / (double) src.getWidth(), MAX_H / (double) src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * k)), h = Math.max(1, (int) Math.round(src.getHeight() * k));
        BufferedImage cur = src;
        // halve repeatedly first: one big bilinear step from e.g. 12000 px would alias
        while (cur.getWidth() >= w * 2 && cur.getHeight() >= h * 2) cur = draw(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        if (cur.getWidth() != w || cur.getHeight() != h || cur.getType() != BufferedImage.TYPE_INT_RGB) cur = draw(cur, w, h);
        return cur;
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(java.awt.Color.BLACK);          // transparent pictures sit on black
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /**
     * The whole "player chose this file" path, shared by the file chooser, drag-and-drop and the
     * command line: decode it, copy the original into {@code bgDir}, return the prepared picture.
     * A file that cannot be decoded changes nothing on disk and comes back as {@code error}.
     */
    static Result choose(File src, File bgDir) {
        Result r = new Result();
        try {
            BufferedImage img = decode(src);
            r.sourceW = img.getWidth();
            r.sourceH = img.getHeight();
            r.storedName = store(src, bgDir);
            r.image = prescale(img);
        } catch (IOException ex) {
            r.error = "Could not use \"" + (src == null ? "?" : src.getName()) + "\" as a background: " + ex.getMessage()
                    + ". The previous background was kept.";
        } catch (OutOfMemoryError ex) {
            r.error = "Could not use \"" + src.getName() + "\" as a background: the picture is too large. The previous background was kept.";
        }
        return r;
    }

    /** Load the stored background of a previous session; null when there is none or it no longer decodes. */
    static BufferedImage loadStored(File stored) {
        if (stored == null || !stored.isFile()) return null;
        try {
            return prescale(decode(stored));
        } catch (IOException | OutOfMemoryError ex) {
            System.err.println("[viewer] background " + stored + " could not be loaded: " + ex.getMessage());
            return null;
        }
    }

    /** Copy {@code src} into the backgrounds folder (unless it already lives there); returns its file name. */
    static String store(File src, File bgDir) throws IOException {
        Files.createDirectories(bgDir.toPath());
        Path dir = bgDir.toPath().toRealPath(), from = src.toPath().toRealPath();
        if (from.getParent() != null && from.getParent().equals(dir)) return from.getFileName().toString();
        String name = src.getName().replaceAll("[^A-Za-z0-9._ -]", "_");
        if (name.isEmpty() || name.startsWith(".")) name = "background" + name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        if (stem.length() > 60) stem = stem.substring(0, 60);
        Path target = dir.resolve(stem + ext);
        for (int k = 2; Files.exists(target) && !sameContent(from, target); k++) target = dir.resolve(stem + "-" + k + ext);
        if (!Files.exists(target)) {
            Path tmp = dir.resolve(target.getFileName() + ".tmp");
            Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
            SaveStore.moveInto(tmp, target);
        }
        return target.getFileName().toString();
    }

    private static boolean sameContent(Path a, Path b) {
        try {
            return Files.size(a) == Files.size(b) && Files.mismatch(a, b) == -1;
        } catch (IOException ex) {
            return false;
        }
    }
}
