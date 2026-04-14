package com.capo.sub_agent_visual_effects.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import org.opencv.core.Mat;
import org.springframework.stereotype.Service;

@Service
public class ProbabilisticBorderEngine {

    // -----------------------------------------------------------------------
    // Probability-Density-Driven Border Damage Engine
    //
    // Instead of detecting paper boundaries via image segmentation (hull model),
    // this engine places "damage anchors" geometrically using a combined
    // probability density W(x,y) = P(r) · P(θ), then applies a Perlin-noise-
    // perturbed SDF blob ("bite") at each anchor.
    //
    // Mathematical model
    // ──────────────────
    //   Let (cx, cy) = image centre,  Rmax = dist(centre, farthest corner)
    //   For each candidate pixel (px, py):
    //
    //   r     = sqrt((px−cx)² + (py−cy)²)
    //   θ     = atan2(py−cy, px−cx)
    //
    //   P(r)  = (r / Rmax)^k                          [Radial Power Function]
    //           k=1 → linear increase toward edges/corners
    //           k=3 → probability strongly concentrated at extremities
    //
    //   P(θ)  = max(0, 1 + A·sin(n·θ + φ))            [Angular Handling Bias]
    //           n=4 → four preferred wear spots (corners)
    //           A=0.3 → 30 % bias toward preferred angles; 0 = isotropic
    //           φ = random phase so damage never looks perfectly regular
    //
    //   W(x,y) = P(r) · P(θ)                          [Combined Weight ∈ [0,1]]
    //
    // Poisson-Disk Anchor Selection
    // ─────────────────────────────
    //   Candidates are sampled at random.  A candidate (px, py) is accepted as
    //   a damage anchor when:
    //     (1) r > 0.40·Rmax          — restricted to the outer ring
    //     (2) rand(0,1) < W · T      — probability-weighted acceptance (T = threshold)
    //     (3) dist(cand, aᵢ) > minDist  for ALL existing anchors aᵢ
    //                                — Poisson-disk spacing constraint
    //   Condition (3) prevents visual clustering and ensures organic coverage.
    //
    // SDF Bite at Each Anchor
    // ────────────────────────
    //   Each anchor (ax, ay) owns a bite radius aR (scaled with r so corner
    //   anchors produce larger bites than edge-center anchors):
    //
    //     aR = biteRadius · (0.70 + 0.60·(r/Rmax)) · varFactor
    //          varFactor ∈ [1−biteVariance, 1+biteVariance]  (random per anchor)
    //
    //   For every pixel (px, py) within anchor's scan region:
    //
    //     dist      = sqrt((px−ax)² + (py−ay)²)
    //     noiseVal  = Perlin2D(px·s, py·s)           ∈ [−1, 1]
    //     distEff   = dist · max(0.3, 1 − biteNoise·noiseVal)
    //                 [SDF distortion: Perlin warps the circular level-set into
    //                  an organic, non-round bite boundary]
    //
    //     if distEff < aR − feather : factor = 0  (fully background)
    //     elif distEff < aR         : factor = 1 − smoothStep((aR−distEff)/feather)
    //     else                      : no effect
    //
    //   Multiple anchors compound via factor_pixel = min(factor_anchor₁, …)
    //   → overlapping bites at corners produce maximum damage depth.
    //
    //   Final composite:
    //     pixel = src · factor + bg · (1 − factor)
    //
    // params:
    //   k           (double, 3.0)  — radial concentration exponent
    //   A           (double, 0.30) — angular bias amplitude [0=isotropic]
    //   n           (int,    4)    — preferred wear-spot count (4 = corners)
    //   phi         (double, -1)   — angular phase; −1 = random per call
    //   maxAnchors  (int,    35)   — target anchor count
    //   minDist     (int,    60)   — minimum pixels between any two anchors
    //   threshold   (double, 0.55) — acceptance threshold T
    //   biteRadius  (int,    65)   — base bite radius in pixels
    //   biteVariance(double, 0.45) — radius variance ±fraction
    //   biteNoise   (double, 0.40) — Perlin amplitude for bite boundary warp
    //   featherPx   (int,    20)   — feather blend width at bite edge
    //   bgR/G/B     (int,   255)   — background fill colour (default white)
    //   seed        (int,    -1)   — RNG seed; −1 = truly random each call
    // -----------------------------------------------------------------------

    private final Map<String, BiFunction<Mat, Map<String, Object>, Mat>> operationRegistry =
        Map.of("probabilisticborder", (src, params) -> {

            double k            = ((Number) params.getOrDefault("k",            3.0 )).doubleValue();
            double A            = ((Number) params.getOrDefault("A",            0.30)).doubleValue();
            int    n            = ((Number) params.getOrDefault("n",            4   )).intValue();
            double phiParam     = ((Number) params.getOrDefault("phi",         -1.0 )).doubleValue();
            int    maxAnchors   = ((Number) params.getOrDefault("maxAnchors",   35  )).intValue();
            int    minDistPx    = ((Number) params.getOrDefault("minDist",      60  )).intValue();
            double threshold    = ((Number) params.getOrDefault("threshold",    0.55)).doubleValue();
            int    biteRadius   = ((Number) params.getOrDefault("biteRadius",   65  )).intValue();
            double biteVariance = ((Number) params.getOrDefault("biteVariance", 0.45)).doubleValue();
            double biteNoise    = ((Number) params.getOrDefault("biteNoise",    0.40)).doubleValue();
            int    featherPx    = ((Number) params.getOrDefault("featherPx",    20  )).intValue();
            int    bgR          = ((Number) params.getOrDefault("bgR",         255  )).intValue();
            int    bgG          = ((Number) params.getOrDefault("bgG",         255  )).intValue();
            int    bgB          = ((Number) params.getOrDefault("bgB",         255  )).intValue();
            int    seedVal      = ((Number) params.getOrDefault("seed",         -1  )).intValue();

            int rows = src.rows();
            int cols = src.cols();
            int ch   = src.channels();

            Random rng = (seedVal < 0) ? new Random() : new Random(seedVal);

            // Resolve phi: negative sentinel → randomise phase so the four
            // preferred "handling" directions differ on every call
            double phi = (phiParam < 0) ? rng.nextDouble() * 2.0 * Math.PI : phiParam;

            double cx   = cols / 2.0;
            double cy   = rows / 2.0;
            double rmax = Math.sqrt(cx * cx + cy * cy);

            // Perlin noise table for SDF bite-boundary distortion
            int[]  perm       = buildPermTable(rng);
            // noiseScale: ~12 noise cycles across the longest dimension
            // → period ≈ max(rows,cols)/12 px; gives visible organic texture
            double noiseScale = 12.0 / Math.max(rows, cols);

            // ── Stage 1: Poisson-disk-biased anchor sampling ─────────────────
            // Each anchor: double[3] = { ax, ay, biteR }
            List<double[]> anchors   = new ArrayList<>();
            int            maxTries  = maxAnchors * 120;
            // Only consider candidates that lie in the outer 60 % by radius
            // → bites always originate from near the image perimeter
            double         minR      = 0.40 * rmax;
            long           minDistSq = (long) minDistPx * minDistPx;

            for (int att = 0; att < maxTries && anchors.size() < maxAnchors; att++) {
                double px = rng.nextDouble() * cols;
                double py = rng.nextDouble() * rows;
                double dx = px - cx;
                double dy = py - cy;
                double r  = Math.sqrt(dx * dx + dy * dy);

                // Enforce outer-ring constraint
                if (r < minR) continue;

                // ── Radial Power Function P(r) ────────────────────────────────
                double Pr = Math.pow(r / rmax, k);

                // ── Angular Handling Bias P(θ) ────────────────────────────────
                // n=4 boosts the four diagonal directions (corners) relative to
                // the cardinal directions (edge centres).
                double theta = Math.atan2(dy, dx);
                double Pt    = Math.max(0.0, 1.0 + A * Math.sin(n * theta + phi));

                // Combined weight W(px,py) ∈ [0, 1+A]
                // We test rand < W·T so divide W by (1+A) to normalise to [0,1]
                double W = Pr * Pt / (1.0 + A);

                // Probability-weighted acceptance
                if (rng.nextDouble() >= W * threshold) continue;

                // ── Poisson-disk spacing check ────────────────────────────────
                // dist(S_i, S_j) > minDist  for all existing anchors S_j
                boolean tooClose = false;
                for (double[] a : anchors) {
                    double adx = px - a[0], ady = py - a[1];
                    if (adx * adx + ady * ady < minDistSq) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) continue;

                // ── Bite radius: grows with radial distance ───────────────────
                // Anchors deeper into the outer ring produce larger bites.
                // r/rmax ∈ [0.40, 1.0] → scale ∈ [0.94, 1.30]
                double baseR     = biteRadius * (0.70 + 0.60 * (r / rmax));
                double varFactor = 1.0 - biteVariance + 2.0 * biteVariance * rng.nextDouble();
                double aR        = baseR * varFactor;

                anchors.add(new double[]{px, py, aR});
            }

            if (anchors.isEmpty()) return src.clone();

            // ── Stage 2: Build per-pixel blend-factor map ────────────────────
            // factorMap[r][c] ∈ [0,1]:
            //   1.0 → pixel fully original
            //   0.0 → pixel fully replaced by background
            // We only update cells within each anchor's bounding box, making
            // this O(anchorCount × (2·biteR)²) rather than O(rows×cols×anchors).
            float[][] factorMap = new float[rows][cols];
            for (float[] row : factorMap) Arrays.fill(row, 1.0f);

            double feather = Math.max(1.0, featherPx);

            for (double[] anchor : anchors) {
                double ax  = anchor[0];
                double ay  = anchor[1];
                double aR  = anchor[2];
                // Clamp effective feather so it never exceeds the bite radius
                double ef  = Math.min(feather, aR * 0.90);
                double scan = aR + ef + 1.0;

                int minC   = Math.max(0,       (int)(ax - scan));
                int maxC   = Math.min(cols - 1, (int)(ax + scan));
                int minRow = Math.max(0,        (int)(ay - scan));
                int maxRow = Math.min(rows - 1, (int)(ay + scan));

                for (int r = minRow; r <= maxRow; r++) {
                    for (int c = minC; c <= maxC; c++) {
                        double ddx  = c - ax;
                        double ddy  = r - ay;
                        double dist = Math.sqrt(ddx * ddx + ddy * ddy);

                        // Quick reject: no effect at all beyond scan radius
                        if (dist > aR + ef) continue;

                        // ── SDF Distortion via Perlin noise ───────────────────
                        // Multiplying dist by (1 − biteNoise·noise) warps the
                        // circular iso-distance contour into an organic blob:
                        //   noise > 0 → distEff < dist → pixel "inside" the bite
                        //               boundary expands outward in this direction
                        //   noise < 0 → distEff > dist → boundary contracts inward
                        double noiseVal = perlin2D(c * noiseScale, r * noiseScale, perm);
                        double distEff  = dist * Math.max(0.30, 1.0 - biteNoise * noiseVal);

                        float factor;
                        if (distEff < aR - ef) {
                            // Fully inside bite: background
                            factor = 0.0f;
                        } else if (distEff < aR) {
                            // Feather zone: smooth-step blend from 0 (deep) → 1 (boundary)
                            double t = (aR - distEff) / ef;
                            factor = (float)(1.0 - smoothStep(t));
                        } else {
                            continue; // outside bite — no effect
                        }

                        // Compound multiple overlapping bites: most aggressive wins
                        if (factor < factorMap[r][c]) factorMap[r][c] = factor;
                    }
                }
            }

            // ── Stage 3: Apply factor map — composite with background ─────────
            Mat    dst    = src.clone();
            byte[] rowBuf = new byte[cols * ch];
            double dBG = bgB, dGG = bgG, dRG = bgR; // OpenCV uses BGR order

            for (int r = 0; r < rows; r++) {
                src.get(r, 0, rowBuf);
                boolean changed = false;

                for (int c = 0; c < cols; c++) {
                    float fac = factorMap[r][c];
                    if (fac >= 1.0f) continue; // untouched pixel

                    int    base = c * ch;
                    double sB   = rowBuf[base    ] & 0xFF;
                    double sG   = rowBuf[base + 1] & 0xFF;
                    double sR   = rowBuf[base + 2] & 0xFF;

                    rowBuf[base    ] = clampByte((int) Math.round(sB * fac + dBG * (1.0 - fac)));
                    rowBuf[base + 1] = clampByte((int) Math.round(sG * fac + dGG * (1.0 - fac)));
                    rowBuf[base + 2] = clampByte((int) Math.round(sR * fac + dRG * (1.0 - fac)));
                    changed = true;
                }
                if (changed) dst.put(r, 0, rowBuf);
            }
            return dst;
        });

    public Mat applyOperation(String operationName, Mat input, Map<String, Object> params) {
        return operationRegistry.getOrDefault(operationName.toLowerCase(), (s, p) -> s)
                                .apply(input, params);
    }

    // -----------------------------------------------------------------------
    // Ken Perlin's Improved 2-D Noise (2002).  Returns value in [−1, 1].
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

    /** Fisher-Yates shuffle doubled to 512 entries for wrap-free lookup. */
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

    /** Smooth-step C¹ curve: f(t) = t²(3 − 2t).  Maps [0,1] → [0,1]. */
    private static double smoothStep(double t) { return t * t * (3.0 - 2.0 * t); }
    private static byte   clampByte(int v)     { return (byte) Math.max(0, Math.min(255, v)); }
}
