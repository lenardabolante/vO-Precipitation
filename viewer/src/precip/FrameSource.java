package precip;

/** A producer of protocol lines (real Python sim or the built-in fake). */
interface FrameSource {
    /** Start producing; every JSON line goes to state.acceptLine. Fatal problems set state.error. */
    void start(ViewerState state);

    /** Send one command object (already JSON) to the simulation. */
    void send(String json);

    /** Ask the simulation to quit and release resources. */
    void stop();

    String describe();
}
