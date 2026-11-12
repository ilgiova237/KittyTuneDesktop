package poc;

import com.alananasss.kittytune.audio.TimeStretcher;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

public final class StretchProbe {
    private static final int RATE = 48000, BLOCK = 1024, CHANNELS = 2;
    private static final com.sun.management.ThreadMXBean MEMORY =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static short[] sine(int frames, double hz, int start) {
        short[] result = new short[frames * CHANNELS];
        for (int i = 0; i < frames; i++) {
            result[i * 2] = (short) (12000 * Math.sin(2 * Math.PI * hz * (i + start) / RATE));
            result[i * 2 + 1] = (short) (9000 * Math.sin(2 * Math.PI * 660 * (i + start) / RATE));
        }
        return result;
    }
    private static double frequency(short[] pcm, int channel, int start, int frames) {
        int crossings = 0;
        for (int i = start + 1; i < start + frames; i++)
            if (pcm[(i - 1) * 2 + channel] <= 0 && pcm[i * 2 + channel] > 0) crossings++;
        return crossings * (double) RATE / frames;
    }
    private static void audioChecks(float speed, float pitch) {
        int inputFrames = RATE * 2;
        short[] result = new short[(int) (inputFrames / speed + RATE) * 2];
        short[] out = new short[(BLOCK * 10 + 1) * 2];
        int produced = 0;
        try (NativeStretch dsp = new NativeStretch(RATE, 2, BLOCK)) {
            for (int start = 0; start < inputFrames; start += BLOCK) {
                int count = Math.min(BLOCK, inputFrames - start);
                int n = dsp.process(sine(count, 440, start), count, out, speed, pitch);
                System.arraycopy(out, 0, result, produced * 2, n * 2);
                produced += n;
            }
            require(Math.abs(produced - inputFrames / (double) speed) < 2, "duration drift");
            int latency = dsp.latency(speed);
            int n = dsp.flush(out, Math.min(latency, BLOCK * 10));
            require(n > 0, "flush produced no tail");
            int skip = Math.max(RATE / 4, latency + RATE / 10);
            int window = Math.min(RATE / 2, produced - skip);
            require(window > RATE / 8, "insufficient settled audio");
            double left = frequency(result, 0, skip, window);
            double right = frequency(result, 1, skip, window);
            require(Math.abs(left - 440 * pitch) < 10, "left pitch: " + left);
            require(Math.abs(right - 660 * pitch) < 10, "right pitch: " + right);
            dsp.reset();
            short[] silence = new short[BLOCK * 2];
            for (int i = 0; i < 20; i++) {
                int frames = dsp.process(silence, BLOCK, out, 1f, 1f);
                for (int s = 0; s < frames * 2; s++) require(Math.abs(out[s]) <= 2, "reset leaked old audio");
            }
            System.out.printf(Locale.ROOT, "audio speed=%.2f pitch=%.2f frames=%d left=%.1fHz right=%.1fHz latency=%d%n",
                speed, pitch, produced, left, right, latency);
        }
    }
    private static void lifecycleChecks() throws Exception {
        NativeStretch dsp = new NativeStretch(RATE, 2, BLOCK);
        short[] input = sine(BLOCK, 440, 0), out = new short[BLOCK * 20 + 2];
        try {
            try { dsp.process(input, BLOCK, out, Float.NaN, 1); throw new AssertionError("accepted NaN"); }
            catch (IllegalArgumentException expected) { }
            final boolean[] rejected = {false};
            Thread wrong = new Thread(() -> {
                try { dsp.reset(); } catch (IllegalStateException expected) { rejected[0] = true; }
            });
            wrong.start(); wrong.join(); require(rejected[0], "accepted another thread");
            for (int i = 0; i < 250; i++) dsp.process(input, BLOCK, out, 0.75f + (i % 100) / 100f, 0.8f + (i % 50) / 100f);
        } finally { dsp.close(); }
        dsp.close();
        try { dsp.reset(); throw new AssertionError("accepted a closed handle"); }
        catch (IllegalStateException expected) { }
    }
    private static long allocated() { return MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static void benchmark(boolean nativeDsp, float speed, float pitch, boolean sweep, int blocks, boolean print) {
        benchmark(nativeDsp ? 1 : 0, speed, pitch, sweep, blocks, print);
    }
    private static void benchmark(int engine, float speed, float pitch, boolean sweep, int blocks, boolean print) {
        short[] pcm = sine(BLOCK, 440, 0), out = new short[BLOCK * 20 + 2];
        NativeStretch rust = engine == 1 ? new NativeStretch(RATE, 2, BLOCK) : null;
        TimeStretcher kotlin = engine == 0 ? new TimeStretcher(RATE, 2) : null;
        HybridStretch hybrid = engine == 2 ? new HybridStretch(RATE, 2, BLOCK,
            sweep ? .75f : speed, sweep ? .8f : pitch) : null;
        long[] times = new long[blocks];
        long count = 0, checksum = 0;
        try {
            for (int i = 0; i < 128; i++) {
                if (rust != null) rust.process(pcm, BLOCK, out, speed, pitch);
                else if (hybrid != null) hybrid.process(pcm, BLOCK, out);
                else { kotlin.setParameters(speed, pitch); kotlin.queue(pcm, pcm.length); while (kotlin.available() > 0) kotlin.drain(out, out.length); }
            }
            long before = allocated(), began = System.nanoTime();
            for (int i = 0; i < blocks; i++) {
                float currentSpeed = sweep ? 0.75f + (i % 100) / 100f : speed;
                float currentPitch = sweep ? 0.8f + (i % 50) / 100f : pitch;
                long start = System.nanoTime();
                int n;
                if (rust != null) n = rust.process(pcm, BLOCK, out, currentSpeed, currentPitch) * 2;
                else if (hybrid != null) {
                    hybrid.setParameters(currentSpeed, currentPitch);
                    n = hybrid.process(pcm, BLOCK, out) * 2;
                    while (hybrid.available() > 0) n += hybrid.drain(out) * 2;
                }
                else {
                    kotlin.setParameters(currentSpeed, currentPitch);
                    kotlin.queue(pcm, pcm.length);
                    n = 0;
                    while (kotlin.available() > 0) n += kotlin.drain(out, out.length);
                }
                times[i] = System.nanoTime() - start;
                count += n;
                checksum += out[0];
            }
            long elapsed = System.nanoTime() - began, bytes = allocated() - before;
            Arrays.sort(times);
            if (print) System.out.printf(Locale.ROOT,
                "%s,%s,%.2f,%.2f,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d%n",
                engine == 2 ? "hybrid" : engine == 1 ? "rust-signalsmith-ffm" : "kotlin-wsola", sweep ? "sweep" : "steady", speed, pitch, blocks,
                elapsed / 1e6, times[blocks / 2] / 1e3, times[(int) (blocks * .99)] / 1e3,
                times[blocks - 1] / 1e3, bytes, count, checksum);
        } finally { if (rust != null) rust.close(); if (hybrid != null) hybrid.close(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !args[0].equals("bench")) {
            audioChecks(0.75f, 1f); audioChecks(1.5f, 1f); audioChecks(1f, 1.5f); audioChecks(1.25f, 1.25f);
            lifecycleChecks();
            System.out.println("audio and lifecycle checks passed");
        } else {
            System.out.println("engine,mode,speed,pitch,blocks,wall_ms,p50_us,p99_us,max_us,java_alloc_bytes,output_samples,checksum");
            for (int round = 0; round < 3; round++) for (float[] p : new float[][]{{1f,1f},{.75f,1f},{1.5f,1f},{1f,.75f},{1f,1.5f},{1.25f,1.25f}}) {
                benchmark(round % 2 == 0, p[0], p[1], false, 256, true);
                benchmark(round % 2 != 0, p[0], p[1], false, 256, true);
                benchmark(2, p[0], p[1], false, 256, true);
            }
            benchmark(false, 1, 1, true, 256, true);
            benchmark(true, 1, 1, true, 256, true);
            benchmark(2, 1, 1, true, 256, true);
        }
    }
}
