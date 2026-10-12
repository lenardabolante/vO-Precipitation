package precip;

/** A deterministic, pull-based frame source (fake sim or a recorded replay) for snapshots and benches. */
interface Scripted {
    String hello();

    /** The next frame line. */
    String step();
}
