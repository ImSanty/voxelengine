package Models;

import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;

/**
 * Clean, consistent CubeModel.
 * Faces are defined with 6 vertices each (two triangles) in CCW order when
 * looking at the outside.
 */
public class CubeModel {

        // +X face (right) - CCW when looking from +X
        public static final Vector3f[] PX_POS = {
                        new Vector3f(0.5f, 0.5f, 0.5f),
                        new Vector3f(0.5f, -0.5f, 0.5f),
                        new Vector3f(0.5f, -0.5f, -0.5f),
                        new Vector3f(0.5f, 0.5f, 0.5f),
                        new Vector3f(0.5f, -0.5f, -0.5f),
                        new Vector3f(0.5f, 0.5f, -0.5f)
        };

        // -X face (left) - CCW when looking from -X
        public static final Vector3f[] NX_POS = {
                        new Vector3f(-0.5f, 0.5f, -0.5f),
                        new Vector3f(-0.5f, -0.5f, -0.5f),
                        new Vector3f(-0.5f, -0.5f, 0.5f),
                        new Vector3f(-0.5f, 0.5f, -0.5f),
                        new Vector3f(-0.5f, -0.5f, 0.5f),
                        new Vector3f(-0.5f, 0.5f, 0.5f)
        };

        // +Y face (top) - CCW when looking from +Y
        public static final Vector3f[] PY_POS = {
                        new Vector3f(-0.5f, 0.5f, -0.5f),
                        new Vector3f(-0.5f, 0.5f, 0.5f),
                        new Vector3f(0.5f, 0.5f, 0.5f),
                        new Vector3f(-0.5f, 0.5f, -0.5f),
                        new Vector3f(0.5f, 0.5f, 0.5f),
                        new Vector3f(0.5f, 0.5f, -0.5f)
        };

        // -Y face (bottom) - CCW when looking from -Y
        public static final Vector3f[] NY_POS = {
                        new Vector3f(-0.5f, -0.5f, 0.5f),
                        new Vector3f(-0.5f, -0.5f, -0.5f),
                        new Vector3f(0.5f, -0.5f, -0.5f),
                        new Vector3f(-0.5f, -0.5f, 0.5f),
                        new Vector3f(0.5f, -0.5f, -0.5f),
                        new Vector3f(0.5f, -0.5f, 0.5f)
        };

        // +Z face (front) - CCW when looking from +Z
        public static final Vector3f[] PZ_POS = {
                        new Vector3f(-0.5f, 0.5f, 0.5f),
                        new Vector3f(-0.5f, -0.5f, 0.5f),
                        new Vector3f(0.5f, -0.5f, 0.5f),
                        new Vector3f(-0.5f, 0.5f, 0.5f),
                        new Vector3f(0.5f, -0.5f, 0.5f),
                        new Vector3f(0.5f, 0.5f, 0.5f)
        };

        // -Z face (back) - CCW when looking from -Z
        public static final Vector3f[] NZ_POS = {
                        new Vector3f(0.5f, 0.5f, -0.5f),
                        new Vector3f(0.5f, -0.5f, -0.5f),
                        new Vector3f(-0.5f, -0.5f, -0.5f),
                        new Vector3f(0.5f, 0.5f, -0.5f),
                        new Vector3f(-0.5f, -0.5f, -0.5f),
                        new Vector3f(-0.5f, 0.5f, -0.5f)
        };

        // UV ordering per face: a(0,0), b(0,1), c(1,1), a(0,0), c(1,1), d(1,0)
        public static final Vector2f[] FACE_UV = {
                        new Vector2f(0f, 0f),
                        new Vector2f(0f, 1f),
                        new Vector2f(1f, 1f),
                        new Vector2f(0f, 0f),
                        new Vector2f(1f, 1f),
                        new Vector2f(1f, 0f)
        };

        // Default simple atlas tile (can be expanded per-block)
        // Atlas configuration: using a 16x16 tile grid by default.
        private static final float TILE = 1f / 16f;

        private static Vector2f[] tileUV(int tx, int ty) {
                float x0 = tx * TILE;
                float y0 = ty * TILE;
                float x1 = x0 + TILE;
                float y1 = y0 + TILE;
                // UV ordering per face: a(0,0), b(0,1), c(1,1), a(0,0), c(1,1), d(1,0)
                return new Vector2f[] { new Vector2f(x0, y0), new Vector2f(x0, y1), new Vector2f(x1, y1),
                                new Vector2f(x0, y0), new Vector2f(x1, y1), new Vector2f(x1, y0) };
        }

        // Choose tiles: (tx,ty) are chosen so that top, side and bottom use different
        // tiles.
        // These indices may be adjusted to match the actual atlas image.
        // Example mapping: top=(0,0), side=(1,0), bottom=(2,0)
        public static final Vector2f[] UV_PX = tileUV(1, 0); // side
        public static final Vector2f[] UV_NX = UV_PX;
        public static final Vector2f[] UV_PZ = UV_PX;
        public static final Vector2f[] UV_NZ = UV_PX;
        public static final Vector2f[] UV_PY = tileUV(0, 0); // top
        public static final Vector2f[] UV_NY = tileUV(2, 0); // bottom

        // Additional block texture tiles (adjust (tx,ty) to match your atlas):
        public static final Vector2f[] UV_STONE = tileUV(3, 0); // stone all faces
        // Bark: different top to show rings (side at (4,0), top/bottom at (4,1))
        public static final Vector2f[] UV_BARK_SIDE = tileUV(4, 0);
        public static final Vector2f[] UV_BARK_TOP = tileUV(4, 1);
        // Leaves (may contain transparency):
        public static final Vector2f[] UV_LEAF = tileUV(5, 0);

        public static final Vector3f[] NORMALS = {
                        new Vector3f(1f, 0f, 0f),
                        new Vector3f(-1f, 0f, 0f),
                        new Vector3f(0f, 1f, 0f),
                        new Vector3f(0f, -1f, 0f),
                        new Vector3f(0f, 0f, 1f),
                        new Vector3f(0f, 0f, -1f)
        };

        // Legacy flat arrays
        public static final float[] vertices = {
                        -0.5f, 0.5f, -0.5f,
                        -0.5f, -0.5f, -0.5f,
                        0.5f, -0.5f, -0.5f,
                        0.5f, 0.5f, -0.5f,

                        -0.5f, 0.5f, 0.5f,
                        -0.5f, -0.5f, 0.5f,
                        0.5f, -0.5f, 0.5f,
                        0.5f, 0.5f, 0.5f,

                        0.5f, 0.5f, -0.5f,
                        0.5f, -0.5f, -0.5f,
                        0.5f, -0.5f, 0.5f,
                        0.5f, 0.5f, 0.5f,

                        -0.5f, 0.5f, -0.5f,
                        -0.5f, -0.5f, -0.5f,
                        -0.5f, -0.5f, 0.5f,
                        -0.5f, 0.5f, 0.5f,

                        -0.5f, 0.5f, 0.5f,
                        -0.5f, 0.5f, -0.5f,
                        0.5f, 0.5f, -0.5f,
                        0.5f, 0.5f, 0.5f,

                        -0.5f, -0.5f, 0.5f,
                        -0.5f, -0.5f, -0.5f,
                        0.5f, -0.5f, -0.5f,
                        0.5f, -0.5f, 0.5f
        };

        public static final int[] indices = {
                        0, 1, 3,
                        3, 1, 2,
                        4, 5, 7,
                        7, 5, 6,
                        8, 9, 11,
                        11, 9, 10,
                        12, 13, 15,
                        15, 13, 14,
                        16, 17, 19,
                        19, 17, 18,
                        20, 21, 23,
                        23, 21, 22
        };

        public static final float[] uv = {
                        0, 0,
                        0, 1,
                        1, 1,
                        0, 0,
                        1, 1,
                        1, 0,
                        0, 0,
                        0, 1,
                        1, 1,
                        0, 0,
                        1, 1,
                        1, 0,
                        0, 0,
                        0, 1,
                        1, 1,
                        0, 0,
                        1, 1,
                        1, 0,
                        0, 0,
                        0, 1,
                        1, 1,
                        0, 0,
                        1, 1,
                        1, 0
        };

}
