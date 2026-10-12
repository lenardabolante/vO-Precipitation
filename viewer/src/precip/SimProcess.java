package precip;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs `python -u sim/precip_sim.py ...` and pumps its stdout into the ViewerState. */
final class SimProcess implements FrameSource {
    private final File root;
    private final List<String> extraArgs;
    private Process proc;
    private Writer stdin;
    private final ArrayDeque<String> stderrTail = new ArrayDeque<>();
    private volatile boolean stopping;
    private String pythonUsed = "?";

    private final int width, height;

    /** World of the default size (3840 x 1080). */
    SimProcess(File root, List<String> extraArgs) { this(root, 3840, 1080, extraArgs); }

    /**
     * @param extraArgs further precip_sim.py arguments, passed through untouched: e.g. {@code --seed N},
     *                  {@code --terrain hilly}, {@code --settlements dense}, {@code --load FILE}, {@code --preset ID},
     *                  any {@code --mode} (sandbox, showcase, hurricane, world) and the v2.3 {@code --month M},
     *                  {@code --hour H}, {@code --year-days N} (see PROTOCOL.md, Launch)
     */
    SimProcess(File root, int width, int height, List<String> extraArgs) {
        this.root = root;
        this.width = width;
        this.height = height;
        this.extraArgs = extraArgs;
    }

    @Override public String describe() { return "python sim (" + pythonUsed + ")"; }

    /** The extra arguments this simulation is (or will be) started with (tests check that a choice reached the command line). */
    List<String> args() { return java.util.Collections.unmodifiableList(extraArgs); }

    @Override
    public void start(ViewerState state) {
        File script = new File(root, "sim" + File.separator + "precip_sim.py");
        if (!script.isFile()) {
            fail(state, "Simulation script not found:\n" + script.getAbsolutePath()
                    + "\n\nBuild the Python half first, or run the viewer with --fake.");
            return;
        }
        String[] candidates = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? new String[]{"python", "py", "python3"} : new String[]{"python3", "python"};
        IOException last = null;
        for (String py : candidates) {
            List<String> cmd = new ArrayList<>();
            cmd.add(py);
            if (py.equals("py")) cmd.add("-3");
            cmd.add("-u");
            cmd.add(script.getAbsolutePath());
            cmd.add("--width"); cmd.add(String.valueOf(width));
            cmd.add("--height"); cmd.add(String.valueOf(height));
            cmd.add("--fps"); cmd.add("30");
            cmd.addAll(extraArgs);
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd).directory(root);
                pb.environment().put("PYTHONIOENCODING", "utf-8");
                pb.environment().put("PYTHONUNBUFFERED", "1");
                proc = pb.start();
                pythonUsed = py;
                break;
            } catch (IOException ex) {
                last = ex;
            }
        }
        if (proc == null) {
            fail(state, "Could not start Python (tried python, py, python3 on PATH).\n"
                    + (last != null ? last.getMessage() : "") + "\n\nInstall Python 3 or run with --fake.");
            return;
        }
        stdin = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
        synchronized (state) { state.status = "Started " + pythonUsed + " " + script.getName(); }

        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    System.err.println("[sim] " + l);
                    synchronized (stderrTail) {
                        stderrTail.addLast(l);
                        while (stderrTail.size() > 12) stderrTail.removeFirst();
                    }
                }
            } catch (IOException ignored) { }
        }, "sim-stderr");
        err.setDaemon(true);
        err.start();

        Thread out = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
                String l;
                while ((l = r.readLine()) != null) state.acceptLine(l);
            } catch (IOException ignored) { }
            if (stopping) return;
            int code = -1;
            try {
                if (proc.waitFor(2, TimeUnit.SECONDS)) code = proc.exitValue();
            } catch (InterruptedException ignored) { }
            StringBuilder sb = new StringBuilder("The Python simulation stopped (exit code " + code + ").");
            synchronized (stderrTail) {
                if (!stderrTail.isEmpty()) {
                    sb.append("\n\nLast stderr lines:");
                    for (String s : stderrTail) sb.append("\n  ").append(s);
                }
            }
            sb.append("\n\nRestart the viewer, or run it with --fake to preview the visuals.");
            fail(state, sb.toString());
        }, "sim-stdout");
        out.setDaemon(true);
        out.start();
    }

    private static void fail(ViewerState state, String msg) {
        System.err.println("[viewer] " + msg);
        synchronized (state) {
            state.error = msg;
            state.status = "Simulation unavailable";
        }
    }

    @Override
    public synchronized void send(String json) {
        if (stdin == null) return;
        try {
            stdin.write(json);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException ex) {
            stdin = null; // process gone; the stdout thread will report it
        }
    }

    @Override
    public void stop() {
        stopping = true;
        if (proc == null) return;
        send("{\"cmd\":\"quit\"}");
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) { }
        // "python" may be a launcher (py.exe, a venv redirector) with the real interpreter as its child:
        // remember the children so that none of them can outlive the viewer
        List<ProcessHandle> children = new ArrayList<>();
        try {
            proc.descendants().forEach(children::add);
        } catch (RuntimeException ignored) { }
        try {
            if (!proc.waitFor(800, TimeUnit.MILLISECONDS)) proc.destroy();
            if (!proc.waitFor(500, TimeUnit.MILLISECONDS)) proc.destroyForcibly();
            proc.waitFor(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            proc.destroyForcibly();
        }
        for (ProcessHandle c : children) {
            if (!c.isAlive()) continue;
            try {
                c.onExit().get(400, TimeUnit.MILLISECONDS);   // it exits by itself when its stdin closes
            } catch (Exception ex) {
                c.destroyForcibly();
            }
        }
    }

    /** True while the simulation process (or a child of it) is still running. */
    boolean alive() {
        Process p = proc;
        if (p == null) return false;
        if (p.isAlive()) return true;
        try {
            return p.descendants().anyMatch(ProcessHandle::isAlive);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Process id of the simulation, or -1 when it never started. */
    long pid() {
        Process p = proc;
        return p == null ? -1 : p.pid();
    }
}
