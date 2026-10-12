package precip;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays back a recorded protocol stream (JSONL: one hello, then frames; see tools/record_replay.py)
 * at 30 fps, or step-by-step for --snapshot / --bench. Wraps around at the end.
 */
final class ReplaySource implements FrameSource, Scripted {
    private final String helloLine;
    private final List<String> frames = new ArrayList<>();
    private int next;
    private volatile boolean running;
    private final String name;

    ReplaySource(File f) throws IOException {
        String h = null;
        for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            if (l.isBlank()) continue;
            if (h == null && l.contains("\"type\":\"hello\"")) h = l; else frames.add(l);
        }
        if (frames.isEmpty()) throw new IOException("no frames in " + f);
        helloLine = h != null ? h : "{\"type\":\"hello\",\"version\":2,\"seed\":0,\"world\":{\"w\":3840,\"h\":1080,\"ground_y\":1000}}";
        name = "replay " + f.getName() + " (" + frames.size() + " frames)";
    }

    @Override public String hello() { return helloLine; }

    @Override public synchronized String step() {
        String s = frames.get(next);
        next = (next + 1) % frames.size();
        return s;
    }

    @Override public String describe() { return name; }

    @Override
    public void start(ViewerState state) {
        running = true;
        state.acceptLine(hello());
        Thread th = new Thread(() -> {
            long t = System.nanoTime();
            while (running) {
                state.acceptLine(step());
                t += 33_333_333L;
                long sleep = t - System.nanoTime();
                if (sleep > 0) {
                    try { Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000)); } catch (InterruptedException e) { return; }
                } else t = System.nanoTime();
            }
        }, "replay");
        th.setDaemon(true);
        th.start();
    }

    @Override public void send(String json) { }

    @Override public void stop() { running = false; }
}
