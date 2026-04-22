package com.capo.sub_agent_visual_effects.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

@Service
public class FlatRealPhotoEngine {

    // -----------------------------------------------------------------------
    // Flat Real Photo Engine
    //
    // Simulates a 3D pinhole camera photograph of a geometrically flat
    // document.  Four physics-grounded layers compose in sequence:
    //
    // ── 1. RADIAL BARREL DISTORTION (BROWN–CONRADY) — applied FIRST ────────
    //
    //   PIPELINE ORDER: Barrel distortion is applied to the clean source
    //   BEFORE perspective warping.  This matches the real camera pipeline
    //   (lens distortion → sensor projection) and prevents the "white jagged
    //   tear" artefact: when barrel runs after perspective, its inverse map
    //   samples into the white inset-background zone created by the perspective
    //   trapezoid, causing a coordinate-singularity fold-over at the centre.
    //
    //   Backward-mapping polynomial (Brown–Conrady):
    //
    //     X = x − cx,   Y = y − cy          (centre-relative coords)
    //     r² = (X² + Y²) / norm²             norm = max(cx, cy)
    //     denom = 1 + k1·r² + k2·r⁴
    //     x_src = cx + X / denom
    //     y_src = cy + Y / denom
    //
    //   k1 > 0 (e.g. 0.02) → barrel (content bows outward, lines curve ~3 %
    //   at corners).  k2 corrects higher-order radial asymmetry (usually 0.0).
    //   INTER_LANCZOS4 + BORDER_CONSTANT(white) as for step 0.
    //
    // ── 0. PERSPECTIVE TRANSFORMATION (3D PROJECTION) ──────────────────────
    //
    //   A real photo is never taken perfectly parallel to the document.
    //   The four source corners are mapped to destination corners whose
    //   positions are offset inward by small ε values, forming a slight
    //   trapezoid that the homography H resolves:
    //
    //     [u]   [h11 h12 h13] [x]
    //     [v] = [h21 h22 h23] [y]      →  pixel = (u/w, v/w)
    //     [w]   [h31 h32 h33] [1]
    //
    //   Source corners:
    //     S = [(0, 0), (W−1, 0), (W−1, H−1), (0, H−1)]
    //
    //   Destination corners (per-corner εTL, εTR, εBR, εBL as fraction of
    //   m = min(W, H)):
    //     TL → (εTL·m,        εTL·m)
    //     TR → (W−1−εTR·m,    εTR·m)
    //     BR → (W−1−εBR·m,    H−1−εBR·m)
    //     BL → (εBL·m,        H−1−εBL·m)
    //
    //   getPerspectiveTransform solves the 8-DOF homography H via the
    //   Direct Linear Transform for exactly 4 point pairs.
    //   warpPerspective with INTER_LANCZOS4 + BORDER_REPLICATE preserves
    //   text sharpness at the warped document edges.
    //
    // ── 1. RADIAL BARREL DISTORTION (BROWN–CONRADY) ─────────────────────────
    //
    //   Real smartphone and wide-angle lenses introduce barrel distortion:
    //   straight lines bow outward.  Backward-mapping polynomial (Brown–Conrady):
    //
    //     X = x − cx,   Y = y − cy          (centre-relative coords)
    //     r² = (X² + Y²) / norm²             norm = max(cx, cy)
    //     denom = 1 + k1·r² + k2·r⁴
    //     x_src = cx + X / denom
    //     y_src = cy + Y / denom
    //
    //   k1 > 0 (e.g. 0.02) → barrel (content bows outward, lines curve ~3 %
    //   at corners).  k2 corrects higher-order radial asymmetry (usually 0.0).
    //   INTER_LANCZOS4 + BORDER_REPLICATE as for step 0.
    //
    // ── 2. NON-UNIFORM LIGHTING — ADDITIVE FILL-LIGHT (DIRECTIONAL SUNLIGHT) ─
    //
    //   Natural morning sunlight from a window acts as a large-area directional
    //   source, NOT a point-light hotspot.  The corrected model combines:
    //
    //   (a) Gaussian halo: gentle local brightening at (Lx, Ly)
    //       v_gauss(x,y) = exp(−r²/(2σ²))
    //
    //   (b) Directional linear gradient: ramps from 0 at the light source
    //       to 1 at the opposite corner, simulating parallel window rays
    //       v_dir(x,y) = dot((x/W, y/H), (1−Lx, 1−Ly)) / |(1−Lx, 1−Ly)|
    //
    //   Combined: v = 0.65·v_gauss + 0.35·(1 − v_dir)
    //
    //   Additive Fill-Light Floor (the key fix for "morning desk" realism):
    //     mask(x,y) = max(v_combined, lightMin)    lightMin = 0.40
    //     → even the darkest corner retains 40 % brightness (ambient bounce)
    //     → shadows look like a dim desk, not a black flash-lit void
    //
    //   Final: pixel_out = pixel_in · lerp(lightMin, lightMax, mask)
    //
    // ── 3. DEPTH OF FIELD (LINEAR RAMP + LERP BLUR) ─────────────────────────
    //
    //   To simulate the camera aperture, the part of the document "farther"
    //   from the lens appears blurrier.  A linear ramp mask M(y) ∈ [0, 1]:
    //
    //     M(y) = y / (H − 1)      0 at top-row  → fully sharp   (near)
    //                              1 at bottom-row → fully blurred (far)
    //
    //   Linearly interpolate (Lerp) between sharp and Gaussian-blurred:
    //
    //     Output = (1 − M) · img_sharp + M · img_blurred_σ
    //
    //   dofInvert=true reverses the ramp so blur is at the top instead.
    //   The brain interprets the gradient as depth even though the geometry
    //   is flat after step 0.
    //
    // -----------------------------------------------------------------------
    // params:
    //   perspEnabled   (boolean, true)   — step 0: perspective warp via H
    //   perspTL        (double,  0.04)   — top-left     ε as fraction of min(W,H)
    //   perspTR        (double,  0.01)   — top-right    ε as fraction of min(W,H)
    //   perspBR        (double,  0.00)   — bottom-right ε as fraction of min(W,H)
    //   perspBL        (double,  0.02)   — bottom-left  ε as fraction of min(W,H)
    //
    //   barrelEnabled  (boolean, true)   — step 1: barrel radial distortion
    //   barrelK1       (double,  0.02)   — k1: + barrel / − pincushion (realistic ≤ 0.05)
    //   barrelK2       (double,  0.00)   — k2: higher-order radial correction
    //
    //   lightEnabled   (boolean, true)   — step 2: Gaussian vignetting
    //   lightX         (double,  0.35)   — light source X as fraction of width  [0, 1]
    //                                        values > 1.0 place the hotspot off-canvas right
    //   lightY         (double,  0.25)   — light source Y as fraction of height [0, 1]
    //   lightSigma     (double,  0.55)   — σ as fraction of image half-diagonal
    //   lightMin       (double,  0.40)   — fill-light floor: minimum mask multiplier (ambient)
    //                                        sunny beach: 0.60 — shadows stay very bright via GI
    //   lightMax       (double,  1.0)    — fill-light ceiling: maximum mask multiplier
    //                                        sunny beach: 1.20 — overexposes the lit side
    //                                        I_mask = lerp(lightMin, lightMax, G(x,y))
    //   exposureOffset (double,  0.0)    — additive exposure offset in [0,255] float space;
    //                                        applied after mask multiplication to lift blacks
    //   reinhardEnabled(boolean, false)  — soft-knee Reinhard tone mapping after exposure;
    //                                        prevents white-circle artefact when lightMax > 1.0
    //   whiteLevel     (double, 255.0)   — Reinhard knee point (8-bit space, nominal 255);
    //                                        85 % is the knee start; above it: hyperbolic rolloff
    //
    //   dofEnabled     (boolean, true)   — step 3: depth-of-field ramp blur
    //   dofBlurSigma   (double,  9.0)    — σ for the fully-blurred reference frame (pixels)
    //   dofInvert      (boolean, false)  — true → blur at top; false → blur at bottom
    //   dofTopFraction (double,  1.0)    — fraction of image height the DoF ramp covers;
    //                                        1.0 = full-height ramp; 0.10 = top-10 % only
    //                                        (sunny beach + dofInvert=true: use 0.10)
    //
    //   lightSigK      (double, 10.0)    — sigmoid steepness k for lighting soft-clip
    //   lightSigThresh (double,  0.75)   — sigmoid threshold (inflection of roll-off) [0, 1]
    //
    // ── 4. SURFACE TOPOGRAPHY (PERLIN NOISE DISPLACEMENT) ──────────────────
    //
    //   Real paper is a 3D mesh of fibers.  A high-frequency Perlin Noise
    //   field N(x,y) acts as a Height Map whose gradient is the Normal Map:
    //
    //     n⃗ = ∇N(x,y) = (∂N/∂x, ∂N/∂y, 1)   (unnormalised)
    //
    //   Phong shading (ambient + diffuse) with the Gaussian light vector L⃗:
    //
    //     I = I_ambient + I_diffuse · (n⃗ · L⃗)
    //
    //   Applied as a multiplicative brightness field so the paper grain
    //   reacts to the same virtual light used in step 2.
    //
    // ── 5. INK-SUBSTRATE INTEGRATION (VARIABLE-KERNEL BLEED MODEL) ─────────
    //
    //   Ink sinks into fibers and spreads in proportion to local contrast.
    //   Variable-kernel Gaussian convolution:
    //
    //     σ_local ∝ |Img_ink(x,y) − Img_paper(x,y)|
    //
    //   Implemented as a weighted blend between the original (σ=0) and a
    //   mildly blurred version, weight driven by the per-pixel contrast.
    //   This softens digital sharpness at text edges just enough to look printed.
    //
    // ── 6. SPECULAR MICRO-HIGHLIGHTS (SCHLICK / FRESNEL) ───────────────────
    //
    //   Matte paper has a subtle sheen at grazing angles (edges of the warped
    //   document) — the Fresnel Effect.  Schlick approximation:
    //
    //     R(θ) = R₀ + (1 − R₀)(1 − cosθ)⁵     R₀ ≈ 0.04
    //
    //   cosθ is approximated from the paper-normal (derived from the same
    //   Perlin height map) dotted with the normalised view vector (0,0,1).
    //   R(θ) is added as a specular highlight layer over the lit image.
    //
    // ── 7. SHADOW CONTACT (SDF-BASED AMBIENT OCCLUSION) ────────────────────
    //
    //   Where the paper meets the background table there is a tight contact
    //   shadow.  A Signed Distance Field d(x,y) from the document mask edge
    //   gives the shadow intensity S:
    //
    //     S(d) = I_bg · (1 − exp(−k · d²))
    //
    //   d is approximated via iterative morphological erosion of the alpha
    //   mask.  The result anchors the document to the surface, eliminating
    //   the "floating" look.
    //
    // -----------------------------------------------------------------------
    //   perlinEnabled  (boolean, true)   — step 4: surface topography
    //   perlinScale    (double,  0.012)  — spatial frequency of noise (higher = finer grain)
    //   perlinStrength (double,  0.08)   — max brightness deviation from grain [0, 1]
    //   perlinOctaves  (int,     4)      — noise octaves (detail layers)
    //   perlinAmbient  (double,  0.92)   — I_ambient base for Phong shading [0, 1]
    //
    //   inkBleedEnabled (boolean, true)  — step 5: ink-substrate bleed
    //   inkBleedSigma   (double,  1.2)   — max σ for edge-bleed Gaussian (pixels)
    //   inkBleedStr     (double,  0.55)  — blend weight at maximum contrast [0, 1]
    //
    //   fresnelEnabled  (boolean, true)  — step 6: Fresnel specular highlights
    //   fresnelR0       (double,  0.04)  — base reflectivity of paper
    //   fresnelStrength (double,  0.18)  — scale of the specular pass [0, 1]
    //
    //   contactEnabled  (boolean, true)  — step 7: contact shadow (AO)
    //   contactK        (double,  0.004) — falloff steepness k in S(d) formula
    //   contactDarkness (double,  0.55)  — max shadow multiplier at edge [0, 1]
    //
    //   ambientFloor     (double,  0.20)  — minimum pixel intensity after lighting [0,1];
    //                                        simulates bounce light so shadows never crush black
    //
    //   microTiltEnabled (boolean, true)  — step 0b: micro Z-axis in-plane rotation
    //   microTiltDeg     (double,  0.5)   — rotation angle in degrees; triggers brain 3-D cue
    //
    //   paperEdgeEnabled (boolean, true)  — step 8: paper physical-edge seam
    //   paperEdgeHL      (int,     30)    — brightness added to top-edge pixels [0,255]
    //   paperEdgeSH      (int,     20)    — darkness subtracted from bottom-edge pixels [0,255]
    //   paperEdgeThresh  (int,    160)    — luminance threshold to detect paper vs. background
    //
    //   seed             (int,     -1)    — RNG seed controlling all per-call randomisation:
    //                                        -1 = random per call: unique Perlin grain AND
    //                                             random perspective corner offsets (perspTL/TR/BR/BL)
    //                                             and microTiltDeg, unless those params are explicitly
    //                                             present in the call (explicit values always win).
    //                                        >=0 = fully reproducible: same grain + same geometry every call.
    // -----------------------------------------------------------------------

    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry =
        Map.of("flatrealphoto", (src, params) -> {

            boolean perspEnabled  = boolParam(params, "perspEnabled",  true);
            double  perspTL       = dblParam (params, "perspTL",        0.04);
            double  perspTR       = dblParam (params, "perspTR",        0.01);
            double  perspBR       = dblParam (params, "perspBR",        0.00);
            double  perspBL       = dblParam (params, "perspBL",        0.02);

            boolean barrelEnabled = boolParam(params, "barrelEnabled", true);
            double  barrelK1      = dblParam (params, "barrelK1",       0.02);
            double  barrelK2      = dblParam (params, "barrelK2",       0.00);

            boolean lightEnabled    = boolParam(params, "lightEnabled",    true);
            double  lightX          = dblParam (params, "lightX",           0.35);
            double  lightY          = dblParam (params, "lightY",           0.25);
            double  lightSigma      = dblParam (params, "lightSigma",       0.55);
            double  lightMin        = dblParam (params, "lightMin",         0.40);
            double  lightMax        = dblParam (params, "lightMax",         1.0);
            double  exposureOffset  = dblParam (params, "exposureOffset",   0.0);
            boolean reinhardEnabled = boolParam(params, "reinhardEnabled",  false);
            double  whiteLevel      = dblParam (params, "whiteLevel",      255.0);

            boolean dofEnabled     = boolParam(params, "dofEnabled",    true);
            double  dofBlurSigma   = dblParam (params, "dofBlurSigma",   9.0);
            boolean dofInvert      = boolParam(params, "dofInvert",     false);
            double  dofTopFraction = dblParam (params, "dofTopFraction", 1.0);

            boolean perlinEnabled  = boolParam(params, "perlinEnabled",  true);
            double  perlinScale    = dblParam (params, "perlinScale",    0.012);
            double  perlinStrength = dblParam (params, "perlinStrength", 0.08);
            int     perlinOctaves  = (int) dblParam(params, "perlinOctaves", 4.0);
            double  perlinAmbient  = dblParam (params, "perlinAmbient",  0.92);

            boolean inkBleedEnabled = boolParam(params, "inkBleedEnabled", true);
            double  inkBleedSigma   = dblParam (params, "inkBleedSigma",    1.2);
            double  inkBleedStr     = dblParam (params, "inkBleedStr",      0.55);

            boolean fresnelEnabled  = boolParam(params, "fresnelEnabled",  true);
            double  fresnelR0       = dblParam (params, "fresnelR0",        0.04);
            double  fresnelStrength = dblParam (params, "fresnelStrength",  0.18);

            boolean contactEnabled  = boolParam(params, "contactEnabled",  true);
            double  contactK        = dblParam (params, "contactK",         0.004);
            double  contactDarkness = dblParam (params, "contactDarkness",  0.55);

            double  ambientFloor     = dblParam (params, "ambientFloor",     0.20);

            boolean microTiltEnabled = boolParam(params, "microTiltEnabled", true);
            double  microTiltDeg     = dblParam (params, "microTiltDeg",     0.5);

            boolean paperEdgeEnabled = boolParam(params, "paperEdgeEnabled", true);
            int     paperEdgeHL      = (int) dblParam(params, "paperEdgeHL",  30.0);
            int     paperEdgeSH      = (int) dblParam(params, "paperEdgeSH",  20.0);
            int     paperEdgeThresh  = (int) dblParam(params, "paperEdgeThresh", 160.0);

            int     seedVal          = (int) dblParam(params, "seed", -1.0);

            // Per-call randomisation pool.
            // seed=-1 → truly random each call (unique geometry + grain).
            // seed>=0 → deterministic (same geometry + grain every time).
            java.util.Random noiseRng = (seedVal < 0) ? new java.util.Random()
                                                      : new java.util.Random(seedVal);

            // Perlin grain offset — displaces the lattice origin so Step 4
            // produces a different paper-fibre texture on every seed=-1 call.
            double noiseOffX = noiseRng.nextDouble() * 9973.0;
            double noiseOffY = noiseRng.nextDouble() * 9973.0;

            // ── Per-call natural variation (seed = -1) ──────────────────────
            //
            // ROOT-CAUSE FIX — "same image on every call":
            //
            // The previous randomisation only replaced params that were ABSENT
            // from the call.  When the visual-plan agent sends a complete param
            // set (which it always does), every check `!params.containsKey(…)`
            // was FALSE, so the block was entirely skipped.  Only noiseOffX/Y
            // (Perlin grain) changed — far too subtle to perceive as a new photo.
            //
            // The corrected strategy mirrors how a real photographer works:
            //   • The PLAN defines the scene  (light direction, camera angle class).
            //   • Each SHOT adds natural hand/lens variation on top of the plan.
            //
            // Two-tier randomisation:
            //   Tier 1 — JITTER (always applied over explicit or default value):
            //     Small perturbations to the most visually impactful params so
            //     every call produces a perceptibly different image.
            //       lightX/Y  ± 0.06  — light walks slightly across the frame
            //       barrelK1  ± 0.012 — lens deformation varies shot-to-shot
            //       dofBlurSigma ± 2.0 — focus breathing (aperture change)
            //       lightSigma ± 0.07  — light spread / softness
            //
            //   Tier 2 — FULL RANDOM (only when caller omitted the param):
            //     Perspective corners and tilt use the full allowed range so the
            //     viewing angle is chosen freely within the family of "near-rect".
            if (seedVal < 0) {
                // Tier 1: jitter on top of every value (explicit or default)
                double jLightX = (noiseRng.nextDouble() - 0.5) * 0.12;   // ±0.06
                double jLightY = (noiseRng.nextDouble() - 0.5) * 0.12;
                lightX = Math.max(0.0, Math.min(1.0, lightX + jLightX));
                lightY = Math.max(0.0, Math.min(1.0, lightY + jLightY));

                double jBarrel = (noiseRng.nextDouble() - 0.5) * 0.024;  // ±0.012
                barrelK1 = Math.max(0.0, Math.min(0.06, barrelK1 + jBarrel));

                double jDof = (noiseRng.nextDouble() - 0.5) * 4.0;       // ±2.0 px
                dofBlurSigma = Math.max(0.5, Math.min(18.0, dofBlurSigma + jDof));

                double jSigma = (noiseRng.nextDouble() - 0.5) * 0.14;    // ±0.07
                lightSigma = Math.max(0.20, Math.min(1.0, lightSigma + jSigma));

                // Tier 2: full random for absent geometry params
                if (!params.containsKey("perspTL"))      perspTL      = noiseRng.nextDouble() * 0.06;
                if (!params.containsKey("perspTR"))      perspTR      = noiseRng.nextDouble() * 0.04;
                if (!params.containsKey("perspBR"))      perspBR      = noiseRng.nextDouble() * 0.03;
                if (!params.containsKey("perspBL"))      perspBL      = noiseRng.nextDouble() * 0.05;
                if (!params.containsKey("microTiltDeg")) microTiltDeg = noiseRng.nextDouble() * 2.5 - 1.0;
            }

            int    rows = src.rows();
            int    cols = src.cols();
            int    ch   = src.channels();

            Mat current = src.clone();

            // Document corners in the perspective-expanded canvas.
            // Initialised to the full image boundary; updated inside the
            // perspEnabled block once the exact corner offsets are known.
            // Used by the contact-shadow step to build a geometry-based mask
            // that is immune to any lighting-induced threshold ambiguity.
            double[] savedDocTL = {0.0,              0.0};
            double[] savedDocTR = {(double)(cols - 1), 0.0};
            double[] savedDocBR = {(double)(cols - 1), (double)(rows - 1)};
            double[] savedDocBL = {0.0,              (double)(rows - 1)};

            // ── STEP 1 (PRE-WARP): Radial Barrel Distortion (Brown–Conrady) ──
            //
            // IMPORTANT ORDER: Barrel distortion is applied FIRST, before any
            // perspective warp.  This matches the physical camera pipeline:
            //   1. Lens distortion bends rays (barrel / pincushion).
            //   2. The distorted rays are then projected onto the sensor plane
            //      (perspective transform).
            //
            // Applying barrel AFTER perspective would cause the distortion
            // backward-map to sample into the inset-background white border
            // introduced by the perspective trapezoid, creating a white
            // jagged-tear artefact near the image centre — a coordinate-mapping
            // singularity (denominator approaching zero in the homography's
            // projective division lands on the white inset zone).
            //
            // By keeping barrel here on the unwarped source we always map within
            // a uniform content region, eliminating the singularity.
            //
            // Backward-mapping via normalised coordinates so that (0,0) is the
            // image centre and the range spans exactly [−1, 1] × [−1, 1]:
            //
            //   x_norm = (2·x − W) / W,   y_norm = (2·y − H) / H
            //   r²     = x_norm² + y_norm²
            //   denom  = 1 + k1·r² + k2·r⁴
            //   x_src  = (x_norm / denom + 1) · W / 2      (back to pixels)
            //   y_src  = (y_norm / denom + 1) · H / 2
            //
            // Border strategy: OOB coordinates are CLAMPED to the nearest valid
            // pixel (edge replication).  This prevents the white-triangle /
            // torn-edge artefact that occurred when OOB coords were mapped to a
            // -1 sentinel and then filled with BORDER_CONSTANT white.
            //
            // Internal margin: a 1 % symmetric crop is applied before the remap
            // and reversed afterwards so that the barrel inverse-map never has
            // to reach outside the source frame, even at the extreme corners.
            // k1 = 0.02 → realistic smartphone-grade barrel (~3 % at corners).
            if (barrelEnabled && Math.abs(barrelK1) > 1e-6) {

                // ── Internal margin: crop 1 % on each side before distortion ──
                // This guarantees that the backward-map for every destination
                // pixel lands on valid source content rather than the void
                // outside the document boundary.
                int marginX = Math.max(1, (int) Math.round(cols * 0.01));
                int marginY = Math.max(1, (int) Math.round(rows * 0.01));
                Mat cropped = new Mat(current,
                    new org.opencv.core.Rect(marginX, marginY,
                                             cols - 2 * marginX,
                                             rows - 2 * marginY));
                // Scale back to original size with INTER_LANCZOS4 so downstream
                // steps see the same canvas dimensions they started with.
                Mat srcForBarrel = new Mat();
                Imgproc.resize(cropped, srcForBarrel, new Size(cols, rows), 0, 0, Imgproc.INTER_LANCZOS4);
                cropped.release();

                int bRows = srcForBarrel.rows();
                int bCols = srcForBarrel.cols();

                Mat mapX = new Mat(bRows, bCols, CvType.CV_32F);
                Mat mapY = new Mat(bRows, bCols, CvType.CV_32F);

                double halfW = bCols / 2.0;
                double halfH = bRows / 2.0;

                for (int y = 0; y < bRows; y++) {
                    double yNorm = (2.0 * y - bRows) / bRows;
                    float[] rowMX = new float[bCols];
                    float[] rowMY = new float[bCols];
                    for (int x = 0; x < bCols; x++) {
                        double xNorm = (2.0 * x - bCols) / bCols;
                        double r2    = xNorm * xNorm + yNorm * yNorm;
                        double denom = 1.0 + barrelK1 * r2 + barrelK2 * (r2 * r2);
                        if (denom <= 0.0) denom = 1.0;
                        double invD  = 1.0 / denom;
                        double xSrc  = (xNorm * invD + 1.0) * halfW;
                        double ySrc  = (yNorm * invD + 1.0) * halfH;
                        // Clamp to valid range — edge replication (no white tears)
                        rowMX[x] = (float) Math.max(0.0, Math.min(bCols - 1, xSrc));
                        rowMY[x] = (float) Math.max(0.0, Math.min(bRows - 1, ySrc));
                    }
                    mapX.put(y, 0, rowMX);
                    mapY.put(y, 0, rowMY);
                }

                Mat barrelResult = new Mat();
                Imgproc.remap(srcForBarrel, barrelResult, mapX, mapY,
                              Imgproc.INTER_LANCZOS4, Core.BORDER_REPLICATE,
                              new Scalar(255, 255, 255, 255));
                mapX.release();
                mapY.release();
                srcForBarrel.release();
                current.release();
                current = barrelResult;
            }

            // ── STEP 0: Perspective Transformation ───────────────────────────
            //
            // Per-corner epsilon offsets (εTL, εTR, εBR, εBL) determine how
            // much each corner is "pushed inward", forming a trapezoid that
            // simulates viewing the flat document from a 3D angle.
            //
            // Inverse Mapping: getPerspectiveTransform solves H for the 4
            // correspondence pairs.  warpPerspective internally applies inverse
            // mapping — for every destination pixel it computes H⁻¹ to find the
            // source coordinate.  This prevents fold-over artefacts that occur
            // with naive forward mapping when the Jacobian det(H) → 0.
            //
            // Canvas Expansion: the four source corners are projected through H
            // to compute the bounding box of the warped document.  When the
            // trapezoid corners land outside the original canvas bounds, the
            // output canvas is enlarged and a translation T is prepended so the
            // warped content is never clipped at the frame edge.
            if (perspEnabled) {

                // ── Border strategy for perspective: BORDER_CONSTANT(white) ──
                //
                // The ε-sized border strip around the inset trapezoid represents
                // the table / desk surface on which the document rests — it MUST
                // be filled with white background, not with replicated document
                // pixels.
                //
                // Why BORDER_CONSTANT is now safe (barrel runs FIRST):
                //   The original white-tear artefact was caused by barrel running
                //   AFTER perspective.  Barrel's backward-map sampled the white
                //   BORDER_CONSTANT padding left by the perspective warp, pulling
                //   that white into the document corners.
                //   Since barrel now runs BEFORE perspective on the clean source,
                //   the perspective output is the FINAL geometry step.  Any OOB
                //   lookup from H⁻¹ for pixels outside the trapezoid SHOULD get
                //   white fill — that is the table background, semantically correct.
                //
                // No pre-crop margin is needed here: for pixels inside the
                // trapezoid H⁻¹ always returns valid source coordinates by
                // construction (the 4 dst corners map exactly to the 4 src
                // corners).  Pixels outside the trapezoid are background → white.
                double m   = Math.min(cols - 1, rows - 1);
                double eTL = perspTL * m;
                double eTR = perspTR * m;
                double eBR = perspBR * m;
                double eBL = perspBL * m;

                MatOfPoint2f srcPts = new MatOfPoint2f(
                    new Point(0,          0),
                    new Point(cols - 1,   0),
                    new Point(cols - 1,   rows - 1),
                    new Point(0,          rows - 1)
                );

                MatOfPoint2f dstPts = new MatOfPoint2f(
                    new Point(eTL,              eTL),
                    new Point(cols - 1 - eTR,   eTR),
                    new Point(cols - 1 - eBR,   rows - 1 - eBR),
                    new Point(eBL,              rows - 1 - eBL)
                );

                Mat H = Imgproc.getPerspectiveTransform(srcPts, dstPts);

                // Fix 3 – Edge Padding / Canvas Expansion:
                // Project the 4 source corners through H to find the true
                // bounding box of the warped document in the output plane.
                // If any projected corner is negative or exceeds the original
                // size, enlarge the canvas and prepend a translation T so
                // that the top-left of the bounding box lands at (0,0).
                double[] hData = new double[9];
                H.get(0, 0, hData);
                double[][] srcCorners = {{0, 0}, {cols - 1, 0}, {cols - 1, rows - 1}, {0, rows - 1}};
                double minCX = Double.MAX_VALUE,  minCY = Double.MAX_VALUE;
                double maxCX = -Double.MAX_VALUE, maxCY = -Double.MAX_VALUE;
                for (double[] c : srcCorners) {
                    double wx = hData[0] * c[0] + hData[1] * c[1] + hData[2];
                    double wy = hData[3] * c[0] + hData[4] * c[1] + hData[5];
                    double w  = hData[6] * c[0] + hData[7] * c[1] + hData[8];
                    if (Math.abs(w) < 1e-10) continue;
                    double px = wx / w, py = wy / w;
                    minCX = Math.min(minCX, px); maxCX = Math.max(maxCX, px);
                    minCY = Math.min(minCY, py); maxCY = Math.max(maxCY, py);
                }
                int canvasW = Math.max(cols, (int) Math.ceil(maxCX - minCX) + 1);
                int canvasH = Math.max(rows, (int) Math.ceil(maxCY - minCY) + 1);

                // Prepend translation so no corner is clipped at a negative coord
                Mat T = Mat.eye(3, 3, CvType.CV_64F);
                if (minCX < 0) T.put(0, 2, -minCX);
                if (minCY < 0) T.put(1, 2, -minCY);
                Mat H_adj = new Mat();
                Core.gemm(T, H, 1.0, new Mat(), 0.0, H_adj);
                T.release();
                H.release();

                // Save the 4 document corners in expanded-canvas coordinates.
                // These are the DESTINATION corners of the perspective warp plus
                // any translation offset applied to keep all corners non-negative.
                // Stored here (before rows/cols update) and used in the contact-
                // shadow step to build a geometry-based document mask.
                double txOff = (minCX < 0 ? -minCX : 0.0);
                double tyOff = (minCY < 0 ? -minCY : 0.0);
                savedDocTL = new double[]{ eTL + txOff,              eTL + tyOff };
                savedDocTR = new double[]{ (cols - 1 - eTR) + txOff, eTR + tyOff };
                savedDocBR = new double[]{ (cols - 1 - eBR) + txOff, (rows - 1 - eBR) + tyOff };
                savedDocBL = new double[]{ eBL + txOff,              (rows - 1 - eBL) + tyOff };

                Mat warped = new Mat();
                Imgproc.warpPerspective(current, warped, H_adj, new Size(canvasW, canvasH),
                                        Imgproc.INTER_LANCZOS4,
                                        Core.BORDER_CONSTANT,
                                        new Scalar(255, 255, 255, 255));
                H_adj.release();
                srcPts.release();
                dstPts.release();
                current.release();
                current = warped;

                // Downstream steps must use the (possibly expanded) canvas dimensions
                rows = current.rows();
                cols = current.cols();
            }

            // ── STEP 0b: Micro Z-axis Tilt ───────────────────────────────────
            //
            // A sub-degree in-plane rotation (default 0.5°) delivers the
            // minimum 3-D realism cue: one side of the document appears
            // fractionally "taller" than the other, exactly as in a handheld
            // photograph.  Standard affine rotation matrix R(θ) is applied
            // around the image centre; INTER_LANCZOS4 preserves text sharpness
            // and BORDER_REPLICATE fills the tiny corner gaps from rotation.
            if (microTiltEnabled && Math.abs(microTiltDeg) > 0.001) {
                double rcx  = cols / 2.0;
                double rcy  = rows / 2.0;
                Mat rotMat  = Imgproc.getRotationMatrix2D(new Point(rcx, rcy), microTiltDeg, 1.0);
                Mat rotated = new Mat();
                Imgproc.warpAffine(current, rotated, rotMat, new Size(cols, rows),
                                   Imgproc.INTER_LANCZOS4, Core.BORDER_REPLICATE, Scalar.all(0));
                rotMat.release();
                current.release();
                current = rotated;
            }

            // ── STEP 2: Additive Fill-Light — Directional Sunlight Gradient ──
            //
            // LIGHTING MODEL FIX — "Morning Desk, Top-Left Light":
            //
            // Previous implementation (Gaussian hotspot + sigmoid roll-off):
            //   v_raw = exp(−r²/2σ²)  →  sigmoid  →  multiply
            //   Problem: shadows approach absolute black → looks like a flashlight
            //   in a dark room, not natural morning sunlight.
            //
            // Corrected implementation (Additive Fill Light):
            //
            //   Natural sunlight from a window is NOT a point-light hotspot.
            //   It is a large-area directional source (sun + sky dome) whose
            //   intensity falls off gently across the document — more like a
            //   cosine lobe than a Gaussian bell.
            //
            //   Step A — Directional component (soft linear gradient):
            //     The light vector from the source (lightX, lightY) to the
            //     opposite corner gives a direction.  Per-pixel cosine similarity
            //     with that direction produces a linear ramp:
            //
            //       dir_x = 1 − lightX,  dir_y = 1 − lightY    (norm vec)
            //       v_dir(x,y) = dot((x/W, y/H), (dir_x, dir_y)) / |dir|
            //                    clamped to [0,1]
            //
            //     This ramps from 0.0 at the light source to 1.0 at the
            //     opposite corner — a clean linear gradient matching window light.
            //
            //   Step B — Gaussian halo (soft local hotspot, wide σ):
            //     A very wide Gaussian at the light position adds a gentle local
            //     brightening without a sharp hotspot:
            //
            //       v_gauss(x,y) = exp(−r²/(2σ²)),  σ = lightSigma · halfDiag
            //
            //   Step C — Combine and apply Additive Fill-Light floor:
            //     v_combined = 0.65 · v_gauss + 0.35 · (1 − v_dir)
            //
            //     Additive fill floor (the fix):
            //       mask(x,y) = max(v_combined, lightMin)
            //       → lightMin = 0.40 ensures the bottom-right corner is always
            //         visible ("flat"), matching a desk lit by ambient room light.
            //
            //     Final multiplicative model:
            //       pixel_out = pixel_in · lerp(lightMin, lightMax, mask)
            //
            if (lightEnabled && lightSigma > 0.001) {

                double lx     = lightX * (cols - 1);
                double ly     = lightY * (rows - 1);

                // Safety boundary: reject NaN/Inf that could arise from malformed
                // params, and cap values to ±150 % of the image dimension so the
                // Gaussian can never receive a dx² / dy² argument large enough to
                // cause IEEE 754 edge behaviour.  Off-canvas presets (beach:
                // lightX = 1.40, i.e. lx ≈ 1.40 × W) are well within this bound.
                if (!Double.isFinite(lx)) lx = (cols - 1) * 0.5;
                if (!Double.isFinite(ly)) ly = (rows - 1) * 0.5;
                lx = Math.max(-(double)cols * 0.5, Math.min((double)cols * 1.5, lx));
                ly = Math.max(-(double)rows * 0.5, Math.min((double)rows * 1.5, ly));

                double halfD  = Math.sqrt((double)cols * cols + (double)rows * rows) / 2.0;
                double sigma  = lightSigma * halfD;
                double inv2s2 = 1.0 / (2.0 * sigma * sigma);

                // Directional gradient unit vector (from light toward opposite corner)
                double dirX = 1.0 - lightX;
                double dirY = 1.0 - lightY;
                double dirLen = Math.sqrt(dirX * dirX + dirY * dirY);
                if (dirLen < 1e-6) dirLen = 1.0;
                dirX /= dirLen;
                dirY /= dirLen;

                Mat lightMask = new Mat(rows, cols, CvType.CV_32F);
                for (int y = 0; y < rows; y++) {
                    double dy  = y - ly;
                    double dy2 = dy * dy;
                    // Normalised position in image [0,1] × [0,1]
                    double yN  = (double) y / Math.max(rows - 1, 1);
                    float[] rowData = new float[cols];
                    for (int x = 0; x < cols; x++) {
                        double dx   = x - lx;
                        double xN   = (double) x / Math.max(cols - 1, 1);

                        // Gaussian halo: wide and soft
                        double vGauss = Math.exp(-(dx * dx + dy2) * inv2s2);

                        // Directional linear ramp: 0 at source, 1 at far corner
                        double vDir = Math.max(0.0, Math.min(1.0,
                                xN * dirX + yN * dirY));

                        // Combine: 65 % Gaussian halo + 35 % directional ramp
                        // The ramp ensures the gradient looks like a window, not a bulb.
                        // Clamp to [0,1] — vGauss can theoretically exceed 1.0 via floating-point
                        // and extreme off-canvas light positions could produce negative vCombined.
                        double vCombined = Math.max(0.0, Math.min(1.0,
                                0.65 * vGauss + 0.35 * (1.0 - vDir)));

                        // Fill-light model: lerp(lightMin, lightMax, t)
                        // vCombined is the pure [0,1] interpolation weight t.
                        // BUG FIX: previously a max(vCombined, lightMin) was applied BEFORE
                        // the lerp, making lightMin act as the t-value instead of 0 at the
                        // darkest corner — effectively double-applying the floor and raising
                        // the minimum mask to (lightMin + lightMin*(lightMax-lightMin)) ≈ 0.64
                        // instead of the intended lightMin = 0.40.
                        // Correct: use vCombined directly as t; lightMin is the lerp baseline.
                        double v = lightMin + (lightMax - lightMin) * vCombined;
                        // The floor is now implicit: when vCombined=0, v = lightMin exactly.
                        rowData[x] = (float) v;
                    }
                    lightMask.put(y, 0, rowData);
                }

                // Strict mask clamp: ensure no multiplier value survives above the
                // intended ceiling or below zero before it reaches Core.multiply.
                // This is the first defence line — the safety clamp on litF after
                // the multiply is the second.  Both are needed because floating-point
                // rounding in the lerp (lightMin + range × vCombined) can produce
                // values infinitesimally outside [0, lightMax] at the extremes.
                double maskCeiling = Math.max(lightMax, 1.0);
                Core.max(lightMask, Scalar.all(0.0),         lightMask);
                Core.min(lightMask, Scalar.all(maskCeiling), lightMask);

                Mat maskC    = replicateToChannels(lightMask, ch);
                lightMask.release();

                Mat currentF = new Mat();
                current.convertTo(currentF, CvType.CV_32F);
                Mat litF = new Mat();
                Core.multiply(currentF, maskC, litF);
                currentF.release();
                maskC.release();

                // Ambient light floor: in a real room, reflected bounce light
                // from walls and surfaces ensures no shadow is ever pure black.
                // Clamping every pixel to at least ambientFloor × 255 recreates
                // that effect — the image histogram never hits 0 in the shadows.
                if (ambientFloor > 0.0) {
                    Mat floorMat   = new Mat(litF.size(), litF.type(),
                                            Scalar.all(ambientFloor * 255.0));
                    Mat litFloored = new Mat();
                    Core.max(litF, floorMat, litFloored);
                    floorMat.release();
                    litF.release();
                    litF = litFloored;
                }

                // Additive exposure offset — lifts or lowers global brightness
                // in float32 space (values in [0, 255] scale).
                // Sunny beach: exposureOffset ~= 10–20 to simulate high-key exposure.
                if (Math.abs(exposureOffset) > 1e-6) {
                    Core.add(litF, Scalar.all(exposureOffset), litF);
                }

                // Soft-knee Reinhard tone mapping — compresses overdrive from lightMax > 1.0
                // back toward whiteLevel without hard-clipping.
                //
                // BUG FIX (grey-arc solarization):
                // The previous implementation used kneeStart = 0.85 × whiteLevel = 216.75.
                // With default lightMax = 1.0, a white pixel (255) satisfies 255 > 216.75,
                // so Reinhard fires and maps it:
                //   excess = 255 − 216.75 = 38.25
                //   out = 216.75 + 38.25 / (1 + 38.25/38.25) = 235.88   ← WHITE → GREY!
                // This created grey arc artefacts at the lit hotspot.
                //
                // Corrected model:
                //   - Guard: only run when lightMax > 1.0 (i.e. pixels CAN exceed whiteLevel).
                //     When lightMax ≤ 1.0, max possible output is exactly whiteLevel — there
                //     is nothing to compress and Reinhard would only damage the image.
                //   - Knee sits at whiteLevel itself: values ≤ 255 are IDENTITY; only
                //     true overdrive (val > whiteLevel) is compressed.
                //   - kneeRange = (lightMax − 1) × whiteLevel = the expected overshoot band,
                //     so the curve is calibrated to the actual dynamic range in use.
                //
                //   out = whiteLevel + excess / (1 + excess / kneeRange)   for val > whiteLevel
                //   Asymptote → whiteLevel + kneeRange = lightMax × whiteLevel  (soft ceiling).
                if (reinhardEnabled && lightMax > 1.0) {
                    double kneeRange = Math.max(1.0, (lightMax - 1.0) * whiteLevel);
                    for (int ry = 0; ry < rows; ry++) {
                        float[] litRow = new float[cols * ch];
                        litF.get(ry, 0, litRow);
                        for (int i = 0; i < litRow.length; i++) {
                            double val = litRow[i];
                            if (val > whiteLevel) {
                                double excess = val - whiteLevel;
                                val = whiteLevel + excess / (1.0 + excess / kneeRange);
                            }
                            litRow[i] = (float) val;
                        }
                        litF.put(ry, 0, litRow);
                    }
                }
                // Safety clamp: guarantee all float values are in [0, whiteLevel] before
                // the convertTo(CV_8U) cast — catches residual overflows from exposureOffset
                // or any future path that could exceed the nominal range.
                Core.min(litF, Scalar.all(whiteLevel), litF);
                Core.max(litF, Scalar.all(0.0), litF);

                Mat lit = new Mat();
                litF.convertTo(lit, current.type());
                litF.release();
                current.release();
                current = lit;
            }

            // ── STEP 3: Depth of Field (Linear Ramp + Lerp Blur) ─────────────
            //
            // The aperture model: regions of the document "farther from the
            // lens" are less in focus.  A row-wise linear ramp M(y) drives
            // the interpolation between a sharp and a Gaussian-blurred version:
            //
            //   M(y) = y / (H − 1)               0 at top → sharp
            //                                     1 at bottom → blurred
            //   Output(x,y) = (1 − M(y)) · sharp(x,y) + M(y) · blurred(x,y)
            //
            // dofInvert=true flips the ramp so the top edge is blurred instead.
            // dofTopFraction limits how far down the ramp extends (default 1.0 =
            // full height; 0.10 = only the top 10 % is blurred, rest stays sharp).
            // This lets sunny-beach scenes have a sky-blur at the top without
            // blurring the entire document.
            if (dofEnabled && dofBlurSigma >= 0.5) {

                int kSize = Math.max(3, 2 * (int) Math.ceil(3.0 * dofBlurSigma) + 1);
                if (kSize % 2 == 0) kSize++;

                Mat blurred = new Mat();
                Imgproc.GaussianBlur(current, blurred, new Size(kSize, kSize), dofBlurSigma);

                // Linear ramp alpha mask — one value per row, broadcast to all columns.
                // dofTopFraction < 1.0 clamps the ramp zone so only that top fraction
                // of the image is affected (remaining rows get alpha = 0 → fully sharp).
                Mat alphaMat = new Mat(rows, cols, CvType.CV_32F);
                for (int y = 0; y < rows; y++) {
                    double alpha;
                    if (dofInvert) {
                        // Blur at top: ramp from 1.0 (y=0) down to 0 within dofTopFraction
                        double zonedY = (double) y / Math.max(rows * dofTopFraction - 1, 1);
                        alpha = Math.max(0.0, 1.0 - zonedY);
                    } else {
                        // Blur at bottom: standard full-height ramp (dofTopFraction ignored)
                        alpha = (double) y / Math.max(rows - 1, 1);
                    }
                    alphaMat.row(y).setTo(new Scalar(alpha));
                }

                Mat invAlpha = new Mat(rows, cols, CvType.CV_32F);
                Mat ones     = Mat.ones(alphaMat.size(), CvType.CV_32F);
                Core.subtract(ones, alphaMat, invAlpha);
                ones.release();

                Mat alphaC    = replicateToChannels(alphaMat,  ch);
                Mat invAlphaC = replicateToChannels(invAlpha,  ch);
                alphaMat.release();
                invAlpha.release();

                Mat sharpF   = new Mat();  current.convertTo(sharpF,   CvType.CV_32F);
                Mat blurredF = new Mat();  blurred.convertTo(blurredF, CvType.CV_32F);
                blurred.release();

                Mat sharpW   = new Mat();  Core.multiply(sharpF,   invAlphaC, sharpW);
                Mat blurredW = new Mat();  Core.multiply(blurredF, alphaC,    blurredW);
                Mat resultF  = new Mat();  Core.add(sharpW, blurredW, resultF);
                sharpF.release();    blurredF.release();
                sharpW.release();    blurredW.release();
                alphaC.release();    invAlphaC.release();

                Mat dofResult = new Mat();
                resultF.convertTo(dofResult, current.type());
                resultF.release();
                current.release();
                current = dofResult;
            }

            // ── STEP 4: Surface Topography — Perlin Noise Height Map ─────────
            //
            // Multi-octave fBm Perlin Noise produces the height map N(x,y).
            // The finite-difference gradient gives the normal vector at each
            // pixel: n⃗ = (∂N/∂x, ∂N/∂y, 1) (unnormalised).
            // Phong shading against the same virtual light (lightX, lightY)
            // generates a multiplicative grain field:
            //   I = perlinAmbient + (1−perlinAmbient) · (n⃗ · L⃗_norm)
            // The final mask is scaled by perlinStrength and added to 1.0 so
            // the image is brightened/darkened by the micro-surface relief.
            if (perlinEnabled && perlinStrength > 1e-6) {

                // Build height map via fBm Perlin (value noise)
                float[] height = new float[rows * cols];
                for (int oct = 0; oct < perlinOctaves; oct++) {
                    double freq = perlinScale * Math.pow(2.0, oct);
                    double amp  = 1.0 / Math.pow(2.0, oct);
                    for (int y = 0; y < rows; y++) {
                        for (int x = 0; x < cols; x++) {
                            height[y * cols + x] += (float)(amp * smoothNoise(
                                    x * freq + noiseOffX, y * freq + noiseOffY));
                        }
                    }
                }

                // Normalise height to [0,1]
                float hMin = height[0], hMax = height[0];
                for (float v : height) { if (v < hMin) hMin = v; if (v > hMax) hMax = v; }
                float hRange = Math.max(hMax - hMin, 1e-6f);
                for (int i = 0; i < height.length; i++) height[i] = (height[i] - hMin) / hRange;

                // Light vector from (lightX, lightY) in image coords → normalise to unit
                double lxN  = lightX * (cols - 1);
                double lyN  = lightY * (rows - 1);
                double lzN  = Math.sqrt((double)cols * cols + (double)rows * rows) * 0.5;
                double lLen = Math.sqrt(lxN * lxN + lyN * lyN + lzN * lzN);
                double lxU  = lxN / lLen, lyU = lyN / lLen, lzU = lzN / lLen;

                Mat grainMask = new Mat(rows, cols, CvType.CV_32F);
                for (int y = 0; y < rows; y++) {
                    float[] rowData = new float[cols];
                    for (int x = 0; x < cols; x++) {
                        int i = y * cols + x;
                        // Finite differences for gradient (wrap-clamp)
                        float hR = height[i + (x < cols - 1 ? 1 : 0)];
                        float hL = height[i - (x > 0 ? 1 : 0)];
                        float hD = height[Math.min(y + 1, rows - 1) * cols + x];
                        float hU = height[Math.max(y - 1, 0) * cols + x];
                        double nx = hR - hL;   // ∂N/∂x (unnormalised)
                        double ny = hD - hU;   // ∂N/∂y
                        double nz = 1.0;
                        double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
                        nx /= nLen; ny /= nLen; nz /= nLen;
                        // Phong: ambient + diffuse
                        double dot = Math.max(0.0, nx * lxU + ny * lyU + nz * lzU);
                        double I   = perlinAmbient + (1.0 - perlinAmbient) * dot;
                        // Map to [1-strength, 1+strength]
                        rowData[x] = (float)(1.0 + perlinStrength * (2.0 * I - 1.9));
                    }
                    grainMask.put(y, 0, rowData);
                }

                Mat grainC   = replicateToChannels(grainMask, ch);
                grainMask.release();

                Mat currentF = new Mat();  current.convertTo(currentF, CvType.CV_32F);
                Mat grainedF = new Mat();  Core.multiply(currentF, grainC, grainedF);
                currentF.release(); grainC.release();

                Mat grained = new Mat();
                grainedF.convertTo(grained, current.type());
                grainedF.release();
                current.release();
                current = grained;
            }

            // ── STEP 5: Ink-Substrate Integration (Variable-Kernel Bleed) ────
            //
            // σ_local ∝ |Img(x,y) − paper_white| — darker pixels are ink and
            // bleed more into the fibers.  Implemented as a weighted lerp between
            // the original and a globally Gaussian-blurred version, with per-pixel
            // weight w(x,y) = inkBleedStr · |Img − 255| / 255:
            //
            //   out(x,y) = (1 − w) · original + w · blurred
            //
            // This preserves the centre of text strokes while softening edges.
            if (inkBleedEnabled && inkBleedSigma > 1e-6 && inkBleedStr > 1e-6) {

                int bk = Math.max(3, 2 * (int) Math.ceil(3.0 * inkBleedSigma) + 1);
                if (bk % 2 == 0) bk++;
                Mat bleedBlur = new Mat();
                Imgproc.GaussianBlur(current, bleedBlur, new Size(bk, bk), inkBleedSigma);

                Mat origF  = new Mat();  current.convertTo(origF,  CvType.CV_32F);
                Mat blurF  = new Mat();  bleedBlur.convertTo(blurF, CvType.CV_32F);
                bleedBlur.release();

                // Compute single-channel contrast map
                Mat grey = new Mat();
                if (ch == 1) {
                    origF.copyTo(grey);
                } else {
                    Imgproc.cvtColor(origF, grey, Imgproc.COLOR_BGR2GRAY);
                }

                Mat weightMat = new Mat(rows, cols, CvType.CV_32F);
                for (int y = 0; y < rows; y++) {
                    float[] rowData = new float[cols];
                    float[] greyRow = new float[cols];
                    grey.get(y, 0, greyRow);
                    for (int x = 0; x < cols; x++) {
                        // Distance from paper-white (255): normalised [0,1]
                        double w = inkBleedStr * Math.abs(255.0 - greyRow[x]) / 255.0;
                        if (w > 1.0) w = 1.0;
                        rowData[x] = (float) w;
                    }
                    weightMat.put(y, 0, rowData);
                }
                grey.release();

                Mat wC    = replicateToChannels(weightMat, ch);
                Mat invWC = new Mat();
                Mat onesW = Mat.ones(weightMat.size(), CvType.CV_32F);
                Mat invW1 = new Mat();  Core.subtract(onesW, weightMat, invW1);
                onesW.release(); weightMat.release();
                invWC = replicateToChannels(invW1, ch);
                invW1.release();

                Mat sharpPart  = new Mat(); Core.multiply(origF,  invWC, sharpPart);
                Mat blurPart   = new Mat(); Core.multiply(blurF,  wC,    blurPart);
                Mat bleedResF  = new Mat(); Core.add(sharpPart, blurPart, bleedResF);
                origF.release(); blurF.release();
                sharpPart.release(); blurPart.release();
                wC.release(); invWC.release();

                Mat bleedResult = new Mat();
                bleedResF.convertTo(bleedResult, current.type());
                bleedResF.release();
                current.release();
                current = bleedResult;
            }

            // ── STEP 6: Specular Micro-Highlights (Schlick / Fresnel) ────────
            //
            // Paper edges tilted away from the camera appear slightly brighter
            // due to the Fresnel Effect.  Schlick approximation:
            //
            //   R(θ) = R₀ + (1−R₀)(1−cosθ)⁵
            //
            // cosθ is derived from the per-pixel normal (rebuilt from the same
            // Perlin height concept) dotted with the view vector (0,0,1).
            // Here we use the image-space gradient of a smoothed greyscale
            // as a proxy for the surface normal — dark areas are "valleys",
            // bright areas "peaks".  The highlight is added additively scaled
            // by fresnelStrength.
            if (fresnelEnabled && fresnelStrength > 1e-6) {

                Mat greyF = new Mat();
                if (ch == 1) {
                    current.convertTo(greyF, CvType.CV_32F);
                } else {
                    Mat tmp = new Mat();
                    Imgproc.cvtColor(current, tmp, Imgproc.COLOR_BGR2GRAY);
                    tmp.convertTo(greyF, CvType.CV_32F);
                    tmp.release();
                }
                // Normalise to [0,1]
                Core.divide(greyF, Scalar.all(255.0), greyF);

                // Smooth height proxy for normals
                int fk = Math.max(3, 2 * (int) Math.ceil(3.0 * 2.0) + 1); // σ=2
                if (fk % 2 == 0) fk++;
                Mat smoothH = new Mat();
                Imgproc.GaussianBlur(greyF, smoothH, new Size(fk, fk), 2.0);
                greyF.release();

                // Sobel gradients → normal proxy
                Mat gx = new Mat(), gy = new Mat();
                Imgproc.Sobel(smoothH, gx, CvType.CV_32F, 1, 0, 3);
                Imgproc.Sobel(smoothH, gy, CvType.CV_32F, 0, 1, 3);
                smoothH.release();

                Mat fresnelMask = new Mat(rows, cols, CvType.CV_32F);
                float[] gxRow = new float[cols], gyRow = new float[cols];
                for (int y = 0; y < rows; y++) {
                    float[] rowData = new float[cols];
                    gx.get(y, 0, gxRow);
                    gy.get(y, 0, gyRow);
                    for (int x = 0; x < cols; x++) {
                        double nx = gxRow[x], ny = gyRow[x], nz = 1.0;
                        double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
                        nz /= nLen;                           // cosθ = n⃗ · (0,0,1)
                        double cosTheta = Math.max(0.0, Math.min(1.0, nz));
                        double R  = fresnelR0 + (1.0 - fresnelR0) * Math.pow(1.0 - cosTheta, 5.0);
                        rowData[x] = (float)(fresnelStrength * R);
                    }
                    fresnelMask.put(y, 0, rowData);
                }
                gx.release(); gy.release();

                // Add specular pass to the image
                Mat fresnelC  = replicateToChannels(fresnelMask, ch);
                fresnelMask.release();
                // Scale to [0,255]
                Core.multiply(fresnelC, Scalar.all(255.0), fresnelC);

                Mat currentF2 = new Mat();  current.convertTo(currentF2, CvType.CV_32F);
                Mat specular  = new Mat();  Core.add(currentF2, fresnelC, specular);
                currentF2.release(); fresnelC.release();

                Mat specResult = new Mat();
                specular.convertTo(specResult, current.type());
                specular.release();
                current.release();
                current = specResult;
            }

            // ── STEP 7: Contact Shadow — SDF-based Ambient Occlusion ─────────
            //
            // ⚠️  BUG-FIX — "Voronoi shadow arc" artifact:
            //
            // The old implementation used Imgproc.threshold on the POST-LIGHTING
            // image (threshold = 245).  The lighting step darkens white paper to
            // ~40–100 % of its value; near the light source the paper stays bright
            // (≥ 245) → classified as BACKGROUND (value 0 in the mask), creating
            // a HOLE.  Remote white-paper regions fell below 245 → classified as
            // DOCUMENT (value 255).  The convex interior of this near-full mask
            // was then ERODED inward from:
            //   (a) the outer image boundary, and
            //   (b) the lit-area hole.
            // Pixels equidistant from both boundaries (the VORONOI BOUNDARY) had
            // the maximum erosion depth → maximum shadow → the visible arc.
            // Additionally, the formula (1 − exp(−k·d²)) grew STRONGER with
            // distance, which is physically backward for a contact shadow.
            //
            // CORRECT IMPLEMENTATION:
            //   1. Build the document polygon from the KNOWN PERSPECTIVE GEOMETRY
            //      (savedDocTL/TR/BR/BL computed during the perspective step).
            //      Completely immune to lighting-induced threshold ambiguity.
            //   2. distanceTransform on the INVERTED mask → distance grows
            //      OUTWARD from the document edge into the background.
            //   3. Formula exp(−k·d²) → shadow strongest at edge (d ≈ 1),
            //      fades smoothly into the background.
            if (contactEnabled && contactDarkness > 1e-6) {

                // Build filled document-polygon mask from the saved corners.
                MatOfPoint docPoly = new MatOfPoint(
                    new Point(savedDocTL[0], savedDocTL[1]),
                    new Point(savedDocTR[0], savedDocTR[1]),
                    new Point(savedDocBR[0], savedDocBR[1]),
                    new Point(savedDocBL[0], savedDocBL[1])
                );
                Mat docMask = Mat.zeros(rows, cols, CvType.CV_8U);
                Imgproc.fillPoly(docMask,
                    java.util.Collections.singletonList(docPoly),
                    new Scalar(255));
                docPoly.release();

                // Invert: background pixels = 255 (distance measured here),
                //         document pixels  = 0  (distance source / boundary).
                Mat invDocMask = new Mat();
                Core.bitwise_not(docMask, invDocMask);
                docMask.release();

                // L2 distance transform: each background pixel gets its exact
                // Euclidean distance to the nearest document boundary pixel.
                Mat distCV = new Mat();
                Imgproc.distanceTransform(invDocMask, distCV,
                                          Imgproc.DIST_L2,
                                          Imgproc.DIST_MASK_PRECISE);
                invDocMask.release();

                // Shadow multiplier: 1 − S(d)
                //   S(d) = contactDarkness · exp(−contactK · d²)
                // d = 0 → inside document → S = 0 → no shadow.
                // d > 0 → background; shadow fades naturally as the Gaussian
                //         decays.  NO hard maxDist cutoff — a hard cutoff at a
                //         fixed pixel radius creates a sharp ring (offset polygon)
                //         around the document that reads as a visible arc artefact.
                //         The Gaussian formula reaches S < 0.001 naturally at
                //         d ≈ sqrt(ln(darkness/0.001) / contactK) ≈ 42 px for
                //         the default contactK=0.004, contactDarkness=0.55.
                Mat shadowMult = new Mat(rows, cols, CvType.CV_32F, Scalar.all(1.0f));
                for (int y = 0; y < rows; y++) {
                    float[] distRow = new float[cols];
                    distCV.get(y, 0, distRow);
                    float[] smRow = new float[cols];
                    shadowMult.get(y, 0, smRow);
                    boolean touched = false;
                    for (int x = 0; x < cols; x++) {
                        double d = distRow[x];
                        if (d > 0.0) {
                            double Sd = contactDarkness * Math.exp(-contactK * d * d);
                            if (Sd > 1e-4) {  // skip computationally insignificant shadow
                                smRow[x] = (float)(1.0 - Sd);
                                touched   = true;
                            }
                        }
                    }
                    if (touched) shadowMult.put(y, 0, smRow);
                }
                distCV.release();

                Mat shadowC   = replicateToChannels(shadowMult, ch);
                shadowMult.release();

                Mat currentF3 = new Mat();  current.convertTo(currentF3, CvType.CV_32F);
                Mat shadowed  = new Mat();  Core.multiply(currentF3, shadowC, shadowed);
                currentF3.release(); shadowC.release();

                Mat shadowResult = new Mat();
                shadowed.convertTo(shadowResult, current.type());
                shadowed.release();
                current.release();
                current = shadowResult;
            }

            // ── STEP 8: Paper Physical-Edge Seam ─────────────────────────────
            //
            // Real paper has a measurable thickness (~0.1 mm).  The top edge
            // of the sheet catches ambient light → a 1-px bright highlight seam;
            // the bottom edge falls in micro-shadow → a 1-px dark seam.
            // Both are detected per-column via luminance thresholding on the
            // current composite, then painted in a single row-scan pass:
            //   top-edge pixel  → pixel + paperEdgeHL   (brighter)
            //   bottom-edge pixel → pixel − paperEdgeSH (darker)
            // This two-pixel strip makes the document read as an object that
            // physically sits on a surface rather than being part of it.
            if (paperEdgeEnabled) {
                Mat greyEdge = new Mat();
                if (ch == 1) {
                    current.copyTo(greyEdge);
                } else {
                    Imgproc.cvtColor(current, greyEdge, Imgproc.COLOR_BGR2GRAY);
                }
                Mat paperMask = new Mat();
                Imgproc.threshold(greyEdge, paperMask, paperEdgeThresh, 255,
                                  Imgproc.THRESH_BINARY);
                greyEdge.release();

                // Read mask into flat byte array for fast column scanning.
                byte[] maskFlat = new byte[rows * cols];
                for (int ey = 0; ey < rows; ey++) {
                    byte[] mr = new byte[cols];
                    paperMask.get(ey, 0, mr);
                    System.arraycopy(mr, 0, maskFlat, ey * cols, cols);
                }
                paperMask.release();

                // Find topmost and bottommost paper pixel per column.
                int[] topEdge    = new int[cols];
                int[] bottomEdge = new int[cols];
                java.util.Arrays.fill(topEdge,    -1);
                java.util.Arrays.fill(bottomEdge, -1);
                for (int ey = 0; ey < rows; ey++) {
                    for (int ex = 0; ex < cols; ex++) {
                        if ((maskFlat[ey * cols + ex] & 0xFF) > 0) {
                            if (topEdge[ex] < 0) topEdge[ex] = ey;
                            bottomEdge[ex] = ey;
                        }
                    }
                }

                // Paint highlight (top) and shadow (bottom) in a single row-scan.
                for (int ey = 0; ey < rows; ey++) {
                    byte[] rowBuf = new byte[cols * ch];
                    current.get(ey, 0, rowBuf);
                    boolean changed = false;
                    for (int ex = 0; ex < cols; ex++) {
                        int base = ex * ch;
                        if (topEdge[ex] == ey) {
                            for (int ec = 0; ec < ch; ec++)
                                rowBuf[base + ec] = (byte) Math.min(255,
                                        (rowBuf[base + ec] & 0xFF) + paperEdgeHL);
                            changed = true;
                        } else if (bottomEdge[ex] == ey) {
                            for (int ec = 0; ec < ch; ec++)
                                rowBuf[base + ec] = (byte) Math.max(0,
                                        (rowBuf[base + ec] & 0xFF) - paperEdgeSH);
                            changed = true;
                        }
                    }
                    if (changed) current.put(ey, 0, rowBuf);
                }
            }

            return current;
        });

    // -----------------------------------------------------------------------
    // Replicates a single-channel CV_32F Mat across n channels via merge.
    // Returns a clone immediately when n == 1.
    // -----------------------------------------------------------------------
    private static Mat replicateToChannels(Mat singleChannel, int n) {
        if (n == 1) return singleChannel.clone();
        List<Mat> channels = new ArrayList<>(n);
        for (int i = 0; i < n; i++) channels.add(singleChannel);
        Mat merged = new Mat();
        Core.merge(channels, merged);
        return merged;
    }

    // -----------------------------------------------------------------------
    // Smooth (value) noise — simple lattice noise with cosine interpolation,
    // used as a single octave for the Perlin-style height map in step 4.
    // Returns a value in roughly [−1, 1].
    // -----------------------------------------------------------------------
    private static double smoothNoise(double x, double y) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        double fx = x - ix;
        double fy = y - iy;
        // Quintic ease: 6t⁵ − 15t⁴ + 10t³  (smooth Perlin fade)
        double ux = fx * fx * fx * (fx * (fx * 6.0 - 15.0) + 10.0);
        double uy = fy * fy * fy * (fy * (fy * 6.0 - 15.0) + 10.0);

        double n00 = lattice(ix,     iy    );
        double n10 = lattice(ix + 1, iy    );
        double n01 = lattice(ix,     iy + 1);
        double n11 = lattice(ix + 1, iy + 1);

        return lerp(lerp(n00, n10, ux), lerp(n01, n11, ux), uy);
    }

    /** Pseudo-random lattice value in [−1, 1] from integer coords. */
    private static double lattice(int ix, int iy) {
        int h = ix * 1619 + iy * 31337;
        h = (h << 13) ^ h;
        h = h * (h * h * 15731 + 789221) + 1376312589;
        return 1.0 - ((h & 0x7fffffff) / 1073741824.0); // [−1, 1]
    }

    private static double lerp(double a, double b, double t) { return a + t * (b - a); }

    public Mat applyOperation(String operationName, Mat input, Map<String, Object> params) {
        return operationRegistry.getOrDefault(operationName.toLowerCase(), (s, p) -> s)
                                .apply(input, params);
    }

    // ── parameter helpers ──────────────────────────────────────────────────

    private static double dblParam(Map<String, Object> p, String key, double def) {
        Object v = p.get(key);
        return v == null ? def : ((Number) v).doubleValue();
    }

    private static boolean boolParam(Map<String, Object> p, String key, boolean def) {
        Object v = p.get(key);
        return v == null ? def : Boolean.parseBoolean(v.toString());
    }
}
