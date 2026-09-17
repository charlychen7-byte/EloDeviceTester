package com.elotouch.devicetester.modules.stability;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Procedurally generated teapot mesh for {@link GpuLoadTask}: the body and lid
 * are one surface of revolution through a Catmull-Rom profile, and the spout
 * and handle are tubes swept along planar Catmull-Rom paths, each with exact
 * analytic normals.
 *
 * <p>Generated rather than shipping Newell's original 32 Bézier patches so the
 * shape is derived from code that can be checked, and so the tessellation can
 * be turned up for load. The total stays under 32768 vertices, which is what
 * lets the index buffer be plain {@code GL_UNSIGNED_SHORT}.
 *
 * <p>Vertices are interleaved as {@code x, y, z, nx, ny, nz}.
 */
final class Teapot {

    static final int FLOATS_PER_VERTEX = 6;
    static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;
    static final int NORMAL_OFFSET_BYTES = 3 * 4;

    private static final int BODY_AROUND = 160;
    private static final int BODY_ALONG = 100;
    private static final int TUBE_AROUND = 64;
    private static final int TUBE_ALONG = 64;

    /** Body + lid + knob as (radius, height) control points, bottom centre upward. */
    private static final float[][] PROFILE = {
            {0.00f, 0.00f}, {0.30f, 0.00f}, {0.55f, 0.02f}, {0.66f, 0.08f},
            {0.76f, 0.20f}, {0.80f, 0.33f}, {0.78f, 0.45f}, {0.70f, 0.58f},
            {0.58f, 0.69f}, {0.47f, 0.77f}, {0.44f, 0.82f}, {0.47f, 0.85f},
            {0.44f, 0.87f}, {0.36f, 0.92f}, {0.22f, 0.97f}, {0.10f, 1.00f},
            {0.09f, 1.03f}, {0.14f, 1.07f}, {0.08f, 1.11f}, {0.00f, 1.12f},
    };

    /** Spout centre-line, starting inside the body wall so the tube merges into it. */
    private static final float[][] SPOUT_PATH = {
            {0.70f, 0.42f}, {1.05f, 0.52f}, {1.30f, 0.72f}, {1.42f, 0.88f},
    };
    private static final float SPOUT_R0 = 0.20f;
    private static final float SPOUT_R1 = 0.035f;

    /** Handle centre-line; both ends sit inside the body wall. */
    private static final float[][] HANDLE_PATH = {
            {-0.50f, 0.72f}, {-1.02f, 0.70f}, {-1.12f, 0.38f}, {-0.68f, 0.26f},
    };
    private static final float HANDLE_R0 = 0.10f;
    private static final float HANDLE_R1 = 0.075f;

    final FloatBuffer vertices;
    final ShortBuffer indices;
    final int vertexCount;
    final int indexCount;

    Teapot() {
        Mesh mesh = new Mesh();
        addRevolution(mesh, sample(PROFILE, BODY_ALONG), BODY_AROUND);
        addTube(mesh, sample(SPOUT_PATH, TUBE_ALONG), SPOUT_R0, SPOUT_R1, TUBE_AROUND);
        addTube(mesh, sample(HANDLE_PATH, TUBE_ALONG), HANDLE_R0, HANDLE_R1, TUBE_AROUND);

        vertexCount = mesh.vertexCount;
        indexCount = mesh.indexCount;
        vertices = ByteBuffer.allocateDirect(mesh.vertexCount * STRIDE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(mesh.vertices, 0, mesh.vertexCount * FLOATS_PER_VERTEX).position(0);
        indices = ByteBuffer.allocateDirect(mesh.indexCount * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        indices.put(mesh.indices, 0, mesh.indexCount).position(0);
    }

    // ------------------------------------------------------------------ pieces

    /** Revolves a (radius, height) profile around the Y axis. */
    private static void addRevolution(Mesh mesh, float[][] profile, int around) {
        int along = profile.length;
        int base = mesh.vertexCount;

        for (int j = 0; j < along; j++) {
            float r = profile[j][0];
            float y = profile[j][1];
            // Profile tangent (dr, dy); the outward normal in that plane is (dy, -dr).
            float[] prev = profile[Math.max(j - 1, 0)];
            float[] next = profile[Math.min(j + 1, along - 1)];
            float dr = next[0] - prev[0];
            float dy = next[1] - prev[1];
            float nr = dy;
            float ny = -dr;

            for (int i = 0; i < around; i++) {
                double theta = 2.0 * Math.PI * i / around;
                float cos = (float) Math.cos(theta);
                float sin = (float) Math.sin(theta);
                mesh.addVertex(r * cos, y, r * sin, nr * cos, ny, nr * sin);
            }
        }
        addGridIndices(mesh, base, around, along);
    }

    /** Sweeps a circular cross-section along a path that lies in the z = 0 plane. */
    private static void addTube(Mesh mesh, float[][] path, float r0, float r1, int around) {
        int along = path.length;
        int base = mesh.vertexCount;

        for (int j = 0; j < along; j++) {
            float[] prev = path[Math.max(j - 1, 0)];
            float[] next = path[Math.min(j + 1, along - 1)];
            float tx = next[0] - prev[0];
            float ty = next[1] - prev[1];
            float length = (float) Math.hypot(tx, ty);
            if (length < 1e-6f) {
                tx = 1f;
                ty = 0f;
                length = 1f;
            }
            tx /= length;
            ty /= length;
            // The path is planar, so cross(tangent, z) gives an exact in-plane
            // perpendicular and z itself completes the orthonormal ring frame.
            float nx = ty;
            float nyv = -tx;
            float radius = r0 + (r1 - r0) * j / (float) (along - 1);

            for (int i = 0; i < around; i++) {
                double phi = 2.0 * Math.PI * i / around;
                float cos = (float) Math.cos(phi);
                float sin = (float) Math.sin(phi);
                float ox = cos * nx;
                float oy = cos * nyv;
                float oz = sin;
                mesh.addVertex(path[j][0] + radius * ox, path[j][1] + radius * oy, radius * oz,
                        ox, oy, oz);
            }
        }
        addGridIndices(mesh, base, around, along);
    }

    /** Two triangles per cell of an {@code around x along} grid, wrapping around. */
    private static void addGridIndices(Mesh mesh, int base, int around, int along) {
        for (int j = 0; j < along - 1; j++) {
            for (int i = 0; i < around; i++) {
                int next = (i + 1) % around;
                int a = base + j * around + i;
                int b = base + j * around + next;
                int c = base + (j + 1) * around + next;
                int d = base + (j + 1) * around + i;
                mesh.addTriangle(a, b, c);
                mesh.addTriangle(a, c, d);
            }
        }
    }

    // ------------------------------------------------------------------ curves

    /** Uniformly samples a Catmull-Rom spline through {@code points}. */
    private static float[][] sample(float[][] points, int count) {
        int segments = points.length - 1;
        float[][] out = new float[count][2];
        for (int i = 0; i < count; i++) {
            float t = i * segments / (float) (count - 1);
            int s = Math.min((int) t, segments - 1);
            float f = t - s;
            float[] p0 = points[Math.max(s - 1, 0)];
            float[] p1 = points[s];
            float[] p2 = points[s + 1];
            float[] p3 = points[Math.min(s + 2, points.length - 1)];
            out[i][0] = catmull(p0[0], p1[0], p2[0], p3[0], f);
            out[i][1] = catmull(p0[1], p1[1], p2[1], p3[1], f);
        }
        return out;
    }

    private static float catmull(float a, float b, float c, float d, float t) {
        return 0.5f * ((-a + 3 * b - 3 * c + d) * t * t * t
                + (2 * a - 5 * b + 4 * c - d) * t * t
                + (-a + c) * t
                + 2 * b);
    }

    /** Growable vertex/index staging arrays, sized from the known tessellation. */
    private static final class Mesh {
        private static final int MAX_VERTICES =
                BODY_AROUND * BODY_ALONG + 2 * TUBE_AROUND * TUBE_ALONG;
        private static final int MAX_INDICES = 6 * (BODY_AROUND * (BODY_ALONG - 1)
                + 2 * TUBE_AROUND * (TUBE_ALONG - 1));

        final float[] vertices = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
        final short[] indices = new short[MAX_INDICES];
        int vertexCount;
        int indexCount;

        void addVertex(float x, float y, float z, float nx, float ny, float nz) {
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 1e-6f) {
                nx /= length;
                ny /= length;
                nz /= length;
            }
            int at = vertexCount * FLOATS_PER_VERTEX;
            vertices[at] = x;
            vertices[at + 1] = y;
            vertices[at + 2] = z;
            vertices[at + 3] = nx;
            vertices[at + 4] = ny;
            vertices[at + 5] = nz;
            vertexCount++;
        }

        void addTriangle(int a, int b, int c) {
            indices[indexCount++] = (short) a;
            indices[indexCount++] = (short) b;
            indices[indexCount++] = (short) c;
        }
    }
}
