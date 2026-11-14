package dev.foxphys;

/**
 * Grid alignment of a physics body (the R key in the gravity gun).
 *
 * The orientation becomes the nearest rotation whose axes are world axes (a multiple of 90 degrees
 * about each axis). The position is then shifted so that the lowest corner of the body's bounding box
 * sits on a whole-block line of the lab grid, so the body's cell faces line up with the blocks.
 *
 * Pure math on plain arrays: no Minecraft types, so it can be checked without a game.
 */
public final class GridAlign {
    private GridAlign() {}

    /** Result: centre of mass (lab-local) and quaternion x, y, z, w. */
    public record Pose(float[] position, float[] rotation) {}

    /**
     * @param position  centre of mass, lab-local
     * @param rotation  quaternion x, y, z, w
     * @param offsets   part centres relative to the centre of mass, body frame (may be empty)
     * @param sizes     part sizes, body frame, same length as offsets
     */
    public static Pose align(float[] position, float[] rotation, float[][] offsets, float[][] sizes) {
        double[][] m = rotationMatrix(rotation);
        double[][] p = nearestSignedPermutation(m);
        float[] q = quaternionFrom(p);

        float[] pos = position.clone();
        if (offsets != null && offsets.length > 0) {
            double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            for (int i = 0; i < offsets.length; i++) {
                for (int corner = 0; corner < 8; corner++) {
                    double[] local = new double[3];
                    for (int k = 0; k < 3; k++) {
                        double half = sizes[i][k] / 2.0;
                        local[k] = offsets[i][k] + (((corner >> k) & 1) == 0 ? -half : half);
                    }
                    for (int r = 0; r < 3; r++) {
                        double world = p[r][0] * local[0] + p[r][1] * local[1] + p[r][2] * local[2];
                        lo[r] = Math.min(lo[r], world);
                    }
                }
            }
            for (int r = 0; r < 3; r++) {
                double lowest = position[r] + lo[r];
                double snapped = Math.round(lowest);
                pos[r] = (float) (position[r] + (snapped - lowest));
            }
        }
        return new Pose(pos, q);
    }

    /** Rotation matrix m[row][col] of a quaternion x, y, z, w; column j is body axis j in world space. */
    static double[][] rotationMatrix(float[] q) {
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        double x = q[0] / n, y = q[1] / n, z = q[2] / n, w = q[3] / n;
        return new double[][] {
            {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
            {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
            {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)},
        };
    }

    /**
     * Greedy nearest signed permutation: take the largest remaining entries of the matrix, one per row
     * and column. The result is a proper rotation (determinant +1).
     */
    static double[][] nearestSignedPermutation(double[][] m) {
        double[][] p = new double[3][3];
        boolean[] rowUsed = new boolean[3], colUsed = new boolean[3];
        for (int pick = 0; pick < 3; pick++) {
            int bestR = -1, bestC = -1;
            double best = -1;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    if (rowUsed[r] || colUsed[c]) continue;
                    double v = Math.abs(m[r][c]);
                    if (v > best) {
                        best = v;
                        bestR = r;
                        bestC = c;
                    }
                }
            }
            rowUsed[bestR] = true;
            colUsed[bestC] = true;
            p[bestR][bestC] = m[bestR][bestC] < 0 ? -1 : 1;
        }
        if (determinant(p) < 0) {
            for (int r = 0; r < 3; r++) p[r][0] = -p[r][0];
        }
        return p;
    }

    static double determinant(double[][] a) {
        return a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
                - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
    }

    /** Quaternion x, y, z, w of a proper orthonormal matrix (Shepperd's method). */
    static float[] quaternionFrom(double[][] m) {
        double trace = m[0][0] + m[1][1] + m[2][2];
        double x, y, z, w;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1) * 2;
            w = 0.25 * s;
            x = (m[2][1] - m[1][2]) / s;
            y = (m[0][2] - m[2][0]) / s;
            z = (m[1][0] - m[0][1]) / s;
        } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
            double s = Math.sqrt(1 + m[0][0] - m[1][1] - m[2][2]) * 2;
            w = (m[2][1] - m[1][2]) / s;
            x = 0.25 * s;
            y = (m[0][1] + m[1][0]) / s;
            z = (m[0][2] + m[2][0]) / s;
        } else if (m[1][1] > m[2][2]) {
            double s = Math.sqrt(1 + m[1][1] - m[0][0] - m[2][2]) * 2;
            w = (m[0][2] - m[2][0]) / s;
            x = (m[0][1] + m[1][0]) / s;
            y = 0.25 * s;
            z = (m[1][2] + m[2][1]) / s;
        } else {
            double s = Math.sqrt(1 + m[2][2] - m[0][0] - m[1][1]) * 2;
            w = (m[1][0] - m[0][1]) / s;
            x = (m[0][2] + m[2][0]) / s;
            y = (m[1][2] + m[2][1]) / s;
            z = 0.25 * s;
        }
        double n = Math.sqrt(x * x + y * y + z * z + w * w);
        return new float[] {(float) (x / n), (float) (y / n), (float) (z / n), (float) (w / n)};
    }
}
