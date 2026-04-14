package com.capo.sub_agent_visual_effects.services;

import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class ImageSmoothingEngine {

    // -----------------------------------------------------------------------
    // All OpenCV Image Filtering & Smoothing operations
    // Keys are lower-cased so applyFilter(filterName.toLowerCase()) always hits.
    // -----------------------------------------------------------------------
    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> filterRegistry = Map.ofEntries(

        // 1. Normalized box filter – simple average blur
        Map.entry("blur", (src, params) -> {
            Mat dst = new Mat();
            int k = ((Number) params.getOrDefault("ksize", 3)).intValue();
            Imgproc.blur(src, dst, new Size(k, k));
            return dst;
        }),

        // 2. Gaussian blur – weighted average using Gaussian kernel
        Map.entry("gaussian", (src, params) -> {
            Mat dst = new Mat();
            int k    = ((Number) params.getOrDefault("ksize", 3)).intValue();
            double sigma = ((Number) params.getOrDefault("sigma", 0)).doubleValue();
            int oddK = (k % 2 == 0) ? k + 1 : k;
            Imgproc.GaussianBlur(src, dst, new Size(oddK, oddK), sigma);
            return dst;
        }),

        // 3. Median blur – replaces each pixel with the median of its neighbourhood
        Map.entry("median", (src, params) -> {
            Mat dst = new Mat();
            int k = ((Number) params.getOrDefault("ksize", 3)).intValue();
            Imgproc.medianBlur(src, dst, (k % 2 == 0) ? k + 1 : k);
            return dst;
        }),

        // 4. Bilateral filter – edge-preserving smoothing
        //    params: diameter (9), sigmaColor (75), sigmaSpace (75)
        Map.entry("bilateral", (src, params) -> {
            Mat dst = new Mat();
            int    d          = ((Number) params.getOrDefault("diameter",   9 )).intValue();
            double sigmaColor = ((Number) params.getOrDefault("sigmaColor", 75)).doubleValue();
            double sigmaSpace = ((Number) params.getOrDefault("sigmaSpace", 75)).doubleValue();
            Imgproc.bilateralFilter(src, dst, d, sigmaColor, sigmaSpace);
            return dst;
        }),

        // 5. Box filter – generalised box filter with optional normalisation
        //    params: ksize (3), normalize (true)
        Map.entry("boxfilter", (src, params) -> {
            Mat dst = new Mat();
            int     k         = ((Number) params.getOrDefault("ksize", 3)).intValue();
            boolean normalize = Boolean.parseBoolean(
                    String.valueOf(params.getOrDefault("normalize", true)));
            Imgproc.boxFilter(src, dst, -1, new Size(k, k), new Point(-1, -1), normalize);
            return dst;
        }),

        // 6. Squared box filter – computes sum of squares in neighbourhood
        //    Useful as a building block for variance / standard deviation maps.
        //    params: ksize (3)
        Map.entry("sqrboxfilter", (src, params) -> {
            Mat dst = new Mat();
            int k = ((Number) params.getOrDefault("ksize", 3)).intValue();
            Imgproc.sqrBoxFilter(src, dst, -1, new Size(k, k));
            return dst;
        }),

        // 7. 2-D convolution with a custom or named kernel
        //    params: kernelType – one of: sharpen | emboss | edgedetect | identity (default: sharpen)
        Map.entry("filter2d", (src, params) -> {
            Mat dst        = new Mat();
            String kType   = String.valueOf(params.getOrDefault("kernelType", "sharpen"));
            Mat kernel     = buildKernel(kType);
            Imgproc.filter2D(src, dst, -1, kernel);
            return dst;
        }),

        // 8. Separable linear filter – applies two 1-D kernels (row then column)
        //    Uses Gaussian separable kernels by default.
        //    params: ksize (5, must be odd), sigma (1.0)
        Map.entry("sepfilter2d", (src, params) -> {
            Mat dst    = new Mat();
            int k      = ((Number) params.getOrDefault("ksize",  5  )).intValue();
            double sig = ((Number) params.getOrDefault("sigma",  1.0)).doubleValue();
            int oddK   = (k % 2 == 0) ? k + 1 : k;
            Mat kX = Imgproc.getGaussianKernel(oddK, sig);
            Mat kY = Imgproc.getGaussianKernel(oddK, sig);
            Imgproc.sepFilter2D(src, dst, -1, kX, kY);
            return dst;
        }),

        // 9. Stack blur – fast approximation of Gaussian blur (OpenCV >= 4.7)
        //    params: ksize (3, must be odd and > 1)
        Map.entry("stackblur", (src, params) -> {
            Mat dst  = new Mat();
            int k    = ((Number) params.getOrDefault("ksize", 3)).intValue();
            int oddK = (k % 2 == 0) ? k + 1 : k;
            Imgproc.stackBlur(src, dst, new Size(oddK, oddK));
            return dst;
        }),

        // 10. Mean-shift filtering – edge-preserving, colour-space segmentation blur
        //     Requires 8-bit 3-channel (BGR) input.
        //     params: sp – spatial window radius (21), sr – colour window radius (51)
        Map.entry("pyrmeanshift", (src, params) -> {
            Mat dst  = new Mat();
            double sp = ((Number) params.getOrDefault("sp", 21)).doubleValue();
            double sr = ((Number) params.getOrDefault("sr", 51)).doubleValue();
            Imgproc.pyrMeanShiftFiltering(src, dst, sp, sr);
            return dst;
        }),

        // 11. Vignette – subtle aged-paper edge tinting.  The vignette is intentionally light so
        //     the paper background stays near its original brightness.  A coarse bilinear-noise
        //     layer perturbs the radial falloff, breaking the perfect-oval shape.
        //     Per-channel multipliers give a faint warm (sepia) cast rather than neutral gray.
        //     params: strength    (double, 0.40 — 0.0=no effect, 1.0=fully black edges)
        //             innerRadius (double, 0.52 — flat bright zone as fraction of corner distance)
        //             feather     (double, 2.0  — falloff power: 1.0=linear, 2.0=smooth, 4.0=sharp)
        //             noise       (double, 0.12 — edge irregularity; 0=perfect oval, 0.3=very ragged)
        //             seed        (int,    42   — random seed for reproducible noise pattern)
        Map.entry("vignette", (src, params) -> {
            double strength    = ((Number) params.getOrDefault("strength",    0.40)).doubleValue();
            double innerRadius = ((Number) params.getOrDefault("innerRadius", 0.52)).doubleValue();
            double feather     = ((Number) params.getOrDefault("feather",     2.0 )).doubleValue();
            double noise       = ((Number) params.getOrDefault("noise",       0.12)).doubleValue();
            int    seed        = ((Number) params.getOrDefault("seed",        42  )).intValue();
            int rows = src.rows();
            int cols = src.cols();
            double cx      = cols / 2.0;
            double cy      = rows / 2.0;
            double maxDist = Math.sqrt(cx * cx + cy * cy);

            // Coarse noise grid (gridSize×gridSize) bilinearly interpolated per pixel.
            // Gaussian-distributed values give softer, more natural irregularity than uniform noise.
            Random rng      = new Random(seed);
            int    gridSize = 12;
            float[][] noiseGrid = new float[gridSize + 1][gridSize + 1];
            for (int gy = 0; gy <= gridSize; gy++)
                for (int gx = 0; gx <= gridSize; gx++)
                    noiseGrid[gy][gx] = (float)(rng.nextGaussian() * 0.5); // roughly [-1, 1]

            // Build 3-channel float mask directly — avoids a merge call and lets each
            // BGR channel carry a different multiplier for the warm-toning effect.
            Mat     mask   = new Mat(rows, cols, CvType.CV_32FC3);
            float[] rowBuf = new float[cols * 3];

            for (int r = 0; r < rows; r++) {
                double dy  = (r - cy) / maxDist;
                double gfy = (double) r / rows * gridSize;
                int    gy0 = (int) gfy;
                int    gy1 = Math.min(gy0 + 1, gridSize);
                double gyt = gfy - gy0;

                for (int c = 0; c < cols; c++) {
                    double dx  = (c - cx) / maxDist;
                    double d   = Math.sqrt(dx * dx + dy * dy);

                    // Bilinear sample of coarse noise grid
                    double gfx = (double) c / cols * gridSize;
                    int    gx0 = (int) gfx;
                    int    gx1 = Math.min(gx0 + 1, gridSize);
                    double gxt = gfx - gx0;
                    double n   = noiseGrid[gy0][gx0] * (1 - gxt) * (1 - gyt)
                               + noiseGrid[gy0][gx1] *      gxt  * (1 - gyt)
                               + noiseGrid[gy1][gx0] * (1 - gxt) *      gyt
                               + noiseGrid[gy1][gx1] *      gxt  *      gyt;

                    // Perturb radial distance → ragged, non-circular border
                    double dPerturbed = d + noise * n;
                    double t = Math.max(0.0, Math.min(1.0,
                            (dPerturbed - innerRadius) / (1.0 - innerRadius)));
                    double darkening = strength * Math.pow(t, feather);

                    // Subtle warm-toned multipliers: very mild sepia shift (not orange).
                    // B reduced slightly more than R to give warmth without heavy colouring.
                    rowBuf[c * 3    ] = (float) Math.max(0.0, 1.0 - darkening * 1.10); // B
                    rowBuf[c * 3 + 1] = (float) Math.max(0.0, 1.0 - darkening * 1.00); // G
                    rowBuf[c * 3 + 2] = (float) Math.max(0.0, 1.0 - darkening * 0.85); // R
                }
                mask.put(r, 0, rowBuf);
            }

            // Apply: src → float → per-channel multiply → clip back to original depth
            Mat srcF = new Mat();
            src.convertTo(srcF, CvType.CV_32FC3);
            Mat resultF = new Mat();
            Core.multiply(srcF, mask, resultF);
            Mat dst = new Mat();
            resultF.convertTo(dst, src.type());
            return dst;
        }),

        // 12. Scratches – draws random thin lines simulating paper surface scratches or film wear.
        //     Light scratches add brightness (light streaks); dark scratches subtract brightness (grooves).
        //     Non-scratch pixels are completely unmodified.
        //     params: count     (int,    12     — number of scratch lines)
        //             opacity   (double, 0.15   — scratch intensity as fraction of full 0–255 range)
        //             minLength (double, 0.3    — minimum scratch length as fraction of longest dimension)
        //             thickness (int,    1      — line width in pixels; 1 keeps them subtle)
        //             color     (String, "light"— "light" = bright streak, "dark" = groove)
        //             seed      (int,    42     — random seed; same seed = reproducible pattern)
        Map.entry("scratches", (src, params) -> {
            int    count     = ((Number) params.getOrDefault("count",     12   )).intValue();
            double opacity   = ((Number) params.getOrDefault("opacity",    0.15)).doubleValue();
            double minLength = ((Number) params.getOrDefault("minLength",  0.3 )).doubleValue();
            int    thickness = ((Number) params.getOrDefault("thickness",  1   )).intValue();
            String colorStr  = String.valueOf(params.getOrDefault("color", "light"));
            int    seed      = ((Number) params.getOrDefault("seed",      42   )).intValue();

            int    rows   = src.rows();
            int    cols   = src.cols();
            int    maxDim = Math.max(rows, cols);
            int    minLen = (int)(maxDim * minLength);
            Random rng    = new Random(seed);

            // Scratch mask: black background (zeros), lines drawn at varying intensity
            Mat scratchMask = Mat.zeros(rows, cols, src.type());

            for (int i = 0; i < count; i++) {
                int x0 = rng.nextInt(cols);
                int y0 = rng.nextInt(rows);
                // Full random angle — paper scratches occur in any direction
                double angleDeg = rng.nextDouble() * 360.0;
                int    len      = minLen + rng.nextInt(Math.max(1, maxDim - minLen));
                double rad      = Math.toRadians(angleDeg);
                int    x1       = (int)(x0 + len * Math.cos(rad));
                int    y1       = (int)(y0 + len * Math.sin(rad));
                // Vary intensity ±30% per scratch so they don't all look identical
                int baseInt = (int)(opacity * 255);
                double vary = 0.7 + rng.nextDouble() * 0.6; // [0.7, 1.3]
                int    intV = Math.min(255, (int)(baseInt * vary));
                Imgproc.line(scratchMask,
                        new Point(x0, y0), new Point(x1, y1),
                        new Scalar(intV, intV, intV),
                        thickness, Imgproc.LINE_AA, 0);
            }

            // Add (light) or subtract (dark) the scratch mask — non-scratch pixels stay untouched
            Mat dst = new Mat();
            if (colorStr.equalsIgnoreCase("dark")) {
                Core.subtract(src, scratchMask, dst);
            } else {
                Core.add(src, scratchMask, dst);
            }
            return dst;
        }),

        // 13. Torn border – simulates worn/ripped paper edges with irregular boundaries and holes.
        //     For each of the four edges a random-walk profile defines how many pixels are eaten
        //     inward.  Gaussian-shaped hole notches punch deeper bites at random positions.
        //     Pixels outside the surviving paper area are filled with a solid background colour
        //     (default: white, matching a scanned-document look).
        //     params: maxDepth  (int,    55   — maximum erosion depth in pixels from each edge)
        //             roughness (double, 0.65 — random-walk step size; 0=smooth curve, 1=very jagged)
        //             holes     (int,    5    — number of deep notch holes per edge)
        //             holeFrac  (double, 0.70 — hole depth as fraction of maxDepth)
        //             bgR/bgG/bgB (int,  255  — background fill colour; default white)
        //             seed      (int,   42    — random seed; same seed = reproducible pattern)
        Map.entry("tornborder", (src, params) -> {
            int    maxDepth  = ((Number) params.getOrDefault("maxDepth",   55  )).intValue();
            double roughness = ((Number) params.getOrDefault("roughness",  0.65)).doubleValue();
            int    holes     = ((Number) params.getOrDefault("holes",       5  )).intValue();
            double holeFrac  = ((Number) params.getOrDefault("holeFrac",   0.70)).doubleValue();
            int    bgRed     = ((Number) params.getOrDefault("bgR",       255  )).intValue();
            int    bgGreen   = ((Number) params.getOrDefault("bgG",       255  )).intValue();
            int    bgBlue    = ((Number) params.getOrDefault("bgB",       255  )).intValue();
            int    seed      = ((Number) params.getOrDefault("seed",       42  )).intValue();

            int rows = src.rows();
            int cols = src.cols();
            int ch   = src.channels(); // 3 for BGR
            Random rng = (seed < 0) ? new Random() : new Random(seed);

            // Build one torn-edge profile per side; seed is advanced implicitly by rng state
            int[] topProfile   = buildTornProfile(cols, maxDepth, roughness, rng);
            punchHoles(topProfile,   holes, holeFrac, maxDepth, rng);
            int[] btmProfile   = buildTornProfile(cols, maxDepth, roughness, rng);
            punchHoles(btmProfile,   holes, holeFrac, maxDepth, rng);
            int[] leftProfile  = buildTornProfile(rows, maxDepth, roughness, rng);
            punchHoles(leftProfile,  holes, holeFrac, maxDepth, rng);
            int[] rightProfile = buildTornProfile(rows, maxDepth, roughness, rng);
            punchHoles(rightProfile, holes, holeFrac, maxDepth, rng);

            Mat    dst    = src.clone();
            byte[] rowBuf = new byte[cols * ch];
            byte   bB     = (byte) bgBlue;
            byte   bG     = (byte) bgGreen;
            byte   bR     = (byte) bgRed;

            for (int r = 0; r < rows; r++) {
                dst.get(r, 0, rowBuf);
                boolean rowChanged = false;
                for (int c = 0; c < cols; c++) {
                    if (r < topProfile[c]
                            || r >= rows - btmProfile[c]
                            || c < leftProfile[r]
                            || c >= cols - rightProfile[r]) {
                        rowBuf[c * ch    ] = bB;
                        rowBuf[c * ch + 1] = bG;
                        rowBuf[c * ch + 2] = bR;
                        rowChanged = true;
                    }
                }
                if (rowChanged) dst.put(r, 0, rowBuf);
            }
            return dst;
        }),

        // 14. Worn edge – simulates aged paper edges with a soft GRADIENT color overlay.
        //     Unlike tornborder (which hard-cuts to a white background), wornedge keeps all
        //     pixels but blends them toward a warm amber/brown colour that grows stronger
        //     as you move toward the actual image edge.  The inner boundary of the effect
        //     zone is defined by the same multi-scale random-walk as tornborder, producing
        //     an organic irregular shape — but the transition is smooth (quadratic falloff),
        //     matching the physical look of paper edges darkened by moisture and oxidation.
        //
        //     Algorithm:
        //       1. Build four independent random-walk profiles — one per edge — defining
        //          where the colour effect begins to appear (inner boundary of worn zone).
        //       2. For each pixel compute its penetration depth into each worn zone.
        //          A pixel is "inside" a zone when it is between the profile line and the
        //          actual image edge; depth = distance from the profile line toward the edge.
        //       3. weight = strength * (maxPenetration / depth)^2  — quadratic so the
        //          darkening builds very slowly near the boundary and strongly at the edge.
        //       4. Blend: dst = src * (1 - weight) + edgeColor * weight
        //          Non-text pixels are progressively coloured; interior remains untouched.
        //
        //     params: depth     (int,    55   — max colour effect zone depth in px per edge)
        //             roughness (double, 0.45 — boundary irregularity; 0=smooth, 1=very jagged)
        //             holes     (int,    3    — extra deep stain patches per edge)
        //             holeFrac  (double, 0.45 — stain-patch depth as fraction of depth)
        //             colorR/G/B (int, 160/110/60 — edge tint colour in RGB; default warm amber)
        //             strength  (double, 0.78 — max blend opacity at actual image edge)
        //             seed      (int,    42   — random seed)
        Map.entry("wornedge", (src, params) -> {
            int    depth     = ((Number) params.getOrDefault("depth",     55  )).intValue();
            double roughness = ((Number) params.getOrDefault("roughness",  0.45)).doubleValue();
            int    holes     = ((Number) params.getOrDefault("holes",       3  )).intValue();
            double holeFrac  = ((Number) params.getOrDefault("holeFrac",   0.45)).doubleValue();
            int    colorR    = ((Number) params.getOrDefault("colorR",    160  )).intValue();
            int    colorG    = ((Number) params.getOrDefault("colorG",    110  )).intValue();
            int    colorB    = ((Number) params.getOrDefault("colorB",     60  )).intValue();
            double strength  = ((Number) params.getOrDefault("strength",   0.78)).doubleValue();
            int    seed      = ((Number) params.getOrDefault("seed",       42  )).intValue();

            int rows = src.rows();
            int cols = src.cols();
            int ch   = src.channels();
            Random rng = (seed < 0) ? new Random() : new Random(seed);

            // Build organic boundary profiles for each edge
            int[] topProfile   = buildTornProfile(cols, depth, roughness, rng);
            punchHoles(topProfile,   holes, holeFrac, depth, rng);
            int[] btmProfile   = buildTornProfile(cols, depth, roughness, rng);
            punchHoles(btmProfile,   holes, holeFrac, depth, rng);
            int[] leftProfile  = buildTornProfile(rows, depth, roughness, rng);
            punchHoles(leftProfile,  holes, holeFrac, depth, rng);
            int[] rightProfile = buildTornProfile(rows, depth, roughness, rng);
            punchHoles(rightProfile, holes, holeFrac, depth, rng);

            // Target colour in BGR
            double tB = colorB;
            double tG = colorG;
            double tR = colorR;

            Mat    dst    = src.clone();
            byte[] rowBuf = new byte[cols * ch];

            for (int r = 0; r < rows; r++) {
                src.get(r, 0, rowBuf);
                boolean rowChanged = false;

                for (int c = 0; c < cols; c++) {
                    // Penetration depth into each worn zone (positive = inside zone)
                    double dTop   = topProfile[c]       - r;
                    double dBtm   = btmProfile[c]       - (rows - 1 - r);
                    double dLeft  = leftProfile[r]      - c;
                    double dRight = rightProfile[r]     - (cols - 1 - c);

                    double maxPen = Math.max(0, Math.max(Math.max(dTop, dBtm),
                                                         Math.max(dLeft, dRight)));
                    if (maxPen <= 0) continue; // safe interior — untouched

                    // Normalised 0→1, quadratic falloff: slow buildup near boundary,
                    // strong tint close to the actual image edge
                    double t = Math.min(1.0, maxPen / depth);
                    double weight = strength * t * t;

                    double srcB = rowBuf[c * ch    ] & 0xFF;
                    double srcG = rowBuf[c * ch + 1] & 0xFF;
                    double srcR2= rowBuf[c * ch + 2] & 0xFF;

                    rowBuf[c * ch    ] = (byte) Math.round(srcB  * (1 - weight) + tB * weight);
                    rowBuf[c * ch + 1] = (byte) Math.round(srcG  * (1 - weight) + tG * weight);
                    rowBuf[c * ch + 2] = (byte) Math.round(srcR2 * (1 - weight) + tR * weight);
                    rowChanged = true;
                }
                if (rowChanged) dst.put(r, 0, rowBuf);
            }
            return dst;
        }),

        // 15. Aging blotches — domain-warped fBm organic discoloration over the paper body.
        //
        //     Three missing mathematical tools from the full aging-realism set are all
        //     implemented here in a single operation:
        //
        //     ① Fractional Brownian Motion (Organic Blotches)
        //           fBm(x,y) = Σᵢ₌₀ⁿ⁻¹  persistence^i · Perlin2D(x·lac^i, y·lac^i)
        //         Multi-octave noise: each octave halves amplitude and doubles frequency.
        //         The accumulated sum produces organic, multi-scale blotch boundary
        //         geometry that matches real paper oxidation (foxing spots, humidity rings).
        //
        //     ② Perlin Gradient Noise (Soft Discoloration)
        //         Used as the base function in every fBm octave.  Ken Perlin's improved
        //         2002 gradient noise: smooth, band-limited, no grid artefacts.  Unlike
        //         the bilinear noise grid in 'vignette', this produces provably smooth
        //         C¹ transitions with genuine gradient coherence.
        //
        //     ③ Domain-Warped Gradient Fields (Stain Irregularity)
        //         The sampling point (x,y) is displaced BEFORE the main fBm query by a
        //         secondary independent fBm warp pass:
        //           wx = fBm(x + 1.7, y + 9.2)    // x-displacement field
        //           wy = fBm(x + 8.3, y + 2.8)    // y-displacement field
        //           result = fBm(x + W·wx, y + W·wy)
        //         This breaks the inherent axis-aligned symmetry of fBm into fjord-like
        //         "folded fjord" coastlines — the same technique used in the stain engine.
        //         Visual result: blotch boundaries curl, fold, and interlock exactly like
        //         dried liquid stains on paper rather than smooth Gaussian hills.
        //
        //     ④ Multiplicative Blending (Texture Interaction)
        //           dst_C = src_C · factorC    where factorC = 1 − weight·(1 − tintC/255)
        //         Multiplicative blend naturally preserves dark text (src_C = 0 → dst_C = 0)
        //         while proportionally warming and dimming bright paper pixels at blotch zones.
        //         Satisfying the physical constraint that ink on aged paper stays legible.
        //
        //     Pipeline position: apply AFTER floodfill (uniform parchment base), BEFORE
        //     pyrmeanshift.  pyrmeanshift will then cluster the fBm-blotched pixels into
        //     natural colour pockets, and the subsequent bilateral×3 will softly halo the
        //     blotch edges alongside the ink halos — compounding all effects naturally.
        //
        //     params:
        //       octaves      (int,    5    — fBm octave count; 4–6 is realistic range)
        //       persistence  (double, 0.50 — amplitude scaling per octave; 0.5 = classic)
        //       lacunarity   (double, 2.0  — frequency multiplier per octave; 2.0 = classic)
        //       scale        (double, 0.003— base spatial frequency; smaller → larger blotches)
        //       warpStrength (double, 0.80 — domain-warp displacement amplitude; 0=no warp)
        //       intensity    (double, 0.28 — max tint weight at blotch centre [0,1])
        //       warmR        (int,    162  — blotch tint red component in RGB)
        //       warmG        (int,    118  — blotch tint green component in RGB)
        //       warmB        (int,     68  — blotch tint blue component in RGB)
        //       seed         (int,    -1   — -1=random each call, ≥0=reproducible)
        Map.entry("agingblotches", (src, params) -> {
            int    octaves      = ((Number) params.getOrDefault("octaves",      5   )).intValue();
            double persistence  = ((Number) params.getOrDefault("persistence",  0.50)).doubleValue();
            double lacunarity   = ((Number) params.getOrDefault("lacunarity",   2.0 )).doubleValue();
            double scale        = ((Number) params.getOrDefault("scale",        0.003)).doubleValue();
            double warpStrength = ((Number) params.getOrDefault("warpStrength", 0.80)).doubleValue();
            double intensity    = ((Number) params.getOrDefault("intensity",    0.28)).doubleValue();
            int    warmR        = ((Number) params.getOrDefault("warmR",       162  )).intValue();
            int    warmG        = ((Number) params.getOrDefault("warmG",       118  )).intValue();
            int    warmB        = ((Number) params.getOrDefault("warmB",        68  )).intValue();
            int    seed         = ((Number) params.getOrDefault("seed",         -1  )).intValue();

            int    rows = src.rows();
            int    cols = src.cols();
            int    ch   = src.channels();
            Random rng  = (seed < 0) ? new Random() : new Random(seed);

            // Two independent Perlin permutation tables ensure the warp fBm
            // (perm2) is statistically decorrelated from the blotch fBm (perm1).
            int[] perm1 = buildPermTable(rng);   // drives main blotch fBm
            int[] perm2 = buildPermTable(rng);   // drives domain-warp fBm

            // Pre-compute tint factors (normalised to [0,1])
            double tR = warmR / 255.0;
            double tG = warmG / 255.0;
            double tB = warmB / 255.0;

            Mat    dst    = src.clone();
            byte[] rowBuf = new byte[cols * ch];

            for (int r = 0; r < rows; r++) {
                src.get(r, 0, rowBuf);
                boolean changed = false;

                for (int c = 0; c < cols; c++) {
                    double nx = c * scale;
                    double ny = r * scale;

                    // ── Domain-warp pass ──────────────────────────────────────
                    // Two independent fBm calls at phase-shifted coordinates give
                    // two scalar displacement fields wx and wy.  These warp the
                    // sampling position before the main blotch query.
                    double wx  = fbm(nx + 1.7, ny + 9.2, octaves, persistence, lacunarity, perm2);
                    double wy  = fbm(nx + 8.3, ny + 2.8, octaves, persistence, lacunarity, perm2);

                    // ── Main blotch fBm at warped position ───────────────────
                    double raw = fbm(nx + warpStrength * wx,
                                     ny + warpStrength * wy,
                                     octaves, persistence, lacunarity, perm1);

                    // Only the positive lobe of fBm creates blotches.
                    // Negative values leave the pixel untouched (clean parchment zones).
                    if (raw <= 0.0) continue;

                    // Map fBm value → blending weight ∈ [0, intensity]
                    // Factor 2.5 compensates for the half-space truncation so
                    // the full [0,1] weight range is still reachable.
                    double weight = Math.min(1.0, raw * intensity * 2.5);

                    // ── Multiplicative warm blend ─────────────────────────────
                    // Per-channel factor:  factorC = 1 − weight·(1 − tintC)
                    //   weight=0 → factor=1.0 → pixel unchanged
                    //   weight=1 → factor=tintC → pixel = src · tintC  (darkened + warm)
                    // Black text (src=0) → 0 × anything = 0  → completely unaffected.
                    double fR = 1.0 - weight * (1.0 - tR);
                    double fG = 1.0 - weight * (1.0 - tG);
                    double fB = 1.0 - weight * (1.0 - tB);

                    int    base = c * ch;
                    double b    = rowBuf[base    ] & 0xFF;
                    double g    = rowBuf[base + 1] & 0xFF;
                    double rv   = rowBuf[base + 2] & 0xFF;

                    rowBuf[base    ] = clampByte((int) Math.round(b  * fB));
                    rowBuf[base + 1] = clampByte((int) Math.round(g  * fG));
                    rowBuf[base + 2] = clampByte((int) Math.round(rv * fR));
                    changed = true;
                }
                if (changed) dst.put(r, 0, rowBuf);
            }
            return dst;
        })
    );

    public Mat applyFilter(String filterName, Mat input, Map<String, Object> params) {
        return filterRegistry.getOrDefault(filterName.toLowerCase(), (src, p) -> src)
                             .apply(input, params);
    }

    // -----------------------------------------------------------------------
    // Perlin gradient noise + fBm helpers (used by agingblotches)
    // -----------------------------------------------------------------------

    /**
     * Fractional Brownian Motion — sum of {@code octaves} Perlin noise layers.
     * Each octave multiplies frequency by {@code lacunarity} and amplitude by
     * {@code persistence}.  Result is normalised by the sum of all amplitudes so
     * the output remains in approximately [−1, 1] regardless of octave count.
     *
     *   fBm(x,y) = (Σᵢ persistence^i · Perlin2D(x·lac^i, y·lac^i)) / (Σᵢ persistence^i)
     */
    private static double fbm(double x, double y,
                               int octaves, double persistence, double lacunarity,
                               int[] perm) {
        double value     = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double maxVal    = 0.0;
        for (int i = 0; i < octaves; i++) {
            value     += amplitude * perlin2D(x * frequency, y * frequency, perm);
            maxVal    += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }
        return (maxVal > 0.0) ? value / maxVal : 0.0;
    }

    /**
     * Ken Perlin's Improved 2-D Noise (2002 reference implementation).
     * Returns a value in approximately [−1, 1].
     * Gradient table uses 4 diagonal unit vectors (hash & 3) — each cell has a
     * unique gradient that produces smooth, band-limited variation without any
     * visible grid-aligned artefacts.
     */
    private static double perlin2D(double x, double y, int[] p) {
        int    xi = ((int) Math.floor(x)) & 255;
        int    yi = ((int) Math.floor(y)) & 255;
        double xf = x - Math.floor(x);
        double yf = y - Math.floor(y);
        double u  = perlinFade(xf);
        double v  = perlinFade(yf);
        int    aa = p[p[xi    ] + yi    ];
        int    ab = p[p[xi    ] + yi + 1];
        int    ba = p[p[xi + 1] + yi    ];
        int    bb = p[p[xi + 1] + yi + 1];
        return perlinLerp(v,
                perlinLerp(u, perlinGrad2(aa, xf,     yf    ), perlinGrad2(ba, xf - 1, yf    )),
                perlinLerp(u, perlinGrad2(ab, xf,     yf - 1), perlinGrad2(bb, xf - 1, yf - 1)));
    }

    /** Perlin smooth-step fade curve: f(t) = 6t⁵ − 15t⁴ + 10t³ (zero first and second derivative at 0/1) */
    private static double perlinFade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double perlinLerp(double t, double a, double b) { return a + t * (b - a); }
    private static double perlinGrad2(int hash, double x, double y) {
        switch (hash & 3) {
            case 0:  return  x + y;
            case 1:  return -x + y;
            case 2:  return  x - y;
            default: return -x - y;
        }
    }

    /**
     * Fisher-Yates shuffle of [0..255] doubled to [0..511] for wrap-free lookup.
     * Accepts any Random instance so both perm1 and perm2 draw from the same
     * seeded RNG in sequence — guaranteeing independence only by the state advance.
     */
    private static int[] buildPermTable(Random rng) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = p[i]; p[i] = p[j]; p[j] = t;
        }
        int[] perm = new int[512];
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
        return perm;
    }

    private static byte clampByte(int v) {
        return (byte) Math.max(0, Math.min(255, v));
    }

    // -----------------------------------------------------------------------
    // Torn-border helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a multi-scale torn-edge depth profile of length {@code len}.
     *
     * Three independent noise levels are combined:
     *   Level 1 — coarse skeleton   (8 ctrl points, full-range swing): sets the
     *             overall shape — some areas barely grazed, others deeply cut.
     *   Level 2 — medium bumps      (35 ctrl points, ±22 % of maxDepth): adds
     *             cm-scale irregularity on top of the skeleton.
     *   Level 3 — fine fiber noise  (per-pixel damped random walk): gives the
     *             pixel-level jaggedness of actual torn paper fibres.
     * Values are clamped to [2, maxDepth].
     */
    private static int[] buildTornProfile(int len, int maxDepth, double roughness, Random rng) {
        // Level 1: coarse skeleton — wide swing from near-zero to near-max depth
        int coarseN = 8;
        double[] coarse = new double[coarseN + 1];
        coarse[0] = maxDepth * (0.10 + 0.50 * rng.nextDouble());
        for (int i = 1; i <= coarseN; i++) {
            double step = (rng.nextDouble() - 0.44) * roughness * maxDepth * 1.10;
            coarse[i] = Math.max(maxDepth * 0.03, Math.min(maxDepth * 0.94, coarse[i - 1] + step));
        }

        // Level 2: medium bumps — independent walk, clamped to ±22 % of maxDepth
        int medN = 35;
        double[] med = new double[medN + 1];
        med[0] = 0;
        for (int i = 1; i <= medN; i++) {
            double step = (rng.nextDouble() - 0.5) * roughness * maxDepth * 0.44;
            med[i] = Math.max(-maxDepth * 0.22, Math.min(maxDepth * 0.22, med[i - 1] + step));
        }

        // Level 3: per-pixel fibre noise — self-correlated (momentum) random walk
        double fineAmp = maxDepth * 0.11 * roughness;
        double[] fine  = new double[len];
        double   vel   = 0;
        for (int i = 0; i < len; i++) {
            vel    = vel * 0.60 + rng.nextGaussian() * 0.40;
            fine[i] = vel * fineAmp;
        }

        // Combine: coarse spine + medium bumps + fine fibre texture
        int[] profile = new int[len];
        for (int i = 0; i < len; i++) {
            double tc = (double) i / Math.max(1, len - 1) * coarseN;
            int    c0 = (int) tc,  c1 = Math.min(c0 + 1, coarseN);
            double cv = coarse[c0] * (1 - (tc - c0)) + coarse[c1] * (tc - c0);

            double tm = (double) i / Math.max(1, len - 1) * medN;
            int    m0 = (int) tm,  m1 = Math.min(m0 + 1, medN);
            double mv = med[m0]    * (1 - (tm - m0)) + med[m1]    * (tm - m0);

            profile[i] = Math.max(2, Math.min(maxDepth, (int)(cv + mv + fine[i])));
        }
        return profile;
    }

    /**
     * Punches {@code holeCount} sharp, asymmetric notches into an existing profile.
     * Uses a power-curve falloff with independent exponents for each side — producing
     * angular, triangular shapes rather than smooth Gaussian bells, much closer to
     * the look of actual torn paper fibres.
     * Exponent < 1 → wide flat base (gentle slope). Exponent > 1 → narrow sharp spike.
     */
    private static void punchHoles(int[] profile, int holeCount, double holeFrac,
                                   int maxDepth, Random rng) {
        int len = profile.length;
        for (int h = 0; h < holeCount; h++) {
            // Keep hole centres away from the very edge pixels
            int    center  = (int)(len * (0.04 + 0.92 * rng.nextDouble()));
            double depth   = holeFrac * maxDepth * (0.50 + 0.50 * rng.nextDouble());
            // Mix of narrow-sharp holes (halfW ≈ 30 % of maxDepth) and
            // wider medium holes (halfW up to 120 % of maxDepth)
            int    halfW   = (int)(maxDepth * (0.30 + 0.90 * rng.nextDouble()));
            // Independent power exponents per side: asymmetry makes each notch unique
            double leftExp  = 0.50 + rng.nextDouble() * 1.20;  // [0.50, 1.70]
            double rightExp = 0.50 + rng.nextDouble() * 1.20;
            int    from    = Math.max(0,   center - halfW);
            int    to      = Math.min(len, center + halfW);
            for (int i = from; i < to; i++) {
                double normDist = (double)(i - center) / halfW; // −1 .. +1
                double exp      = normDist < 0 ? leftExp : rightExp;
                double factor   = Math.max(0.0, 1.0 - Math.pow(Math.abs(normDist), exp));
                profile[i] = (int) Math.min(maxDepth, profile[i] + depth * factor);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Named kernels for filter2D
    // -----------------------------------------------------------------------
    private static Mat buildKernel(String type) {
        Mat kernel = new Mat(3, 3, CvType.CV_64F);
        switch (type.toLowerCase()) {
            case "sharpen":
                // Sharpens edges while keeping centre weight positive
                kernel.put(0, 0,  0.0, -1.0,  0.0,
                                 -1.0,  5.0, -1.0,
                                  0.0, -1.0,  0.0);
                break;
            case "emboss":
                // Emboss / relief effect
                kernel.put(0, 0, -2.0, -1.0,  0.0,
                                 -1.0,  1.0,  1.0,
                                  0.0,  1.0,  2.0);
                break;
            case "edgedetect":
                // Laplacian-style edge detector
                kernel.put(0, 0, -1.0, -1.0, -1.0,
                                 -1.0,  8.0, -1.0,
                                 -1.0, -1.0, -1.0);
                break;
            default: // identity – no-op, useful for testing
                kernel.put(0, 0,  0.0,  0.0,  0.0,
                                  0.0,  1.0,  0.0,
                                  0.0,  0.0,  0.0);
        }
        return kernel;
    }
}
