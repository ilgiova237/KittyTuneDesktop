# hybrid routing: windows x64 measurements

Synthetic stereo PCM, 48 kHz, 256 x 1024-frame blocks. Three rounds per fixed setting; one sweep. Median total wall time in milliseconds, including copying and dispatch. Algorithms and latency differ. Individual timings are noisy; raw block timings and allocation are retained.

| JDK | case (speed, pitch) | Kotlin ms | Native ms | Hybrid ms |
| --- | --- | ---: | ---: | ---: |
| 21 | steady (1.00, 1.00) | 11.97 | 185.85 | 9.83 |
| 21 | steady (0.75, 1.00) | 361.14 | 245.92 | 279.63 |
| 21 | steady (1.50, 1.00) | 213.09 | 154.59 | 147.69 |
| 21 | steady (1.00, 0.75) | 276.02 | 189.47 | 194.18 |
| 21 | steady (1.00, 1.50) | 538.58 | 175.46 | 178.68 |
| 21 | steady (1.25, 1.25) | 34.64 | 150.41 | 32.22 |
| 21 | sweep (1.00, 1.00) | 512.85 | 223.21 | 214.31 |
| 25 | steady (1.00, 1.00) | 6.98 | 201.84 | 7.16 |
| 25 | steady (0.75, 1.00) | 444.63 | 247.92 | 290.02 |
| 25 | steady (1.50, 1.00) | 275.05 | 152.72 | 148.86 |
| 25 | steady (1.00, 0.75) | 275.75 | 182.96 | 178.79 |
| 25 | steady (1.00, 1.50) | 504.43 | 186.81 | 189.62 |
| 25 | steady (1.25, 1.25) | 35.87 | 164.59 | 33.00 |
| 25 | sweep (1.00, 1.00) | 498.52 | 228.77 | 168.12 |

Routing checks passed on JDK 21 and 25: regular and coupled speed/pitch output matches Kotlin byte-for-byte; independent tempo/pitch output matches direct native output. Separate missing-library JVM checks confirm fallback to Kotlin. Native audio/lifecycle checks also passed on both JDKs.

The hybrid uses Kotlin for unity and coupled speed/pitch, Rust/Signalsmith for independent tempo/pitch. The sweep starts with independent parameters, so its hybrid instance stays native. Selection is per segment, not a seamless live backend switch. These measurements do not establish whole-player CPU usage, native allocation, absence of GC stutters, or listening quality.
