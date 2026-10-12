package precip;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * The Debug mode's cloud sequences: for one cloud type and seed, the six forming steps, the mature
 * cloud and the six dissipating steps, rendered by {@link CloudPainter} on one fixed canvas so the
 * thirteen pictures line up as a sequence. The Clouds tab shows exactly these pictures in its strip
 * and {@link #exportOne} writes exactly these pictures as PNG files, plus one contact sheet.
 * <p>
 * A "seed" here is a seed of the viewer's built-in cloud generator ({@link FakeSim#debugCloud}); the
 * Python simulation's clouds are not reproduced by it.
 */
final class DebugExport {
    private DebugExport() {}

    /** Life fractions of the forming steps: from the first haze to fully condensed (form reaches 1 at 0.24). */
    static final double[] FORMING = {0.05, 0.088, 0.126, 0.164, 0.202, 0.24};
    static final double MATURE = 0.38;
    /** Life fractions of the dissipating steps (form falls from 1 at 0.84 to nothing at 1). */
    static final double[] DISSIPATING = {0.84, 0.866, 0.892, 0.918, 0.944, 0.97};
    static final int STEPS = FORMING.length + 1 + DISSIPATING.length;
    /** Fixed animation time and drift of the fine structure: a picture depends on type, seed, life, light and quality only. */
    static final double TIME = 40;

    /** One picture of a sequence. */
    static final class Step {
        /** {@code forming}, {@code mature} or {@code dissipating}: the part of the sequence (not the simulation's stage name). */
        String part;
        int k, of;
        double life;
        Model.Cloud cloud;
        /** Transparent ARGB, the sequence's canvas size, the cloud alone. */
        BufferedImage img;

        String fileName(String type, long seed) {
            return String.format(Locale.ROOT, "%s_s%d_%s_%dof%d_l%03d.png", type, seed, part, k, of, Math.round(life * 1000));
        }

        String caption() { return String.format(Locale.ROOT, "%s %d/%d  ·  life %.0f %%  ·  form %.2f", part, k, of, life * 100, cloud.form); }
    }

    /** The thirteen pictures of one type and seed. */
    static final class Frames {
        String type;
        long seed;
        double tod, scale;
        int quality, cw, ch;
        /** 0..5 forming, 6 mature, 7..12 dissipating. */
        final Step[] steps = new Step[STEPS];

        Step mature() { return steps[FORMING.length]; }
    }

    /**
     * The world rectangle {minX, minY, maxX, maxY}, relative to the cloud's origin, that holds the cloud of this type and
     * seed at every point of its life (anvil and shear included): one frame for the whole sequence.
     */
    static double[] box(String type, long seed) {
        double[] b = {1e9, 1e9, -1e9, -1e9};
        for (double life : new double[]{0.24, MATURE, 0.5, 0.62, 0.8}) {
            Model.Cloud c = FakeSim.debugCloud(type, seed, life);
            double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
            for (double[] p : c.puffs) {
                if (p[2] <= 0.3) continue;
                minX = Math.min(minX, p[0] - p[2]); maxX = Math.max(maxX, p[0] + p[2]);
                minY = Math.min(minY, p[1] - p[2]); maxY = Math.max(maxY, p[1] + p[2]);
            }
            if (maxX < minX) continue;
            // room for what the painter adds around the skeleton: the anvil of the cumulonimbus family, shear, soft edges
            // (the anvil spreads as far as the renderer's own bounds allow: SharedClouds.bounds uses 0.6 / 0.75 of the width)
            double side = (maxX - minX) * (c.kind == Model.SUPERCELL ? 0.75 * c.anvil : c.kind == Model.CUMULONIMBUS ? 0.6 * c.anvil : 0.06) + Math.abs(c.shear) + 22;
            double up = c.kind == Model.SUPERCELL ? 30 + (maxX - minX) * 0.10 * c.overshoot : 24;
            b[0] = Math.min(b[0], minX - side); b[2] = Math.max(b[2], maxX + side);
            b[1] = Math.min(b[1], minY - up); b[3] = Math.max(b[3], maxY + 26);
        }
        if (b[2] < b[0]) return new double[]{-100, -60, 100, 60};
        return b;
    }

    /** One render of a cloud by the game's painter: fixed time and drift, the light of {@code tod}, optionally its rain shaft. */
    static CloudPainter.Image render(Model.Cloud c, double tod, double scale, boolean veil, CloudPainter.Scratch sc) {
        CloudPainter.Spec sp = CloudPainter.sheetSpec(c, TIME, CloudPainter.Light.of(tod, 0), scale);
        if (veil && c.precipRate > 0.02 && !"none".equals(c.precipType)) {
            sp.precipType = c.precipType;
            sp.precipRate = c.precipRate;
            sp.veilDrop = 400;
            sp.veilDrift = 16;
        }
        return CloudPainter.render(sp, sc);
    }

    /** Render the thirteen pictures (slow: call off the event thread). */
    static Frames frames(String type, long seed, double tod, CloudPainter.Scratch sc) {
        Frames f = new Frames();
        f.type = type; f.seed = seed; f.tod = tod; f.quality = CloudPainter.quality;
        double[] b = box(type, seed);
        double bw = b[2] - b[0], bh = b[3] - b[1];
        f.scale = Math.min(2.0, Math.min(460 / bw, 330 / bh));
        f.cw = Math.max(8, (int) Math.ceil(bw * f.scale));
        f.ch = Math.max(8, (int) Math.ceil(bh * f.scale));
        for (int i = 0; i < STEPS; i++) {
            Step s = new Step();
            if (i < FORMING.length) { s.part = "forming"; s.k = i + 1; s.of = FORMING.length; s.life = FORMING[i]; }
            else if (i == FORMING.length) { s.part = "mature"; s.k = 1; s.of = 1; s.life = MATURE; }
            else { s.part = "dissipating"; s.k = i - FORMING.length; s.of = DISSIPATING.length; s.life = DISSIPATING[i - FORMING.length - 1]; }
            s.cloud = FakeSim.debugCloud(type, seed, s.life);
            s.img = new BufferedImage(f.cw, f.ch, BufferedImage.TYPE_INT_ARGB);
            CloudPainter.Image im = render(s.cloud, tod, f.scale, false, sc);
            if (im != null && im.img != null) {
                Graphics2D g = s.img.createGraphics();
                g.drawImage(im.img, (int) Math.round((im.relX - b[0]) * f.scale), (int) Math.round((im.relY - b[1]) * f.scale), null);
                g.dispose();
            }
            f.steps[i] = s;
        }
        return f;
    }

    /** Sum of the alpha of a picture (0 = nothing drawn). */
    static long alphaSum(BufferedImage img) {
        long sum = 0;
        int w = img.getWidth(), h = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int v : row) sum += v >>> 24;
        }
        return sum;
    }

    /** The sky gradient {top, bottom} the cloud contact sheet of {@code --cloud-sheet} uses for a light. */
    static Color[] skyOf(double tod) {
        CloudPainter.Light L = CloudPainter.Light.of(tod, 0);
        return new Color[]{
                new Color((int) (255 * Math.min(1, L.tr * 0.45)), (int) (255 * Math.min(1, L.tg * 0.72)), (int) (255 * Math.min(1, L.tb * 1.0))),
                new Color((int) (255 * Math.min(1, L.tr * 1.05 + L.sr * 0.2)), (int) (255 * Math.min(1, L.tg * 1.05 + L.sg * 0.12)), (int) (255 * Math.min(1, L.tb * 1.08)))};
    }

    /**
     * The contact sheet of a sequence: forming on the first row with the mature cloud at its end, dissipating on the
     * second, on the sky of the light it was rendered in, every picture captioned.
     */
    static BufferedImage sheet(Frames f) {
        int pad = 10, cap = 34, head = 40, cols = FORMING.length + 1;
        int cellW = f.cw + pad, cellH = f.ch + cap;
        BufferedImage img = new BufferedImage(cols * cellW + pad, head + 2 * cellH + pad, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color[] sky = skyOf(f.tod);
        g.setPaint(new GradientPaint(0, 0, sky[0], 0, img.getHeight(), sky[1]));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        Font fb = new Font(Font.SANS_SERIF, Font.BOLD, 15), fs = new Font(Font.SANS_SERIF, Font.PLAIN, 11), fsb = new Font(Font.SANS_SERIF, Font.BOLD, 11);
        Model.Cloud m = f.mature().cloud;
        g.setColor(new Color(255, 255, 255, 235));
        g.setFont(fb);
        g.drawString(f.type + "   seed " + f.seed, pad, 20);
        g.setFont(fs);
        g.setColor(new Color(255, 255, 255, 200));
        g.drawString(String.format(Locale.ROOT, "%s  ·  %.0f x %.0f world px  ·  %d puffs  ·  light %s  ·  cloud quality %s  ·  %.2f px per world px  ·  built-in generator seed",
                m.name, m.w, m.h, m.puffs.length, lightName(f.tod), Settings.QUALITY_NAMES[Math.max(0, Math.min(2, f.quality))], f.scale), pad, 35);
        for (int i = 0; i < STEPS; i++) {
            Step s = f.steps[i];
            int col = i < FORMING.length ? i : i == FORMING.length ? FORMING.length : i - FORMING.length - 1, row = i <= FORMING.length ? 0 : 1;
            int x = pad + col * cellW, y = head + row * cellH;
            g.setColor(new Color(255, 255, 255, 26));
            g.drawRect(x, y, f.cw - 1, f.ch - 1);
            g.drawImage(s.img, x, y, null);
            // (a dark plate under the caption: the sky is nearly white towards the bottom of the sheet)
            g.setColor(new Color(10, 16, 30, 150));
            g.fillRoundRect(x, y + f.ch + 2, Math.min(f.cw, 250), 29, 8, 8);
            g.setFont(fsb);
            g.setColor(new Color(255, 255, 255, 235));
            g.drawString(s.part + " " + s.k + "/" + s.of, x + 5, y + f.ch + 13);
            g.setFont(fs);
            g.setColor(new Color(255, 255, 255, 205));
            g.drawString(String.format(Locale.ROOT, "life %.1f %%  form %.2f  %s", s.life * 100, s.cloud.form, s.cloud.stage), x + 5, y + f.ch + 27);
        }
        // the free cell at the end of the second row: what the rows are
        int x = pad + FORMING.length * cellW, y = head + cellH;
        g.setColor(new Color(10, 16, 30, 150));
        g.fillRoundRect(x, y + 4, Math.min(f.cw, 190), 84, 8, 8);
        g.setFont(fs);
        g.setColor(new Color(255, 255, 255, 225));
        String[] note = {"Row 1: forming, then the", "mature cloud (life 38 %).", "Row 2: dissipating.", "Same canvas and scale in", "every picture."};
        for (int k = 0; k < note.length; k++) g.drawString(note[k], x + 8, y + 20 + k * 15);
        g.dispose();
        return img;
    }

    static String lightName(double tod) {
        for (int k = 0; k < DebugScreen.LIGHT_TOD.length; k++) if (Math.abs(DebugScreen.LIGHT_TOD[k] - tod) < 1e-6) return DebugScreen.LIGHTS[k].toLowerCase(Locale.ROOT);
        return String.format(Locale.ROOT, "tod %.2f", tod);
    }

    /** The {@code debug} folder next to where the game's screenshot key writes ({@code AppShell.screenshot}: {@code screenshots/}). */
    static File folder(File root) { return new File(new File(root, "screenshots"), "debug"); }

    /** A file that does not exist yet: the name itself, else {@code name-2.png}, {@code name-3.png} ... (as the screenshot key does). */
    static File unique(File f) {
        if (!f.exists()) return f;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        for (int k = 2; ; k++) {
            File g = new File(f.getParentFile(), n.substring(0, dot) + "-" + k + n.substring(dot));
            if (!g.exists()) return g;
        }
    }

    /**
     * Write the sequence of one type and seed into {@code outDir/<type>-seed<N>/}: thirteen transparent PNGs named
     * {@code <type>_s<N>_<part>_<k>of<K>_l<life in per mille>.png} and the contact sheet {@code <type>_s<N>_sheet.png}.
     * Nothing is overwritten. Returns the files written, the sheet last.
     */
    static List<File> exportOne(String type, long seed, double tod, File outDir) throws IOException {
        return write(frames(type, seed, tod, new CloudPainter.Scratch()), outDir);
    }

    static List<File> write(Frames f, File outDir) throws IOException {
        File dir = new File(outDir, f.type + "-seed" + f.seed);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        List<File> out = new ArrayList<>();
        for (Step s : f.steps) {
            File file = unique(new File(dir, s.fileName(f.type, f.seed)));
            if (!ImageIO.write(s.img, "png", file)) throw new IOException("no PNG writer");
            out.add(file);
        }
        File sheet = unique(new File(dir, f.type + "_s" + f.seed + "_sheet.png"));
        if (!ImageIO.write(sheet(f), "png", sheet)) throw new IOException("no PNG writer");
        out.add(sheet);
        return out;
    }

    /** {@link #exportOne} for all nine types. {@code progress} (may be null) hears the type that is about to be rendered. */
    static List<File> exportAll(long seed, double tod, File outDir, java.util.function.Consumer<String> progress) throws IOException {
        List<File> out = new ArrayList<>();
        CloudPainter.Scratch sc = new CloudPainter.Scratch();
        for (String type : Model.CLOUD_TYPES) {
            if (progress != null) progress.accept(type);
            out.addAll(write(frames(type, seed, tod, sc), outDir));
        }
        return out;
    }
}
