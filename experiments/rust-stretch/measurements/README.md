# windows x64 measurements

Synthetic stereo PCM, 48 kHz, 256 x 1024-frame blocks per run. Three steady-state rounds; one sweep. Values below are the median total wall time in milliseconds, including bridge copies. Native and Kotlin algorithms differ.

| JDK | case (speed, pitch) | Kotlin ms | Rust/Signalsmith/FFM ms |
| --- | --- | ---: | ---: |
| 21 | steady (1.00, 1.00) | 7.07 | 121.25 |
| 21 | steady (0.75, 1.00) | 278.89 | 183.66 |
| 21 | steady (1.50, 1.00) | 142.95 | 93.24 |
| 21 | steady (1.25, 1.25) | 27.84 | 122.04 |
| 21 | sweep (1.00, 1.00) | 373.19 | 127.53 |
| 25 | steady (1.00, 1.00) | 8.14 | 157.38 |
| 25 | steady (0.75, 1.00) | 407.22 | 244.33 |
| 25 | steady (1.50, 1.00) | 186.49 | 129.01 |
| 25 | steady (1.25, 1.25) | 39.52 | 168.56 |
| 25 | sweep (1.00, 1.00) | 457.22 | 169.29 |

The Kotlin unity and coupled speed/pitch paths are faster in this run. Native tempo-only changes and the sweep are faster. Most warmed steady-state runs measured zero Java-thread allocation for both paths; check raw CSV for outliers and block timing. Native allocation and physical playback were not measured.

Validation: 3 Rust tests passed. Audio and lifecycle checks passed on JDK 21 and 25. At 0.75x tempo / 1x pitch: 128000 output frames from 96000 input frames, settled channels at 440/660 Hz. At 1x tempo / 1.5x pitch: 96000 output frames, channels at 660/990 Hz. No whole-app performance claim follows from these results.
