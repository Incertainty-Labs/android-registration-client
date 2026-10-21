# Consented test photos

Face photos used by instrumented tests are **never committed**: this repository is public. `assets/consented/` is gitignored, and tests that need a photo are skipped when it is missing.

To run them locally, put a consented photo of exactly one face at `assets/consented/b-selfie.jpg`:

- Only use a photo of someone who has agreed to its use in this project's tests, usually yourself.
- Front-facing, well lit, about 480x640 JPEG.
- Strip all metadata (EXIF, GPS, XMP) before copying it in.
- Record the consent in `testdata/MANIFEST.csv` in mosip-face-liveness (file name, who consented, date, purpose; never the image).

| Test | Photo needed |
| --- | --- |
| `YuNetOnDeviceTest.b03_yunetOnDevice_findsOneFaceInConsentedPhoto` | `b-selfie.jpg`, exactly one face |
| `YuNetOnDeviceTest.b03_yunetOnDevice_findsNoFaceInBlankImage` | none (generated) |
