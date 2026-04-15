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
        //                                     water | mud | blood | oil (=grease) |
        //                                     ketchup | honey | gel
        //   opacity     (double, 0.90)      — max blend strength (0=invisible, 1=opaque)
        //   minSize     (double, substance) — min stain radius as fraction of min(W,H);
        //                                     defaults to per-substance value if omitted
        //   maxSize     (double, substance) — max stain radius as fraction of min(W,H);
        //                                     defaults to per-substance value if omitted
        //   colorR/G/B  (int, —)            — custom RGB override (all three required);
        //                                     when set, overrides substance preset and
        //                                     uses fill-cubic alpha mode
        //   seed        (int,   -1)         — random seed; same seed = same layout
        "stain", (src, params) -> {
            int    count     = ((Number) params.getOrDefault("count",     1     )).intValue();
            String substance = (String)  params.getOrDefault("substance", "coffee");
            double opacity   = ((Number) params.getOrDefault("opacity",   0.90  )).doubleValue();
            // size defaults are substance-specific; caller may override with explicit params
            boolean hasMinSize = params.containsKey("minSize");
            boolean hasMaxSize = params.containsKey("maxSize");
            double minSizeFrac = hasMinSize ? ((Number) params.get("minSize")).doubleValue() : 0.0;
            double maxSizeFrac = hasMaxSize ? ((Number) params.get("maxSize")).doubleValue() : 0.0;
            // seed = -1 (default) → truly random placement each call; any ≥0 → reproducible
            int    seed        = ((Number) params.getOrDefault("seed",      -1    )).intValue();

            // ------------------------------------------------------------------
            // Substance profiles — per-fluid physics parameters.
            // ------------------------------------------------------------------
            SubstanceProfile profile = getProfile(substance);
            int[]  stainColor = profile.color;
            int    alphaMode  = profile.alphaMode;
            double warpAmp    = profile.warpAmp;
            double fbmPerturb = profile.fbmPerturb;
            opacity          *= profile.opacityMult;
            if (!hasMinSize) minSizeFrac = profile.defMinSize;
            if (!hasMaxSize) maxSizeFrac = profile.defMaxSize;

            // Custom colour override — all three channels must be present
            if (params.containsKey("colorR") && params.containsKey("colorG") && params.containsKey("colorB")) {
                stainColor = new int[]{
                    ((Number) params.get("colorR")).intValue(),
                    ((Number) params.get("colorG")).intValue(),
                    ((Number) params.get("colorB")).intValue()
                };
                alphaMode = 1; // fill-cubic for custom colours
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
                        stainColor, alphaMode, warpAmp, fbmPerturb, opacity, rng);

                // ----- Satellite drops — 8–20, randomly scattered at 1.5–5.5× radius -----
                // Rendered with the same substance profile (warpAmp, fbmPerturb, alphaMode).
                // satFrac controls size relative to main radius; floored at 3 px so even
                // tiny blood drops produce visible individual satellite specks.
                int nSat = 8 + rng.nextInt(13);  // 8–20 satellites
                for (int i = 0; i < nSat; i++) {
                    double angle = rng.nextDouble() * 2.0 * Math.PI;
                    double dist  = radius * (1.5 + rng.nextDouble() * 4.0); // 1.5–5.5× away
                    int    satCx = cx + (int)(dist * Math.cos(angle));
                    int    satCy = cy + (int)(dist * Math.sin(angle));
                    if (satCx < 0 || satCy < 0 || satCx >= cols || satCy >= rows) continue;
                    // Satellite size: substance fraction of main radius, floored at 3 px
                    double satR     = Math.max(3.0, radius * (profile.satFrac * 0.5
                                        + rng.nextDouble() * profile.satFrac * 0.5));
                    int[]  satPerm  = buildPermTable(rng);
                    double satAlpha = opacity * (0.60 + rng.nextDouble() * 0.30);
                    dst = renderBlob(dst, ch, rows, cols, satCx, satCy, satR,
                            satPerm, stainColor, profile.alphaMode, warpAmp, fbmPerturb, satAlpha, rng);
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
    //   D) Substance alpha profile     → mode 0: ring (coffee/tea), mode 1: fill-cubic
    //                                     (wine/ink/mud), mode 2: blood-center (dense core)
    //   E) Variable edge feathering    → tips sharp (0.5 %), body soft (1.5 %)
    //   F) Multiply blend              → (Background × StainColor) / 255
    // -----------------------------------------------------------------------
    private Mat renderBlob(Mat dst, int ch, int rows, int cols,
            int cx, int cy, double radius,
            int[] perm,
            int[] stainColor, int alphaMode, double warpAmp, double fbmPerturb,
            double opacity, Random rng) {

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
                // Coarse warp field (3 octaves, lower spatial frequency).
                // warpAmp controls boundary roughness: high (coffee ~1.40) = fjord coastline;
                // low (blood ~0.45) = smooth cohesive oval held by surface tension.
                double wu     = fbm(nx * 0.50, ny * 0.50,              perm, 3, 0.55, 2.0) * warpAmp;
                double wv     = fbm(nx * 0.50 + 4.3, ny * 0.50 + 2.1, perm, 3, 0.55, 2.0) * warpAmp;
                // Fine boundary fBm at domain-warped coordinates (6 octaves)
                double fbmBnd = fbm(nx + wu, ny + wv, perm, 6, 0.52, 2.0);
                // Effective radius: fbmPerturb controls ± excursion from nominal radius.
                double effR   = radius * Math.max(0.25, 1.0 + fbmPerturb * fbmBnd);
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
                    switch (alphaMode) {
                        case 0: {
                            // Ring (coffee/tea/water) — Marangoni evaporation ring.
                            // baseFill:  0.80→0.68 center→edge uniform coat
                            // edgeBoost: Gaussian peak at t=0.88 (dried contact-line ring)
                            // poolVar:   ±35% fBm variation for organic interior patches.
                            double baseFill  = 0.80 * (1.0 - 0.15 * t);
                            double edgeBoost = 0.22 * Math.exp(-0.5 * Math.pow((t - 0.88) / 0.07, 2));
                            double poolVar   = 0.35 * pool;
                            alpha = opacity * Math.min(1.0, Math.max(0.50, baseFill + edgeBoost + poolVar));
                            break;
                        }
                        case 2: {
                            // Blood-center — physics: red blood cells concentrate at the pool's
                            // deepest point giving maximum opacity at t=0; the spreading edge is
                            // thin and semi-translucent. A slight dried-ring boost at t≈0.90
                            // models the desiccated outer rim of a dried blood drop.
                            double centerFill = 0.90 * (1.0 - 0.30 * t * t);
                            double dryEdge    = 0.12 * Math.exp(-0.5 * Math.pow((t - 0.90) / 0.06, 2));
                            double poolVar    = 0.25 * pool;
                            alpha = opacity * Math.min(1.0, Math.max(0.25, centerFill + dryEdge + poolVar));
                            break;
                        }
                        default: {
                            // Fill-cubic (wine/ink/mud) — solid fill with smooth cubic rolloff.
                            double fillA = Math.max(0.0, 1.0 - t * t * t);
                            alpha = opacity * fillA * (1.0 + 0.20 * pool);
                        }
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
                    alpha = opacity * dripFade * (alphaMode == 0 ? 0.80 : 0.65);
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
    // Per-substance physics profile.
    //
    //   alphaMode   0 = ring  (coffee/tea/water) — pigment concentrates at edge
    //               1 = fill-cubic (wine/ink/mud) — uniform solid fill
    //               2 = blood-center — dense/opaque at centre, feathered edge
    //   warpAmp     domain-warp amplitude:
    //               high (≥1.20) = chaotic fjord coastline (low-viscosity fluids)
    //               low  (≤0.55) = smooth cohesive oval (high-surface-tension drops)
    //   fbmPerturb  fBm boundary perturbation fraction:
    //               high → jagged, deeply indented boundary
    //               low  → near-circular boundary
    //   opacityMult multiplied onto the caller-supplied opacity baseline
    //   defMinSize  default minSizeFrac when caller omits the 'minSize' param
    //   defMaxSize  default maxSizeFrac when caller omits the 'maxSize' param
    //   satFrac     satellite drop max-size as fraction of main radius
    //               (floored at 3 px so even tiny blood drops generate visible specks)
    // -----------------------------------------------------------------------
    private static final class SubstanceProfile {
        final int[]  color;
        final int    alphaMode;
        final double warpAmp;
        final double fbmPerturb;
        final double opacityMult;
        final double defMinSize;
        final double defMaxSize;
        final double satFrac;
        SubstanceProfile(int[] color, int alphaMode, double warpAmp, double fbmPerturb,
                         double opacityMult, double defMinSize, double defMaxSize, double satFrac) {
            this.color = color;  this.alphaMode = alphaMode;  this.warpAmp = warpAmp;
            this.fbmPerturb = fbmPerturb;  this.opacityMult = opacityMult;
            this.defMinSize = defMinSize;  this.defMaxSize = defMaxSize;  this.satFrac = satFrac;
        }
    }

    // -----------------------------------------------------------------------
    // Substance preset library.
    //
    //  coffee — hot thin liquid, vigorous spreading, strong evaporation ring
    //  tea    — similar physics to coffee, slightly less vigorous
    //  wine   — low viscosity, deep soaking, no prominent ring
    //  ink    — moderate surface tension, solid uniform fill
    //  water  — very low viscosity, wide spread, near-invisible trace mineral ring
    //  mud    — high viscosity, heavy opaque fill, minimal spread
    //  blood  — high surface tension; small cohesive drops, dense opaque centre,
    //           tiny visible satellite specks
    //  oil    — grease/vegetable oil on paper. Near-transparent film that darkens
    //           the fibres without leaving colour. Wide shallow spread, diffuse
    //           edge, effectively no evaporation ring (oil does not evaporate).
    //           opacityMult=0.22 keeps it honest: a ghost stain, not a puddle.
    //  ketchup — shear-thinning (pseudoplastic) non-Newtonian. Flows under the
    //           shear of impact, then freezes quickly once shear stops. Creates
    //           thick opaque blobs with mildly jagged edges from tomato particles.
    //           Small size range — ketchup does not spread far.
    //  honey  — viscoelastic non-Newtonian (Deborah number > 1 at slow deformation).
    //           Extremely low domain warp: elasticity retracts the boundary back
    //           toward a smooth oval. Dense amber fill, almost no satellite drops.
    //  gel    — generic aqueous gel (aloe, hand sanitiser, hair gel). Viscoelastic
    //           shear-thinning: flows on contact then stops. Semi-transparent,
    //           clean smooth boundary, subtle greenish cast.
    // -----------------------------------------------------------------------
    private static SubstanceProfile getProfile(String substance) {
        switch (substance.toLowerCase()) {
            case "tea":
                return new SubstanceProfile(new int[]{190, 150,  80}, 0, 1.10, 0.80, 1.00, 0.08, 0.28, 0.07);
            case "wine":
                return new SubstanceProfile(new int[]{114,  47,  55}, 1, 1.20, 0.85, 1.00, 0.08, 0.30, 0.07);
            case "ink":
                return new SubstanceProfile(new int[]{ 20,  20,  60}, 1, 0.60, 0.55, 1.00, 0.04, 0.20, 0.07);
            case "water":
                return new SubstanceProfile(new int[]{185, 210, 230}, 0, 1.60, 0.95, 0.35, 0.10, 0.40, 0.07);
            case "mud":
                return new SubstanceProfile(new int[]{ 90,  70,  40}, 1, 0.70, 0.65, 1.00, 0.08, 0.25, 0.07);
            case "blood":
                // High surface tension → oval drops, not fjords.
                // defMin/MaxSize tuned for realistic droplet scale on a document.
                // satFrac 0.50: satellites are 25–50 % of main radius — visible
                // specks even at these small absolute sizes.
                return new SubstanceProfile(new int[]{150,  12,  18}, 2, 0.45, 0.40, 1.00, 0.008, 0.035, 0.50);
            case "oil":
            case "grease":
                // Oil/grease on paper: thin translucent film, wide shallow spread.
                // No evaporation ring (oil does not evaporate at room temperature).
                // Paper darkens/becomes transparent where fibres absorb the oil.
                // opacityMult 0.22 keeps the stain as a faint ghost. warpAmp 1.25:
                // oil spreads along paper grain giving an irregular wide patch.
                return new SubstanceProfile(new int[]{200, 175, 110}, 1, 1.25, 0.75, 0.22, 0.12, 0.45, 0.04);
            case "ketchup":
                // Shear-thinning non-Newtonian (pseudoplastic).
                // Flows under impact shear, freezes quickly once shear stops.
                // Thick opaque blob, limited spread, mildly rough edge from
                // tomato particle texture. A few chunky satellite splats.
                return new SubstanceProfile(new int[]{168,  22,  14}, 1, 0.65, 0.60, 1.00, 0.04, 0.18, 0.18);
            case "honey":
                // Viscoelastic non-Newtonian (Deborah ≫ 1 at slow deformation).
                // Elastic memory retracts the boundary → extremely smooth oval.
                // Dense amber fill (mode 2: dense centre) with almost no
                // satellite drops; honey flows too slowly for impact splatter.
                return new SubstanceProfile(new int[]{205, 140,  18}, 2, 0.28, 0.22, 1.00, 0.04, 0.14, 0.04);
            case "gel":
                // Generic aqueous gel (aloe vera, hand sanitiser, hair gel).
                // Viscoelastic shear-thinning: flows on contact then stops fast.
                // Semi-transparent, clean boundary, slight greenish cast.
                return new SubstanceProfile(new int[]{175, 218, 195}, 1, 0.50, 0.42, 0.32, 0.03, 0.12, 0.05);
            default: // coffee
                return new SubstanceProfile(new int[]{ 72,  46,  18}, 0, 1.40, 0.90, 1.00, 0.10, 0.35, 0.07);
        }
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
