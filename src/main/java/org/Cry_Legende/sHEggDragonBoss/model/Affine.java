package org.Cry_Legende.sHEggDragonBoss.model;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Transformation d'une entité d'affichage telle que le client la reçoit : translation, rotation gauche,
 * échelle, rotation droite (matrice = T · G · E · D). Immuable.
 */
public record Affine(Vector3fc translation, Quaternionfc left, Vector3fc scale, Quaternionfc right) {

    // Produit scalaire de deux rotations tenues pour égales (écart sous 0,25°) ; une rotation comparée à elle-même ne
    // donne pas toujours 1 exactement en simple précision
    private static final float SAME_ROTATION = 1 - 2.0e-6f;

    public static final Affine IDENTITY = new Affine(new Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf());

    /** Décompose une matrice affine (ligne du bas 0 0 0 1) comme le fait le jeu pour une transformation en 16 nombres. */
    public static Affine of(Matrix4f matrix) {
        return Svd.decompose(matrix);
    }

    public Matrix4f toMatrix() {
        return new Matrix4f()
            .translation(translation)
            .rotate(left)
            .scale(scale)
            .rotate(right);
    }

    /**
     * Cette transformation placée dans un repère : T(décalage) · rotation · échelle uniforme · this.
     * Sans décomposition : la rotation passe à gauche, l'échelle uniforme se reporte sur l'échelle.
     */
    public Affine placed(Vector3fc offset, Quaternionfc rotation, float uniformScale) {
        Vector3f t = rotation.transform(new Vector3f(translation).mul(uniformScale)).add(offset);
        Quaternionf l = new Quaternionf(rotation).mul(left);
        Vector3f s = new Vector3f(scale).mul(uniformScale);
        return new Affine(t, l, s, right);
    }

    /**
     * Transformation intermédiaire entre celle-ci (k = 0) et « to » (k = 1), calculée comme le client le fait entre
     * deux images clés : translation et échelle en ligne droite, rotations par le plus court chemin.
     */
    public Affine lerp(Affine to, float k) {
        return new Affine(
            new Vector3f(translation).lerp(to.translation, k),
            new Quaternionf(left).slerp(to.left, k),
            new Vector3f(scale).lerp(to.scale, k),
            new Quaternionf(right).slerp(to.right, k));
    }

    /** Même transformation : translation et échelle à la tolérance donnée, rotations à 0,25° près (une rotation et son opposée sont la même). */
    public boolean near(Affine other, float tolerance) {
        return translation.distance(other.translation) <= tolerance
            && scale.distance(other.scale) <= tolerance
            && Math.abs(dot(left, other.left)) >= SAME_ROTATION
            && Math.abs(dot(right, other.right)) >= SAME_ROTATION;
    }

    private static float dot(Quaternionfc a, Quaternionfc b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w();
    }

    /** Même transformation décalée (le décalage n'est pas tourné). */
    public Affine shifted(Vector3fc offset) {
        return new Affine(new Vector3f(translation).add(offset), left, scale, right);
    }
}
