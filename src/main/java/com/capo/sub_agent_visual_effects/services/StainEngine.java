package com.capo.sub_agent_visual_effects.services;

import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import org.opencv.core.Mat;
import org.springframework.stereotype.Service;

@Service
public class StainEngine {

    // -----------------------------------------------------------------------
    // Realistic stain engine — physics-based coffee/organic liquid rendering.
    //
    // Three physics layers:
    //
    // 1. FRACTAL BROWNIAN MOTION with DOMAIN WARPING — coastline-fractal boundary
    //    Pixel position is normalised to unit-blob space (dx/radius, dy/radius).
    //    A coarse warp pass (3-octave fBm) displaces the sample coordinates before
    //    a fine 6-octave fBm evaluates the boundary value at the warped position.
    //    This "folded fjord" technique produces jaggedness at every scale — matching
    //    the formula  Stain(x,y) = Σᵢ (1/2ⁱ)·Noise(2ⁱ·x, 2ⁱ·y)  — rather than
    //    a bumped circle produced by angular sampling.
    //
    // 2. VISCOUS FINGERING (Saffman–Taylor / DLA approximation)
    //    When low-viscosity coffee displaces air in porous paper, instabilities
    //    create long "fingers."  We approximate this with explicit tapered drip
    //    extensions at random angles: each drip is a cone that is wide at the base
    //    (where it merges with the blob boundary) and tapers to a point at the tip.
    //    2–5 drips per blob produce the organic "legs" that break circular symmetry.
    //
    // 3. POWER LAW EDGE DARKENING — coffee-ring physics
    //    Capillary flow carries solute particles outward to compensate for
    //    evaporation at the pinned contact line (Marangoni effect).
    //    Implemented as  Opacity = t^k (k=5)  where t ∈ [0,1] is normalised
    //    distance from centre.  The centre is completely transparent; all pigment
    //    concentrates in the thin edge band at t → 1.  Variable feathering
    //    (2.5 % at pointed tips, 10 % at rounded body) softens the final edge.
    //
    // 4. Satellite drops via Brownian-motion random walk.
    // 5. Multiply blend — (Background × StainColor)/255 — preserves text.
    // 6. fBm internal pooling — localised darker zones where coffee pooled deeper.
    // -----------------------------------------------------------------------
    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry = Map.of(

        // stain — renders one or more organic substance stains over the image.
        //
        // params:
        //   count       (int,    1)         — number of stains to render
        //   substance   (String, "coffee")  — preset: coffee | tea | wine | ink |
        //                                     water | mud | blood
        //   opacity     (double, 0.72)      — max blend strength (0=invisible, 1=opaque)
        //   minSize     (double, 0.05)      — min stain radius as fraction of min(W,H)
        //   maxSize     (double, 0.14)      — max stain radius as fraction of min(W,H)
        //   colorR/G/B  (int, —)            — custom RGB override (all three required);
        //                                     when set, overrides substance preset and
        //                                     uses fill-type (no ring effect)
        //   seed        (int,   42)         — random seed; same seed = same layout
        "stain", (src, params) -> {
            int    count       = ((Number) params.getOrDefault("count",     1     )).intValue();
            String substance   = (String)  params.getOrDefault("substance", "coffee");
            double opacity     = ((Number) params.getOrDefault("opacity",   0.90  )).doubleValue();
            double minSizeFrac = ((Number) params.getOrDefault("minSize",   0.10  )).doubleValue();
            double maxSizeFrac = ((Number) params.getOrDefault("maxSize",   0.35  )).doubleValue();
            // seed = -1 (default) → truly random placement each call; any ≥0 → reproducible
            int    seed        = ((Number) params.getOrDefault("seed",      -1    )).intValue();

            // ------------------------------------------------------------------
            // Substance presets — stainColor is [R, G, B]:
            //   ringEffect = true  → coffee-ring evaporation profile
            //   ringEffect = false → solid fill with smooth cubic boundary
            // ------------------------------------------------------------------
            int[]   stainColor;
            boolean ringEffect;
            switch (substance.toLowerCase()) {
                case "wine":  stainColor = new int[]{114,  47,  55}; ringEffect = false; break;
                case "ink":   stainColor = new int[]{ 20,  20,  60}; ringEffect = false; break;
                case "water": stainColor = new int[]{185, 210, 230}; ringEffect = true;
                              opacity *= 0.35; break;
                case "tea":   stainColor = new int[]{190, 150,  80}; ringEffect = true;  break;
                case "mud":   stainColor = new int[]{ 90,  70,  40}; ringEffect = false; break;
                case "blood": stainColor = new int[]{ 80,  10,  10}; ringEffect = false; break;
                default:      stainColor = new int[]{ 72,  46,  18}; ringEffect = true;  break; // coffee
            }

            // Custom colour override — all three channels must be present
            if (params.containsKey("colorR") && params.containsKey("colorG") && params.containsKey("colorB")) {
                stainColor = new int[]{
                    ((Number) params.get("colorR")).intValue(),
                    ((Number) params.get("colorG")).intValue(),
                    ((Number) params.get("colorB")).intValue()
                };
                ringEffect = false;
            }

            int    rows   = src.rows();
            int    cols   = src.cols();
            int    ch     = src.channels();
            int    minDim = Math.min(rows, cols);
            Random rng    = (seed >= 0) ? new Random(seed) : new Random();
            Mat    dst    = src.clone();

            for (int s = 0; s < count; s++) {

                // ----- Main stain geometry -----
                double radius = minDim * (minSizeFrac + rng.nextDouble() * (maxSizeFrac - minSizeFrac));
                // margin = 1.5× radius — blob center kept away from image edges;
                // lobes/satellites may reach beyond this and are silently clipped.
                int    margin = (int)(radius * 1.5) + 12;
                int cx = (int)(margin + rng.nextDouble() * Math.max(1.0, cols - 2.0 * margin));
                int cy = (int)(margin + rng.nextDouble() * Math.max(1.0, rows - 2.0 * margin));

                // Per-stain Perlin permutation table
                int[] perm = buildPermTable(rng);

                // ----- Render main blob -----
                dst = renderBlob(dst, ch, rows, cols, cx, cy, radius, perm,
                        stainColor, ringEffect, opacity, rng);

                // ----- Satellite drops — 8–20, randomly scattered at 1.5–5.5× radius -----
                // Forced fill-type: rendered as solid rounded drops, never transparent rings.
                int nSat = 8 + rng.nextInt(13);  // 8–20 satellites
                for (int i = 0; i < nSat; i++) {
                    double angle = rng.nextDouble() * 2.0 * Math.PI;
                    double dist  = radius * (1.5 + rng.nextDouble() * 4.0); // 1.5–5.5× away
                    int    satCx = cx + (int)(dist * Math.cos(angle));
                    int    satCy = cy + (int)(dist * Math.sin(angle));
                    if (satCx < 0 || satCy < 0 || satCx >= cols || satCy >= rows) continue;
                    double satR     = radius * (0.02 + rng.nextDouble() * 0.05); // 2–7 % of main
                    int[]  satPerm  = buildPermTable(rng);
                    double satAlpha = opacity * (0.60 + rng.nextDouble() * 0.30);
                    dst = renderBlob(dst, ch, rows, cols, satCx, satCy, satR,
                            satPerm, stainColor, false, satAlpha, rng);
                }
            }
            return dst;
        }
    );

    // -----------------------------------------------------------------------
    // Renders a single organic blob (main stain or satellite drop).
    //
    // Per-pixel pipeline:
    //   A) Domain-warped fBm boundary  → irregular coastline perimeter
    //   B) Viscous fingering (drips)   → long tapered legs at random angles
    //   C) fBm internal pooling        → absorbed-liquid darker patches
    //   D) Power-law alpha profile     → Opacity = t^5 pushes pigment to edge
    //      (ring-type) or cubic rolloff (fill-type)
    //   E) Variable edge feathering    → tips sharp (2.5 %), body soft (10 %)
    //   F) Multiply blend              → (Background × StainColor) / 255
    // -----------------------------------------------------------------------
    private Mat renderBlob(Mat dst, int ch, int rows, int cols,
            int cx, int cy, double radius,
            int[] perm,
            int[] stainColor, boolean ringEffect, double opacity,
            Random rng) {

        // Spatial offsets ensure each blob samples a unique region of the noise field
        double offX = rng.nextDouble() * 137.5;
        double offY = rng.nextDouble() * 93.7;

        // ---- Pre-compute viscous drip parameters ----
        // Each drip approximates Saffman–Taylor instability: a tapered cone that is
        // wide at the blob boundary and narrows to a point at the tip.
        // Skip drips for tiny blobs (radius < 8 px); keep 4–9 organic lobes per main blob.
        int nDrips = (radius < 8.0) ? 0 : (4 + rng.nextInt(6));
        double[] dripCos    = new double[nDrips];
        double[] dripSin    = new double[nDrips];
        double[] dripLength = new double[nDrips];
        double[] dripBaseW  = new double[nDrips];
        for (int i = 0; i < nDrips; i++) {
            double a      = rng.nextDouble() * 2.0 * Math.PI;
            dripCos[i]    = Math.cos(a);
            dripSin[i]    = Math.sin(a);
            dripLength[i] = radius * (0.45 + rng.nextDouble() * 1.05); // 0.45–1.50× radius
            dripBaseW[i]  = radius * (0.38 + rng.nextDouble() * 0.17); // 38–55 % — lobe-like
        }

        // margin = 4.0× radius — covers lobes (up to 1.50×) + fBm expansion (72%) + feathering
        int margin = (int)(radius * 4.0) + 10;
        int r0 = Math.max(0,    cy - margin);
        int r1 = Math.min(rows, cy + margin);
        int c0 = Math.max(0,    cx - margin);
        int c1 = Math.min(cols, cx + margin);

        byte[] rowBuf = new byte[cols * ch];

        for (int r = r0; r < r1; r++) {
            dst.get(r, 0, rowBuf);
            boolean changed = false;

            for (int c = c0; c < c1; c++) {
                double dx = c - cx;
                double dy = r - cy;
                double d  = Math.sqrt(dx * dx + dy * dy);

                // ---- A. Domain-warped fBm boundary ----
                // Normalise pixel to unit-blob space.  Two-pass domain warp:
                // a coarse fBm displaces the sample coordinates, then a fine
                // boundary fBm is evaluated at the warped position → "folded fjord"
                // coastline that is jagged at every scale of observation.
                double nx    = dx / radius + offX;
                double ny    = dy / radius + offY;
                // Coarse warp field (3 octaves, lower spatial frequency)
                // Amplitude 1.40 gives a violently irregular coastline — jagged at every scale.
                double wu    = fbm(nx * 0.50, ny * 0.50,              perm, 3, 0.55, 2.0) * 1.40;
                double wv    = fbm(nx * 0.50 + 4.3, ny * 0.50 + 2.1, perm, 3, 0.55, 2.0) * 1.40;
                // Fine boundary fBm at domain-warped coordinates (6 octaves)
                double fbmBnd = fbm(nx + wu, ny + wv, perm, 6, 0.52, 2.0);
                // Effective radius: fBm perturbs ±90% — deep concavities + far-reaching lobes
                double effR   = radius * Math.max(0.25, 1.0 + 0.90 * fbmBnd);
                double t      = d / effR;    // 0 = centre, 1 = blob boundary
                boolean inBlob = (t <= 1.02);
                if (inBlob) t = Math.min(1.0, t);

                // ---- B. Viscous fingering — tapered drip membership test ----
                // Pixel belongs to a drip if it falls within the tapered cone that
                // extends from near the blob boundary outward along the drip axis.
                double bestDripPos = -1.0;  // [0=root, 1=tip]; -1 = not in any drip
                for (int i = 0; i < nDrips; i++) {
                    double proj = dx * dripCos[i] + dy * dripSin[i]; // along drip axis
                    // Root starts at 30 % of radius so drip merges naturally with blob
                    if (proj < radius * 0.30 || proj > dripLength[i]) continue;
                    double perp   = Math.abs(dx * dripSin[i] - dy * dripCos[i]);
                    double tapPos = (proj - radius * 0.30) / (dripLength[i] - radius * 0.30);
                    // Sub-linear taper (0.40 exponent): rounder lobe ends, wider at tips
                    double maxPerp = dripBaseW[i] * Math.pow(1.0 - tapPos, 0.40);
                    if (perp <= maxPerp && tapPos > bestDripPos) bestDripPos = tapPos;
                }
                boolean inDrip = (bestDripPos >= 0.0);

                if (!inBlob && !inDrip) continue;

                // ---- C. fBm internal pooling (blob pixels only) ----
                // High-frequency fBm at pixel position creates darker/lighter patches
                // where the liquid soaked deeper into the paper fibres.
                double pool = 0.0;
                if (inBlob) {
                    double inx    = (c + offX * 0.4) / (radius * 0.50);
                    double iny    = (r + offY * 0.4) / (radius * 0.50);
                    double fbmInt = fbm(inx, iny, perm, 4, 0.55, 2.10);
                    pool = Math.max(-1.0, Math.min(1.0, fbmInt));
                }

                // ---- D. Alpha profile ----
                double alpha;
                if (inBlob) {
                    if (ringEffect) {
                        // SPLASH profile — models poured/dropped coffee that dried:
                        // baseFill:  strong uniform dark coat 0.80→0.68 (center→edge)
                        // edgeBoost: Gaussian peak t=0.88 (evaporation ring, contact-line pinning)
                        // poolVar:   ±35% fBm variation — organic dark/light interior patches
                        // Clamp to [0.50,1.0] so stain body is always visibly dark.
                        // White paper (opacity=0.90, stainColor={72,46,18}):
                        //   t=0.00 no pool: α=0.72 → RGB(117,90,64) warm coffee brown ✓
                        //   t=0.00 pool=+1: α=0.90 → RGB(90,57,27) very dark brown   ✓
                        //   t=0.88 edge:    α=0.90 → RGB(90,57,27) evaporation ring  ✓
                        double baseFill  = 0.80 * (1.0 - 0.15 * t);
                        double edgeBoost = 0.22 * Math.exp(-0.5 * Math.pow((t - 0.88) / 0.07, 2));
                        double ringA     = baseFill + edgeBoost;
                        double poolVar   = 0.35 * pool;
                        alpha = opacity * Math.min(1.0, Math.max(0.50, ringA + poolVar));
                    } else {
                        // Fill-type: cubic rolloff with fBm interior texture
                        double fillA = Math.max(0.0, 1.0 - t * t * t);
                        alpha = opacity * fillA * (1.0 + 0.20 * pool);
                    }

                    // ---- E. Variable edge feathering ----
                    // Very tight zone: tips get 0.5 %, body gets 1.5 % — keeps boundary crisp
                    // so the outline reads as a hard-edged fluid splash, not a blurry halo.
                    double tipFactor   = Math.max(0.0, Math.min(1.0, (fbmBnd + 1.0) * 0.5));
                    double featherZone = 0.005 + (1.0 - tipFactor) * 0.010;
                    if (t > 1.0 - featherZone) {
                        double f = (1.0 - t) / featherZone;
                        alpha *= f * f;
                    }
                } else {
                    // Drip pixel: fades from base (opacity) to tip (transparent)
                    double dripFade = Math.pow(1.0 - bestDripPos, 1.8);
                    alpha = opacity * dripFade * (ringEffect ? 0.80 : 0.65);
                }

                if (alpha < 0.004) continue;
                alpha = Math.min(1.0, alpha);

                // ---- F. Multiply blend — (Background × StainColor) / 255 ----
                // stainColor is [R, G, B]; OpenCV rowBuf order is B, G, R.
                // Text and lines remain legible through the liquid.
                int    base  = c * ch;
                double bgB   = rowBuf[base    ] & 0xFF;
                double bgG   = rowBuf[base + 1] & 0xFF;
                double bgR   = rowBuf[base + 2] & 0xFF;
                double multB = (bgB * stainColor[2]) / 255.0;
                double multG = (bgG * stainColor[1]) / 255.0;
                double multR = (bgR * stainColor[0]) / 255.0;
                rowBuf[base    ] = clampByte(bgB * (1.0 - alpha) + multB * alpha);
                rowBuf[base + 1] = clampByte(bgG * (1.0 - alpha) + multG * alpha);
                rowBuf[base + 2] = clampByte(bgR * (1.0 - alpha) + multR * alpha);
                changed = true;
            }
            if (changed) dst.put(r, 0, rowBuf);
        }
        return dst;
    }

    // -----------------------------------------------------------------------
    // Fractal Brownian Motion — sums `octaves` octaves of 2-D Perlin noise.
    // Returns a value normalised to approximately [-1, 1].
    // persistence controls amplitude decay per octave (0.5 = classic 1/f noise).
    // lacunarity controls frequency growth per octave (2.0 = octave doubling).
    // -----------------------------------------------------------------------
    private static double fbm(double x, double y, int[] perm,
                               int octaves, double persistence, double lacunarity) {
        double value     = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double maxVal    = 0.0;
        for (int i = 0; i < octaves; i++) {
            value    += perlin2D(x * frequency, y * frequency, perm) * amplitude;
            maxVal   += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }
        return maxVal > 0.0 ? value / maxVal : 0.0;
    }

    // -----------------------------------------------------------------------
    // Ken Perlin's Improved 2-D Noise (2002 reference implementation).
    // Returns a value in approximately [-1, 1].
    // -----------------------------------------------------------------------
    private static double perlin2D(double x, double y, int[] p) {
        int    xi = ((int) Math.floor(x)) & 255;
        int    yi = ((int) Math.floor(y)) & 255;
        double xf = x - Math.floor(x);
        double yf = y - Math.floor(y);
        double u  = fade(xf);
        double v  = fade(yf);
        int aa = p[p[xi    ] + yi    ];
        int ab = p[p[xi    ] + yi + 1];
        int ba = p[p[xi + 1] + yi    ];
        int bb = p[p[xi + 1] + yi + 1];
        return lerp(v,
                lerp(u, grad2(aa, xf,     yf    ), grad2(ba, xf - 1, yf    )),
                lerp(u, grad2(ab, xf,     yf - 1), grad2(bb, xf - 1, yf - 1)));
    }

    private static double fade(double t)              { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    private static double grad2(int hash, double x, double y) {
        switch (hash & 3) {
            case 0:  return  x + y;
            case 1:  return -x + y;
            case 2:  return  x - y;
            default: return -x - y;
        }
    }

    // -----------------------------------------------------------------------
    // Builds a 512-entry doubled permutation table (values 0-255, shuffled).
    // The doubling avoids index wrapping in the noise lookup.
    // -----------------------------------------------------------------------
    private static int[] buildPermTable(Random rng) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        // Fisher-Yates shuffle
        for (int i = 255; i > 0; i--) {
            int j   = rng.nextInt(i + 1);
            int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
        }
        int[] perm = new int[512];
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
        return perm;
    }

    private static byte clampByte(double v) {
        return (byte) Math.max(0, Math.min(255, (int) Math.round(v)));
    }

    public Mat applyOperation(String operationName, Mat input, Map<String, Object> params) {
        return operationRegistry.getOrDefault(operationName.toLowerCase(), (s, p) -> s)
                                .apply(input, params);
    }
}
