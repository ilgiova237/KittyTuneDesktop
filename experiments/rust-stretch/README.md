# rust / ffm timestretch spike

An isolated experiment based on upstream `02c7574`, not an enabled player backend. Compose, the normal application build, and existing playback stay untouched.

The path is Java FFM -> Rust `cdylib` -> `ssstretch` 0.1.0 -> C++ Signalsmith Stretch. There is no JNI code. This is not a pure Rust implementation of Signalsmith. Rubato alone would not replace pitch-preserving time stretching.

## hybrid prototype

`HybridStretch` selects the existing Kotlin implementation for unity playback and coupled speed/pitch (the resampling-only path). When tempo and pitch are independent, it selects Rust/Signalsmith. If native loading fails, it falls back to Kotlin and exposes the reason. The regular Kotlin path does not initialize the native library. The rest of KittyTune's effects chain is not part of this change.

Selection happens when an analysis/playback segment is created. Parameter updates stay on that engine until it is closed and a new segment is created at a track/seek boundary. This avoids silently throwing away queued audio and changing latency on every slider movement. Seamless engine handoff during playback still needs latency alignment and listening tests before wiring this facade into AudioEngine. This branch remains an isolated POC; the application itself still uses its existing backend.

`HybridChecks` compares ordinary and coupled-speed/pitch output byte-for-byte with the real Kotlin class, and independent tempo/pitch output with the native class. It also runs in a separate JVM with a deliberately missing library to verify fallback. The benchmark includes the selected hybrid path, including pure pitch cases.

## run

Requires Python 3, a Rust MSVC toolchain and Visual Studio C++ Build Tools on Windows (or the native Rust/C++ toolchains on Linux/macOS), JDK 21 or newer, and Kotlin compiler/stdlib jars. The last argument may point at the `lib` directory of an installed Kotlin compiler or Gradle distribution that includes `K2JVMCompiler`.

```powershell
python run.py --java-home $env:JAVA_HOME --kotlin-lib 'C:\path\to\gradle\lib' --bench
```

Use `--cargo C:\path\to\cargo.exe` when Cargo is not on PATH. Custom Rust installations also need their usual `RUSTUP_HOME` / `CARGO_HOME`. `--no-rust` reuses a built library to check another JDK.

The runner pins dependencies with `Cargo.lock`, runs Rust tests, builds the library, compiles the actual repository `TimeStretcher.kt` as the baseline, compiles the Java bridge, and runs audio/lifecycle checks. `--bench` also writes raw timings to `results/`. Normal application Gradle tasks are not invoked.

On Windows it sets `/Zc:__cplusplus` for the C++ dependency: MSVC's legacy `__cplusplus` value otherwise enables the wrapper's obsolete `make_unique` fallback. No dependency files are patched.

FFM is a preview API on JDK 21. The runner uses `--enable-preview` when compiling and running with 21, and `--enable-native-access=ALL-UNNAMED` when running. It builds a separate bridge for newer JDKs; Java 21 preview bytecode must not be dropped into the application's JDK 25 runtime. Both 21 and 25 were tested on Windows x64. Other operating systems have not been exercised.

## boundary

`include/kittytune_stretch.h` describes the C ABI. One handle belongs to one owner thread; Java rejects cross-thread use and calls after close. Each instance has reusable native input/output buffers. Repeated calls with the same Java arrays reuse their memory views. The handle must be closed explicitly with try-with-resources.

Processing accepts interleaved signed 16-bit mono/stereo PCM, 4..maximum input frames per call, speeds/pitches from 0.1 to 4, and rates from 8 to 192 kHz. The returned count is output **frames**. Input/output conversion and the FFM copies are included in the benchmark. The wrapper retains fractional frame counts across blocks and rejects insufficient output capacity without consuming input. Rust errors return status codes; the C caller remains responsible for valid, nonaliasing buffers and live handles.

The Rust wrapper's working buffers are sized at creation. This does not prove the entire C++ library never allocates: native allocation has not been instrumented. Panic handling does not make invalid foreign pointers safe.

Signalsmith adds latency. The harness reports the input/output latency converted to output frames and checks settled sine-wave pitch after that interval. The bridge exposes flush/reset; a production queue/drain adapter still needs explicit startup latency compensation, end-of-stream accounting, and parameter-transition listening tests. It is not a drop-in replacement for the current Kotlin class.

## checks and measurement

Three Rust tests cover invalid formats, rejected output capacity and fractional duration. Java checks cover stereo frequency separation, tempo independent of pitch, coupled speed/pitch, reset after nonzero audio, invalid parameters, rapid parameter updates, wrong-thread calls, double close and use after close.

`StretchProbe bench` alternates the two engines, warms each case, runs three rounds of 256 stereo blocks of 1024 frames at 48 kHz, and records total wall time, block p50/p99/max, Java-thread allocation, output count and a checksum. A separate parameter sweep is included. See `measurements/` for this machine's raw data and summary.

These are different algorithms and quality/latency settings. This is a workload comparison, not a measurement of Rust versus Kotlin in isolation. Output counts differ because the current Kotlin path buffers a tail and this benchmark does not add an artificial EOF policy to that baseline. Java allocation excludes native allocations. One synthetic workload on one machine cannot establish application CPU use, UI smoothness or crossfade performance. Music listening tests, JFR during real playback and testing on other hardware remain necessary before integration.

The results favor keeping the Kotlin pass-through and simple resampling paths. The native candidate is worth investigating for pitch-preserving tempo changes and parameter sweeps. The existing Kotlin code already has very low steady-state Java allocation; these measurements do not support claiming it causes GC stutters, or promising that FFM eliminates them.

FFT/waveform generation and GraalVM native-image are outside this spike. Upstream already added a Windows JDK 25 AOT startup-cache path; startup work should be evaluated separately.

## references

- [Signalsmith Stretch](https://signalsmith-audio.com/code/stretch/) and [Rust wrapper API](https://docs.rs/ssstretch/0.1.0/ssstretch/struct.Stretch.html). Both dependency implementations are MIT licensed; this experiment follows the application's GPLv3 licensing.
- [Java 21 FFM guide](https://docs.oracle.com/en/java/javase/21/core/foreign-function-and-memory-api.html).
