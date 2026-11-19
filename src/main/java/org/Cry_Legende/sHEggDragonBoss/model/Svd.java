package org.Cry_Legende.sHEggDragonBoss.model;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Décomposition d'une matrice 3x3 en rotation · échelle · rotation (valeurs singulières, méthode de Jacobi sur AᵀA),
 * pour passer d'une transformation en 16 nombres (export block-display.com) aux quatre champs que le client reçoit.
 * Faite une fois au chargement d'un modèle.
 */
final class Svd {

    private static final double EPSILON = 1.0e-9;

    private Svd() {
    }

    static Affine decompose(Matrix4f m) {
        float w = m.m33() == 0.0f ? 1.0f : m.m33();
        double[][] a = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                a[row][col] = m.get(col, row) / w;
            }
        }
        Vector3f translation = new Vector3f(m.m30() / w, m.m31() / w, m.m32() / w);

        // AᵀA = V · D · Vᵀ
        double[][] b = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += a[k][i] * a[k][j];
                }
                b[i][j] = sum;
            }
        }
        double[][] v = identity();
        jacobi(b, v);
        if (det(v) < 0) {
            for (int k = 0; k < 3; k++) {
                v[k][2] = -v[k][2];
            }
        }

        double[] sigma = new double[3];
        double[][] u = new double[3][3];
        boolean[] present = new boolean[3];
        for (int i = 0; i < 3; i++) {
            sigma[i] = Math.sqrt(Math.max(b[i][i], 0.0));
            if (sigma[i] > EPSILON) {
                for (int r = 0; r < 3; r++) {
                    double sum = 0;
                    for (int k = 0; k < 3; k++) {
                        sum += a[r][k] * v[k][i];
                    }
                    u[r][i] = sum / sigma[i];
                }
                present[i] = true;
            } else {
                sigma[i] = 0.0;
            }
        }
        complete(u, present);
        if (det(u) < 0) {
            int smallest = 0;
            for (int i = 1; i < 3; i++) {
                if (Math.abs(sigma[i]) < Math.abs(sigma[smallest])) {
                    smallest = i;
                }
            }
            sigma[smallest] = -sigma[smallest];
            for (int r = 0; r < 3; r++) {
                u[r][smallest] = -u[r][smallest];
            }
        }

        Quaternionf left = new Quaternionf().setFromNormalized(columnMajor(u, false));
        Quaternionf right = new Quaternionf().setFromNormalized(columnMajor(v, true));
        Vector3f scale = new Vector3f((float) sigma[0], (float) sigma[1], (float) sigma[2]);
        return new Affine(translation, left.normalize(), scale, right.normalize());
    }

    /** Diagonalise la matrice symétrique b (en place) et accumule les rotations dans v. */
    private static void jacobi(double[][] b, double[][] v) {
        int[][] pairs = {{0, 1}, {0, 2}, {1, 2}};
        for (int sweep = 0; sweep < 64; sweep++) {
            double off = b[0][1] * b[0][1] + b[0][2] * b[0][2] + b[1][2] * b[1][2];
            if (off < 1.0e-30) {
                return;
            }
            for (int[] pair : pairs) {
                int p = pair[0];
                int q = pair[1];
                if (Math.abs(b[p][q]) < 1.0e-30) {
                    continue;
                }
                double theta = (b[q][q] - b[p][p]) / (2.0 * b[p][q]);
                double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
                if (theta == 0.0) {
                    t = 1.0;
                }
                double c = 1.0 / Math.sqrt(t * t + 1.0);
                double s = t * c;
                for (int k = 0; k < 3; k++) {
                    double kp = b[k][p];
                    double kq = b[k][q];
                    b[k][p] = c * kp - s * kq;
                    b[k][q] = s * kp + c * kq;
                }
                for (int k = 0; k < 3; k++) {
                    double pk = b[p][k];
                    double qk = b[q][k];
                    b[p][k] = c * pk - s * qk;
                    b[q][k] = s * pk + c * qk;
                }
                for (int k = 0; k < 3; k++) {
                    double kp = v[k][p];
                    double kq = v[k][q];
                    v[k][p] = c * kp - s * kq;
                    v[k][q] = s * kp + c * kq;
                }
            }
        }
    }

    /** Complète les colonnes absentes de u (échelle nulle sur un axe) en base orthonormée directe. */
    private static void complete(double[][] u, boolean[] present) {
        int count = (present[0] ? 1 : 0) + (present[1] ? 1 : 0) + (present[2] ? 1 : 0);
        if (count == 3) {
            return;
        }
        if (count == 0) {
            double[][] id = identity();
            for (int r = 0; r < 3; r++) {
                System.arraycopy(id[r], 0, u[r], 0, 3);
            }
            return;
        }
        if (count == 1) {
            int i = present[0] ? 0 : present[1] ? 1 : 2;
            double[] a = column(u, i);
            double[] helper = Math.abs(a[0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
            double[] b = normalize(cross(a, helper));
            setColumn(u, (i + 1) % 3, b);
            present[(i + 1) % 3] = true;
        }
        for (int k = 0; k < 3; k++) {
            if (!present[k]) {
                setColumn(u, k, normalize(cross(column(u, (k + 1) % 3), column(u, (k + 2) % 3))));
                present[k] = true;
            }
        }
    }

    private static double[][] identity() {
        return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    private static double det(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
            - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
            + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    private static double[] column(double[][] m, int c) {
        return new double[]{m[0][c], m[1][c], m[2][c]};
    }

    private static void setColumn(double[][] m, int c, double[] value) {
        for (int r = 0; r < 3; r++) {
            m[r][c] = value[r];
        }
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] a) {
        double length = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return length < EPSILON ? new double[]{1, 0, 0} : new double[]{a[0] / length, a[1] / length, a[2] / length};
    }

    /** Matrice JOML (rangée par colonnes) depuis un tableau [ligne][colonne], transposée si demandé. */
    private static Matrix3f columnMajor(double[][] m, boolean transpose) {
        Matrix3f out = new Matrix3f();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float value = (float) (transpose ? m[col][row] : m[row][col]);
                out.set(col, row, value);
            }
        }
        return out;
    }
}
