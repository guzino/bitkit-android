# AndroidX native sources

Bitkit builds the native sources required by CameraX `1.6.1` and AndroidX
Graphics Path `1.1.0` with 16 KB `LOAD` and `GNU_RELRO` alignment.

- `camera-core/` matches AndroidX camera release commit
  `987b9ac8585b31424a397206c492196dd163997b`.
- `graphics-path/` matches AndroidX graphics release commit
  `1c44f0be720ff16cd30357ad2375239bf97a1da8`.

The source files retain their Android Open Source Project Apache 2.0 license
headers. `Android.mk` supplies the page-size linker configuration while keeping
the release source unchanged.

The CameraX image-processing target uses the required source subset from libyuv
commit `ddc6764d1392fb2e3ff5752b12c73786a989473e`. The portable C kernels provide
consistent behavior across Bitkit's packaged ABIs. The libyuv files retain
their BSD license headers, with `LICENSE` and `PATENTS` stored alongside the
source.
