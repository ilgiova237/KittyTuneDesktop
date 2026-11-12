package poc;

import com.alananasss.kittytune.audio.TimeStretcher;
import java.util.Arrays;

public final class HybridChecks {
    private static final int RATE = 48000, FRAMES = 1024;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static short[] input() {
        short[] pcm = new short[FRAMES * 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((i * 2971) % 24000 - 12000);
        return pcm;
    }
    private static void identicalToKotlin(float speed, float pitch, boolean fallback) {
        TimeStretcher original = new TimeStretcher(RATE, 2);
        original.setParameters(speed, pitch);
        short[] pcm = input(), expected = new short[FRAMES * 24], actual = new short[FRAMES * 24];
        try (HybridStretch hybrid = new HybridStretch(RATE, 2, FRAMES, speed, pitch)) {
            require(hybrid.backend().equals("kotlin"), "wrong regular/fallback route");
            require((hybrid.fallbackReason() != null) == fallback, "native loading happened on the wrong path");
            for (int block = 0; block < 100; block++) {
                original.queue(pcm, pcm.length);
                int n = original.drain(expected, expected.length);
                int m = hybrid.process(pcm, FRAMES, actual) * 2;
                require(n == m, "duration differs from Kotlin");
                require(Arrays.equals(expected, 0, n, actual, 0, m), "samples differ from Kotlin");
            }
            hybrid.reset();
            original.flush();
            require(hybrid.available() == 0, "reset kept queued audio");
        }
    }
    private static void nativeRoute(float speed, float pitch) {
        short[] pcm = input(), expected = new short[FRAMES * 24], actual = new short[FRAMES * 24];
        try (HybridStretch hybrid = new HybridStretch(RATE, 2, FRAMES, speed, pitch);
             NativeStretch direct = new NativeStretch(RATE, 2, FRAMES)) {
            require(hybrid.backend().equals("rust-ffm"), "modified tempo/pitch did not select native");
            for (int block = 0; block < 50; block++) {
                int n = direct.process(pcm, FRAMES, expected, speed, pitch) * 2;
                int m = hybrid.process(pcm, FRAMES, actual) * 2;
                require(n == m && Arrays.equals(expected, 0, n, actual, 0, m), "native route changed the output");
            }
            hybrid.setParameters(1.1f, 1.3f);
            require(hybrid.process(pcm, FRAMES, actual) > 0, "parameter update stopped output");
        }
    }
    public static void main(String[] args) {
        identicalToKotlin(1, 1, false);
        identicalToKotlin(1.25f, 1.25f, false);
        if (args.length > 0 && args[0].equals("missing-library")) {
            identicalToKotlin(.75f, 1, true);
            identicalToKotlin(1, 1.5f, true);
        } else {
            nativeRoute(.75f, 1);
            nativeRoute(1, 1.5f);
        }
        System.out.println("hybrid routing and output checks passed");
    }
}
