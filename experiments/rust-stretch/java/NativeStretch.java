package poc;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

@SuppressWarnings("preview")
public final class NativeStretch implements AutoCloseable {
    private static final Arena LIBRARY = Arena.ofShared();
    private static final SymbolLookup SYMBOLS = SymbolLookup.libraryLookup(
        Path.of(System.getProperty("kittytune.stretch.library")).toAbsolutePath(), LIBRARY);
    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        return Linker.nativeLinker().downcallHandle(SYMBOLS.find(name).orElseThrow(), descriptor);
    }
    private static final MethodHandle ABI = bind("kt_stretch_abi", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle CREATE = bind("kt_stretch_create", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle PROCESS = bind("kt_stretch_process", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT));
    private static final MethodHandle FLUSH = bind("kt_stretch_flush", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle RESET = bind("kt_stretch_reset", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle LATENCY = bind("kt_stretch_latency", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT));
    private static final MethodHandle DESTROY = bind("kt_stretch_destroy", FunctionDescriptor.ofVoid(ADDRESS));

    private final Thread owner = Thread.currentThread();
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment handle, input, output;
    private final int channels, maximum, outputCapacity;
    private short[] lastInput, lastOutput;
    private MemorySegment inputView, outputView;
    private boolean closed;

    public NativeStretch(int rate, int channels, int maximum) {
        if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2 || maximum < 64 || maximum > 8192)
            throw new IllegalArgumentException("unsupported format or block size");
        this.channels = channels;
        this.maximum = maximum;
        outputCapacity = maximum * 10 + 1;
        try {
            if ((int) ABI.invokeExact() != 1) throw new IllegalStateException("unsupported native ABI");
            input = arena.allocate((long) maximum * channels * 2, 2);
            output = arena.allocate((long) outputCapacity * channels * 2, 2);
            handle = (MemorySegment) CREATE.invokeExact(rate, channels, maximum);
            if (handle.address() == 0) throw new IllegalStateException("native creation failed");
        } catch (Throwable failure) { arena.close(); throw failed(failure); }
    }

    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("use the DSP owner thread");
        if (closed) throw new IllegalStateException("stretcher is closed");
    }
    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        return failure instanceof RuntimeException r ? r : new IllegalStateException(failure);
    }
    private void destination(short[] pcm) {
        if (pcm != lastOutput) { outputView = MemorySegment.ofArray(pcm); lastOutput = pcm; }
    }
    private int copyResult(int frames) {
        if (frames < 0) throw new IllegalStateException("native DSP returned " + frames);
        MemorySegment.copy(output, 0, outputView, 0, (long) frames * channels * 2);
        return frames;
    }
    public int process(short[] pcm, int frames, short[] result, float speed, float pitch) {
        check();
        if (frames < 4 || frames > maximum || (long) frames * channels > pcm.length ||
            !Float.isFinite(speed) || !Float.isFinite(pitch) || speed < 0.1f || speed > 4f || pitch < 0.1f || pitch > 4f)
            throw new IllegalArgumentException("invalid block or parameters");
        if (pcm != lastInput) { inputView = MemorySegment.ofArray(pcm); lastInput = pcm; }
        destination(result);
        MemorySegment.copy(inputView, 0, input, 0, (long) frames * channels * 2);
        int capacity = Math.min(outputCapacity, result.length / channels);
        try { return copyResult((int) PROCESS.invokeExact(handle, input, frames, output, capacity, speed, pitch)); }
        catch (Throwable failure) { throw failed(failure); }
    }
    public int flush(short[] result, int frames) {
        check();
        if (frames < 1 || frames > outputCapacity || (long) frames * channels > result.length)
            throw new IllegalArgumentException("invalid flush capacity");
        destination(result);
        try { return copyResult((int) FLUSH.invokeExact(handle, output, frames)); }
        catch (Throwable failure) { throw failed(failure); }
    }
    public int latency(float speed) {
        check();
        try { int n = (int) LATENCY.invokeExact(handle, speed); if (n < 0) throw new IllegalArgumentException("speed"); return n; }
        catch (Throwable failure) { throw failed(failure); }
    }
    public void reset() {
        check();
        try { if ((int) RESET.invokeExact(handle) != 0) throw new IllegalStateException("reset failed"); }
        catch (Throwable failure) { throw failed(failure); }
    }
    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("use the DSP owner thread");
        if (closed) return;
        closed = true;
        try { DESTROY.invokeExact(handle); }
        catch (Throwable failure) { throw failed(failure); }
        finally { arena.close(); }
    }
}
