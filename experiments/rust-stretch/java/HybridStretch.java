package poc;

import com.alananasss.kittytune.audio.TimeStretcher;

public final class HybridStretch implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final int channels, maximum;
    private final TimeStretcher kotlin;
    private NativeStretch nativeDsp;
    private float speed, pitch;
    private boolean closed;
    private String fallbackReason;

    public HybridStretch(int rate, int channels, int maximum, float speed, float pitch) {
        if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2 || maximum < 64 || maximum > 8192)
            throw new IllegalArgumentException("unsupported format or block size");
        validate(speed, pitch);
        this.channels = channels;
        this.maximum = maximum;
        kotlin = new TimeStretcher(rate, channels);
        this.speed = speed;
        this.pitch = pitch;
        kotlin.setParameters(speed, pitch);
        if (Math.abs(speed / pitch - 1f) >= 1e-3f) {
            try { nativeDsp = new NativeStretch(rate, channels, maximum); }
            catch (LinkageError | IllegalStateException failure) {
                fallbackReason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
        }
    }

    private static void validate(float speed, float pitch) {
        if (!Float.isFinite(speed) || !Float.isFinite(pitch) || speed < .1f || speed > 4f || pitch < .1f || pitch > 4f)
            throw new IllegalArgumentException("invalid speed or pitch");
    }

    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("use the DSP owner thread");
        if (closed) throw new IllegalStateException("stretcher is closed");
    }

    public String backend() { check(); return nativeDsp == null ? "kotlin" : "rust-ffm"; }
    public String fallbackReason() { check(); return fallbackReason; }

    // Keep the selected engine until a track/seek boundary; their latencies differ.
    public void setParameters(float speed, float pitch) {
        check();
        validate(speed, pitch);
        this.speed = speed;
        this.pitch = pitch;
        kotlin.setParameters(speed, pitch);
    }

    public int process(short[] input, int frames, short[] output) {
        check();
        if (frames < 4 || frames > maximum || (long) frames * channels > input.length || output.length < channels)
            throw new IllegalArgumentException("invalid block or output capacity");
        if (nativeDsp != null) return nativeDsp.process(input, frames, output, speed, pitch);
        kotlin.queue(input, frames * channels);
        return kotlin.drain(output, output.length - output.length % channels) / channels;
    }

    public int available() { check(); return nativeDsp == null ? kotlin.available() / channels : 0; }
    public int drain(short[] output) {
        check();
        return nativeDsp == null ? kotlin.drain(output, output.length - output.length % channels) / channels : 0;
    }

    public void reset() {
        check();
        kotlin.flush();
        if (nativeDsp != null) nativeDsp.reset();
    }

    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("use the DSP owner thread");
        if (closed) return;
        closed = true;
        kotlin.flush();
        if (nativeDsp != null) nativeDsp.close();
    }
}
