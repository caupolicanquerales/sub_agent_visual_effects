package com.capo.sub_agent_visual_effects.services;

import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class FingerprintEngine {

    // -----------------------------------------------------------------------
    // Procedural Fingerprint Overlay Engine
    //
    // Generates a photorealistic partial fingerprint texture and composites it
    // onto the source image using Multiply blend.
    //
    // Mathematical Pipeline
    // ─────────────────────
    //
    // STEP 1 — Orientation Field  Θ(x, y)
    //   Models the vector field that fingerprint ridges follow.
    //   Based on the Sherlock–Monro model: each singular point (core / delta)
    //   contributes a complex-angle influence to surrounding pixels.
    //
    //   Singular-point contribution at pixel (px, py) from point (sx, sy)
    //   of type t (core=+1, delta=−1):
    //
    //     angle_sp = atan2(py − sy, px − sx)
    //     raw_θ_i  = 0.5 · atan2(sin(2·angle_sp), cos(2·angle_sp)) + phase_i
    //
    //   Blending uses inverse-distance weighting with a Gaussian kernel:
    //
    //     w_i(x,y) = exp(−((px−sx)² + (py−sy)²) / (2·σ_sp²))
    //
    //     Θ(x,y)  = 0.5 · atan2( Σ w_i · sin(2·raw_θ_i),
    //                             Σ w_i · cos(2·raw_θ_i) )
    //
    //   When there are no singular points (pure arch pattern), Θ is a slow
    //   linear gradient from −π/4 to +π/4 across the blob width.
    //
    // STEP 2 — Ridge Frequency Map  f(x, y)
    //   Fingerprint ridges are locally periodic. In real forensics the inter-
    //   ridge distance is 8–14 px at typical scan DPI.
    //
    //     f(x,y) = 1 / ridgePeriod   (uniform; could be extended to a warped map)
    //
    // STEP 3 — Gabor Bank Synthesis
    //   For every pixel (px, py) inside the fingerprint blob:
    //
    //     xθ =   (px − cx) · cos(Θ) + (py − cy) · sin(Θ)
    //     yθ = −(px − cx) · sin(Θ) + (py − cy) · cos(Θ)
    //
    //     G(xθ, yθ) = exp(−½ · (xθ²/σx² + yθ²/σy²)) · cos(2π · f · xθ)
    //
    //   G ∈ [−1, 1]; remapped to [0, 1] as:
    //     ridge_val = 0.5 + 0.5·G
    //
    //   σx controls ridge elongation (along ridge direction),
    //   σy controls ridge width  (across ridge direction, ≈ ridgePeriod/2).
    //
    // STEP 4 — Blob Mask  (Gaussian envelope × elliptical shape)
    //   A soft Gaussian blob mask restricts the fingerprint to a realistic
    //   partial-print area and prevents hard rectangular cutoffs.
    //
    //     d²    = (dx/(a·rx))² + (dy/(b·ry))²
    //     mask  = exp(−d² / (2·maskSoftness²))
    //
    //   The elliptic stretching (a, b) is randomised so prints vary in shape.
    //
    // STEP 5 — Skin-texture noise
    //   A single octave of Perlin 2-D noise adds organic variation to ridge
    //   density — imitating the slight irregular pore patterns in skin.
    //
    //     ridge_final = ridge_val + skinNoise · Perlin2D(px·ns, py·ns)
    //
    // STEP 6 — Multiply Blend
    //   The fingerprint stain (ridge_final ∈ [0,1]) is blended via multiply:
    //
    //     dst_C = src_C · (1 − mask · opacity · (1 − ridge_final))
    //
    //   ridge_final = 1  → fully bright (paper): no darkening
    //   ridge_final = 0  → fully dark (deep ridge valley): max darkening
    //   mask = 0 at border: seamless fade into background
    //
    // params:
    //   cx, cy       (double, image centre) — fingerprint blob centre
    //   radius       (double, min(0.05·min(W,H), 90) — hard-capped at 90 px; gives ~128×180 px print (~5% of invoice height)
    //   opacity      (double, 0.88)  — peak ridge blend strength; high value forces near-black ridges at core
    //   ridgePeriod  (int, round(radius/22) clamped [3,5]) — ridge spacing 3–5 px; ~25–32 ridges across 128 px
    //   patternType  (String, "loop") — "loop" | "whorl" | "arch"
    //   angle        (double, 0.0)  — global rotation of entire pattern (degrees)
    //   sigmaY       (double, ridgePeriod × 0.22) — ridge sharpness; gamma = ridgePeriod*0.65/sigmaY ≈ 3.0
    //   skinNoise    (double, 0.22) — Perlin amplitude for ridge-phase / pore variation
    //   inkSaturation(double, 3.0)  — linear boost: ridge ≥33% cosine clips to full-black; valleys stay 0
    //   maskSoftness (double, 1.0)  — raised-cosine taper exponent; 1=standard, >1=steeper edge falloff
    //   inkR/G/B     (int, 35/28/22) — ridge colour in RGB; very dark sebum-brown, near-black ridges
    //   seed         (int, -1)  — RNG seed; -1 = random each call
    // -----------------------------------------------------------------------

    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry =
        Map.of("fingerprint", (src, params) -> {

            int    rows = src.rows();
            int    cols = src.cols();
            int    ch   = src.channels();
            int    minDim = Math.min(rows, cols);

            // ── Parameters ─────────────────────────────────────────────────
            double cxP         = ((Number) params.getOrDefault("cx",          cols  / 2.0 )).doubleValue();
            double cyP         = ((Number) params.getOrDefault("cy",          rows  / 2.0 )).doubleValue();
            // Hard-capped at 90 px so a print is ≈128 px wide × 180 px tall (~5% of invoice height).
            // The fraction 0.05 scales sensibly for smaller images while the 90-px cap prevents
            // the print from dominating the white space on a standard invoice layout.
            double radius      = ((Number) params.getOrDefault("radius",      Math.min(minDim * 0.05, 90.0))).doubleValue();
            // Enforce physical bounds regardless of agent-supplied value:
            // min 30 px (smallest partial print), max 90 px (≈128 px wide, ~5% of invoice height).
            radius = Math.max(30.0, Math.min(radius, 90.0));
            double opacity     = ((Number) params.getOrDefault("opacity",     0.88          )).doubleValue();
            // Auto-scale ridgePeriod to 3–5 px spacing → ~32 ridges across the 128-px width.
            // radius÷22 gives 4 px at radius=90; hard clamped to [3, 5].
            int    defaultRP   = Math.max(3, Math.min(5, (int) Math.round(radius / 22.0)));
            int    ridgePeriod = ((Number) params.getOrDefault("ridgePeriod", defaultRP     )).intValue();
            // Enforce ridge spacing regardless of agent override: keeps frequency in target band.
            ridgePeriod = Math.max(3, Math.min(ridgePeriod, 5));
            String patternType = (String)  params.getOrDefault("patternType", "loop");
            double angleDeg    = ((Number) params.getOrDefault("angle",       0.0           )).doubleValue();
            // sigmaY → gamma = ridgePeriod*0.65/sigmaY ≈ 3.0; smaller = sharper dark valleys
            double sigmaY      = ((Number) params.getOrDefault("sigmaY",      ridgePeriod * 0.22)).doubleValue();
            // skinNoise 0.22: phase-perturbs xTheta → uneven ridge width, ink pore gaps
            double skinNoise      = ((Number) params.getOrDefault("skinNoise",     0.22)).doubleValue();
            // inkSaturation 3.0: linear boost so any ridge with ≥33% cosine clips to full-black;
            // valleys remain exactly 0 — aggressive clipping ensures near-black ridges at centre.
            double inkSaturation  = ((Number) params.getOrDefault("inkSaturation", 3.0 )).doubleValue();
            double maskSoft       = ((Number) params.getOrDefault("maskSoftness",  1.0 )).doubleValue();
            // Ink colour: very dark sebum-brown (~14% of white = near-black at full blend).
            int    inkR        = ((Number) params.getOrDefault("inkR",        35           )).intValue();
            int    inkG        = ((Number) params.getOrDefault("inkG",        28           )).intValue();
            int    inkB        = ((Number) params.getOrDefault("inkB",        22           )).intValue();
            int    seedVal     = ((Number) params.getOrDefault("seed",        -1           )).intValue();

            Random rng  = (seedVal < 0) ? new Random() : new Random(seedVal);
            int[]  perm  = buildPermTable(rng);
            // Independent permutation table for the contact-pressure blotch field
            // so it is statistically uncorrelated from the ridge-phase noise.
            int[]  perm2 = buildPermTable(new Random(rng.nextLong()));
            // Third independent table for micro-grain texture (wavelength ≈ 2–3 px).
            // Kept separate from perm/perm2 to avoid any correlation with the blotch
            // or ridge-phase fields.
            int[]  perm3 = buildPermTable(new Random(rng.nextLong()));

            double angleRad   = Math.toRadians(angleDeg);
            // Perlin at ~6× the ridge frequency → pore-scale texture noise
            double noiseScale    = 1.0 / (ridgePeriod * 6.0);
            // Contact-pressure blotch: low-frequency field (wavelength ≈ 30% of blob
            // radius) models the uneven area of skin-paper contact.  Produces the
            // characteristic "blotchy" look of a real oil/sebum fingerprint where some
            // patches are visibly darker (high contact) and others nearly invisible.
            double contactScale  = 1.0 / (radius * 0.30);

            // ── Elliptic blob shape (finger-pad aspect ratio ≈ 1:1.4) ─────
            // Human finger pads are ~40% longer (tip-to-palm) than wide.
            // Small Gaussian jitter (±5%) simulates varying contact angle.
            double ell_a = 0.71 + rng.nextGaussian() * 0.04;  // width  ≈ 0.71 × radius
            double ell_b = 1.00 + rng.nextGaussian() * 0.04;  // length ≈ 1.00 × radius (ratio ≈ 1:1.41)
            double rx    = radius * Math.abs(ell_a);
            double ry    = radius * Math.abs(ell_b);

            // ── Singular points (Sherlock–Monro model) ─────────────────────
            // Each singular point carries: { sx, sy, type (+1=core, -1=delta), phase }
            // "loop"  → 1 core near blob centre, no delta
            // "whorl" → 2 cores (upper/lower), 2 deltas (left/right)
            // "arch"  → no singular points (pure gradient field)
            int    nSP      = 0;
            double[][] spData; // [n][4] → {sx, sy, type, phase}
            switch (patternType.toLowerCase()) {
                case "whorl": {
                    double jitter = radius * 0.20;
                    spData = new double[][] {
                        { cxP + rng.nextGaussian() * jitter,
                          cyP - radius * 0.18 + rng.nextGaussian() * jitter,
                          +1, Math.PI },                     // core upper — phase π → ridges loop around core
                        { cxP + rng.nextGaussian() * jitter,
                          cyP + radius * 0.18 + rng.nextGaussian() * jitter,
                          +1, Math.PI },                     // core lower (phase flipped → concentric)
                        { cxP - radius * 0.55 + rng.nextGaussian() * jitter,
                          cyP + rng.nextGaussian() * jitter,
                          -1, 0 },                           // delta left
                        { cxP + radius * 0.55 + rng.nextGaussian() * jitter,
                          cyP + rng.nextGaussian() * jitter,
                          -1, Math.PI }                      // delta right
                    };
                    nSP = 4;
                    break;
                }
                case "arch": {
                    spData = new double[0][4];
                    nSP = 0;
                    break;
                }
                default: { // loop: 1 core (upper-centre) + 1 delta (lower-right triradius)
                    // Standard right-loop topology — ridges enter from bottom-left,
                    // curve over the core, exit from bottom-right.
                    double jitter = radius * 0.08;
                    // rx/ry already computed above (1:1.4 ellipse); reuse here.
                    spData = new double[][] {
                        { cxP + rng.nextGaussian() * jitter,
                          cyP - radius * 0.22 + rng.nextGaussian() * jitter,
                          +1, Math.PI },                     // core: phase π → closed loops around core
                        { cxP + rx * 0.65 + rng.nextGaussian() * jitter,
                          cyP + ry * 0.32 + rng.nextGaussian() * jitter,
                          -1, 0 }                            // delta: triradius at lower-right
                    };
                    nSP = 2;
                    break;
                }
            }

            // σ_sp: singular-point influence radius — 30% of blob radius.
            // Tighter than before (was 50%): the core's concentrated influence creates a
            // sharper, more realistic loop/whorl singularity.  Beyond this radius the arch
            // prior quickly dominates, producing clean parallel ridges at the periphery.
            double sigmaSP  = radius * 0.30;
            double sigmaSP2 = 2.0 * sigmaSP * sigmaSP;

            // ── Build gradient-field lookup for arch (no singular points) ──
            // Arch prior direction: near-horizontal (small tilt) so far-field ridges
            // run horizontally and clearly diverge from the core loop region.
            double archTilt = rng.nextDouble() * 0.20 - 0.10;  // ±0.10 rad (≈6°) tilt

            // ── Scan region (hard clip at ellipse boundary handles the rest) ─
            double maxR   = Math.max(rx, ry) * 1.05;
            // Rotation matrix precomputed once — constant for every pixel in this call
            double cosA   = Math.cos(-angleRad);
            double sinA   = Math.sin(-angleRad);
            int    r0     = Math.max(0,    (int)(cyP - maxR));
            int    r1     = Math.min(rows, (int)(cyP + maxR) + 1);
            int    c0     = Math.max(0,    (int)(cxP - maxR));
            int    c1     = Math.min(cols, (int)(cxP + maxR) + 1);

            // Ink colour in BGR order (OpenCV)
            double inkBcv = inkB;
            double inkGcv = inkG;
            double inkRcv = inkR;

            // Pass 1 — render ridge darkness values into float layer.
            // Java zero-initialises float[] so pixels outside the scan region stay 0.
            float[] darkLayer = new float[rows * cols];

            for (int r = r0; r < r1; r++) {
                for (int c = c0; c < c1; c++) {

                    // ── Relative position, apply global rotation ───────────
                    double dxRaw = c - cxP;
                    double dyRaw = r - cyP;
                    double dx    = dxRaw * cosA - dyRaw * sinA;
                    double dy    = dxRaw * sinA + dyRaw * cosA;

                    // ── Blob mask: hard ellipse clip + raised-cosine taper ──
                    // Ridges are physically limited to the finger contact patch.
                    // The raised-cosine envelope simulates the hemisphere pressure
                    // profile: maximum opacity at centre (peak contact), falls
                    // smoothly to exactly 0.0 at the ellipse boundary — no bleed.
                    double dNorm2 = (dx / rx) * (dx / rx) + (dy / ry) * (dy / ry);
                    if (dNorm2 >= 1.0) continue;              // hard ellipse boundary — no bleed
                    double dNorm  = Math.sqrt(dNorm2);        // ∈ [0, 1)
                    // Pressure taper: full opacity in inner 85%, raised-cosine fade in
                    // the outer 15% only — crisp ridges at centre, clean edge cutoff.
                    double taper;
                    if (dNorm < 0.85) {
                        taper = 1.0;
                    } else {
                        double t = (dNorm - 0.85) / 0.15;    // 0 = start of fade, 1 = edge
                        taper = 0.5 * (1.0 + Math.cos(Math.PI * t));
                    }
                    double mask = Math.pow(taper, maskSoft);

                    // Variable pressure dome: Gaussian profile concentrated at the core
                    // so the centre prints near-black while edges taper to ~45% weight.
                    // This high-contrast gradient is the primary visual signature of
                    // a real rolled/pressed latent print (forensic reference look).
                    //   pressure ∈ [0.45, 1.00]  (Gaussian, 1.00 at centre)
                    double pressure = 0.45 + 0.55 * Math.exp(-dNorm2 * 2.2);

                    // ── STEP 1  Orientation Field Θ(x, y) ─────────────────
                    double theta;
                    if (nSP == 0) {
                        // Arch: linear gradient field
                        theta = archTilt + (dx / (2.0 * rx)) * (Math.PI * 0.5);
                    } else {
                        // Sherlock–Monro: inverse-distance weighted singular points
                        double sumSin = 0.0, sumCos = 0.0, sumW = 0.0;
                        for (int i = 0; i < nSP; i++) {
                            double spDx  = c - spData[i][0];
                            double spDy  = r - spData[i][1];
                            double dist2 = spDx * spDx + spDy * spDy;
                            double w     = Math.exp(-dist2 / sigmaSP2);
                            double aSP   = Math.atan2(spDy, spDx);
                            double type  = spData[i][2];
                            double phase = spData[i][3];
                            // Double-angle representation so +1 core and -1 delta create
                            // the correct winding direction of the flow field
                            double raw   = 0.5 * (type * 2.0 * aSP + phase);
                            sumSin += w * Math.sin(2.0 * raw);
                            sumCos += w * Math.cos(2.0 * raw);
                            sumW   += w;
                        }
                        // Reduced arch prior (0.30) — the core's extended influence now
                        // dominates a larger area, creating a pronounced pinched loop
                        // while still forcing parallel ridges at the print periphery.
                        double archPrior = 0.30;
                        sumSin += archPrior * Math.sin(2.0 * archTilt);
                        sumCos += archPrior * Math.cos(2.0 * archTilt);
                        sumW   += archPrior;
                        theta = 0.5 * Math.atan2(sumSin / sumW, sumCos / sumW);
                    }

                    // ── STEP 2  Per-pixel ridge frequency ──────────────────
                    // Ridges are ~20% tighter near the core, expanding toward
                    // the ellipse boundary (real friction-ridge skin behaviour).
                    //   localPeriod = ridgePeriod × (0.80 + 0.40 × dNorm)
                    double fLocal   = 1.0 / (ridgePeriod * (0.80 + 0.40 * dNorm));

                    // ── STEP 3  Ridge synthesis ─────────────────────────────
                    // Pure cosine in the ridge-aligned frame.
                    // sigmaY drives gamma ≈ 3.0 (default): narrow dark valleys,
                    // wide bright ridges — the defining visual of a real inked print.
                    double cosT   = Math.cos(theta);
                    double sinT   = Math.sin(theta);
                    // CRITICAL: xTheta must be the ACROSS-RIDGE coordinate so the cosine
                    // oscillates perpendicular to the ridge direction.
                    // Across-ridge unit vector = (−sin θ, cos θ).
                    // Bug in previous version: used along-ridge (cos θ, sin θ) → 90° wrong.
                    double xTheta = -dx * sinT + dy * cosT;

                    // ── STEP 5  Skin-texture noise as phase perturbation ───
                    // Shifting xTheta by noise varies ridge width pixel-by-pixel,
                    // creating the non-uniform ink pore gaps seen in real prints.
                    double noise   = perlin2D(c * noiseScale, r * noiseScale, perm);
                    double xThetaN = xTheta + skinNoise * ridgePeriod * 0.35 * noise;

                    double cosine  = Math.cos(2.0 * Math.PI * fLocal * xThetaN);
                    double gamma   = (ridgePeriod * 0.65) / Math.max(1.0, sigmaY);
                    double ridgeVal = Math.pow(Math.max(0.0, 0.5 + 0.5 * cosine), gamma);

                    // ── STEP 6  Contact-pressure blotch mask ───────────
                    // Low-frequency Perlin (independent table perm2) models the uneven
                    // skin-paper contact area.  Blotchy patches where the finger presses
                    // harder render ≈50% darker; areas with partial lift-off render
                    // ≈50% lighter.  This is the primary visual signature of a real
                    // oil / sebum print and the most common "tell" in synthetic prints.
                    //   contactNoise ∈ [−1, 1]  →  factor ∈ [0.50, 1.50]
                    double contactNoise  = perlin2D(c * contactScale + 200.0, r * contactScale + 300.0, perm2);
                    double contactFactor = 1.0 + 0.50 * contactNoise;

                    // Ink saturation: linear boost compresses partial ridges to
                    // full-black faster (inkSaturation=2 → 50% cosine = 100% ink).
                    // Valleys stay exactly 0 — no darkening between ridges.
                    double ridgeValS = Math.min(1.0, ridgeVal * inkSaturation);

                    // Final dark value: mask × pressure dome × opacity × saturated ridge
                    // × contact-blotch factor.  pressure makes the core ≈2× as dark as
                    // the perimeter; contactFactor ∈ [0.50,1.50] adds large low-freq patches.
                    double dark = mask * pressure * opacity * ridgeValS * contactFactor;

                    // ── STEP 7  Perlin pore-blob fragmentation ─────────────
                    // Mid-frequency Perlin erases ~25% of ridge area as larger ink-
                    // starvation patches (partial lift-off, ink-depleted pore clusters).
                    //   poreMask = 0 if poreNoise < −0.20  (gap)
                    //            = 1 if poreNoise > −0.10  (full ink)
                    double poreScale = 1.2 / ridgePeriod;
                    double poreNoise = perlin2D(c * poreScale + 31.7, r * poreScale + 97.3, perm);
                    double poreMask  = Math.max(0.0, Math.min(1.0, (poreNoise + 0.20) / 0.10));
                    dark *= poreMask;

                    // ── STEP 8  Salt-and-pepper sweat-pore gaps ────────────
                    // High-frequency hash produces individually isolated white breaks
                    // in the ridge lines, simulating the ~80-µm pore openings visible
                    // in high-resolution latent prints (~7% drop-out rate, ≈18/255).
                    // Uses a deterministic integer hash of (c,r) seeded by perm — no
                    // extra Random object required inside the inner loop.
                    int spHash = perm[(perm[((c * 73856093) ^ (r * 19349663)) & 255]
                                      + ((c ^ (r * 3)) & 255)) & 255];
                    if (spHash < 38) dark = 0.0;   // ≈ 15 % pore gap — gritty salt-and-pepper breaks

                    // ── STEP 9  Micro-grain texture ──────────────────────────
                    // Thresholded high-frequency Perlin (wavelength ≈ 1.8 px, table perm3)
                    // fragments the alpha at sub-ridge scale — imitating the break-up of
                    // oil or carbon at individual paper-fibre junctions.
                    //   Nearly-binary: tight 0.10 ramp → ~25 % of ridge pixels become hard
                    //   grain gaps; the remaining 75 % snap to full ink strength.
                    // Higher frequency (0.55) + tighter ramp (0.10) produces the "gritty"
                    // dotted ink texture visible in high-res forensic reference prints.
                    if (dark > 0.0) {
                        double microNoise = perlin2D(c * 0.55 + 500.0, r * 0.55 + 700.0, perm3);
                        double microGrain = Math.max(0.0, Math.min(1.0, (microNoise + 0.25) / 0.10));
                        dark *= microGrain;
                    }

                    if (dark > 0.003) darkLayer[r * cols + c] = (float) dark;
                }
            }

            // Pass 2 — Ink-bleed: minimal Gaussian blur (sigma=0.8, ksize=3).
            // Just enough to simulate ink spreading 1 px into paper fibres and break
            // the pixel-perfect cosine precision; does NOT destroy ridge structure.
            int roiRows = r1 - r0;
            int roiCols = c1 - c0;
            if (roiRows > 0 && roiCols > 0) {
                float[] roiRow = new float[roiCols];
                Mat darkMat = Mat.zeros(roiRows, roiCols, CvType.CV_32F);
                for (int r = r0; r < r1; r++) {
                    for (int c = c0; c < c1; c++) roiRow[c - c0] = darkLayer[r * cols + c];
                    darkMat.put(r - r0, 0, roiRow);
                }
                Imgproc.GaussianBlur(darkMat, darkMat, new Size(3, 3), 0.8);
                for (int r = r0; r < r1; r++) {
                    darkMat.get(r - r0, 0, roiRow);
                    for (int c = c0; c < c1; c++) darkLayer[r * cols + c] = roiRow[c - c0];
                }
                darkMat.release();
            }

            // Pass 3 — Composite: multiply-blend the ridge layer onto the source image.
            Mat dst = src.clone();
            byte[] rowBuf = new byte[cols * ch];
            for (int r = r0; r < r1; r++) {
                dst.get(r, 0, rowBuf);
                boolean changed = false;
                for (int c = c0; c < c1; c++) {
                    float d = darkLayer[r * cols + c];
                    if (d < 0.004f) continue;
                    int    base  = c * ch;
                    double srcB  = rowBuf[base    ] & 0xFF;
                    double srcG  = rowBuf[base + 1] & 0xFF;
                    double srcR  = rowBuf[base + 2] & 0xFF;
                    double multB = (srcB * inkBcv) / 255.0;
                    double multG = (srcG * inkGcv) / 255.0;
                    double multR = (srcR * inkRcv) / 255.0;
                    rowBuf[base    ] = clampByte(srcB * (1.0 - d) + multB * d);
                    rowBuf[base + 1] = clampByte(srcG * (1.0 - d) + multG * d);
                    rowBuf[base + 2] = clampByte(srcR * (1.0 - d) + multR * d);
                    changed = true;
                }
                if (changed) dst.put(r, 0, rowBuf);
            }
            return dst;
        });

    // -----------------------------------------------------------------------
    // Post-process pass: apply a light Gaussian blur to the rendered ridge
    // layer to simulate the slight ink bleed into paper fibres, then blend
    // the blurred layer back onto the sharp render at a 30% weight.
    // (Called as a convenience — not exposed as a separate operation.)
    // -----------------------------------------------------------------------
    @SuppressWarnings("unused")
    private static Mat softenRidges(Mat src) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(src, blurred, new Size(3, 3), 0);
        Mat result = new Mat();
        Core.addWeighted(src, 0.70, blurred, 0.30, 0, result);
        return result;
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
        int    aa = p[p[xi    ] + yi    ];
        int    ab = p[p[xi    ] + yi + 1];
        int    ba = p[p[xi + 1] + yi    ];
        int    bb = p[p[xi + 1] + yi + 1];
        return lerp(v,
                lerp(u, grad2(aa, xf,     yf    ), grad2(ba, xf - 1, yf    )),
                lerp(u, grad2(ab, xf,     yf - 1), grad2(bb, xf - 1, yf - 1)));
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    private static double grad2(int hash, double x, double y) {
        switch (hash & 3) {
            case 0:  return  x + y;
            case 1:  return -x + y;
            case 2:  return  x - y;
            default: return -x - y;
        }
    }

    /** Fisher–Yates shuffle doubled to 512 entries for wrap-free lookup. */
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

    private static byte clampByte(double v) {
        return (byte) Math.max(0, Math.min(255, (int) Math.round(v)));
    }

    public Mat applyOperation(String operationName, Mat input, Map<String, Object> params) {
        return operationRegistry.getOrDefault(operationName.toLowerCase(), (s, p) -> s)
                                .apply(input, params);
    }
}
