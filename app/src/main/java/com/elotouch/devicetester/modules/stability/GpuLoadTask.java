package com.elotouch.devicetester.modules.stability;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.View;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GPU load task: renders a continuously rotating teapot with OpenGL ES 2.0 —
 * a ~48k-triangle procedural mesh ({@link Teapot}) shaded with per-pixel
 * Blinn-Phong plus extra per-fragment work, drawn as fast as the driver
 * allows. Reports frames, fps and any {@code glGetError}.
 *
 * <p>A real GPU load needs a live, visible surface, so this task owns a
 * {@link GLSurfaceView} that {@link StabilityActivity} attaches to the page;
 * rendering happens on the view's own GL thread, not on the shared pool.
 */
final class GpuLoadTask implements StressTask {

    private static final float ROTATION_DEG_PER_SEC = 42f;

    private static final String VERTEX_SRC =
            "uniform mat4 uMvp;\n"
                    + "uniform mat3 uNormalMat;\n"
                    + "attribute vec3 aPos;\n"
                    + "attribute vec3 aNormal;\n"
                    + "varying vec3 vNormal;\n"
                    + "varying vec3 vPos;\n"
                    + "void main() {\n"
                    + "  vNormal = uNormalMat * aNormal;\n"
                    + "  vPos = aPos;\n"
                    + "  gl_Position = uMvp * vec4(aPos, 1.0);\n"
                    + "}\n";

    private static final String FRAGMENT_SRC =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
                    + "precision highp float;\n"
                    + "#else\n"
                    + "precision mediump float;\n"
                    + "#endif\n"
                    + "uniform float uTime;\n"
                    + "varying vec3 vNormal;\n"
                    + "varying vec3 vPos;\n"
                    + "void main() {\n"
                    + "  vec3 n = normalize(vNormal);\n"
                    + "  vec3 l = normalize(vec3(0.45, 0.75, 0.55));\n"
                    + "  vec3 v = vec3(0.0, 0.0, 1.0);\n"
                    + "  vec3 h = normalize(l + v);\n"
                    + "  float diff = max(dot(n, l), 0.0);\n"
                    + "  float spec = pow(max(dot(n, h), 0.0), 42.0);\n"
                    // Extra per-pixel work so the fragment stage is loaded too, not
                    // just the geometry the teapot provides.
                    + "  float ripple = 0.0;\n"
                    + "  for (int i = 0; i < 24; i++) {\n"
                    + "    float fi = float(i);\n"
                    + "    ripple += sin(vPos.x * 9.0 + uTime + fi)\n"
                    + "            * cos(vPos.y * 9.0 - uTime * 0.7 + fi);\n"
                    + "  }\n"
                    + "  ripple = ripple / 24.0;\n"
                    + "  vec3 base = vec3(0.24, 0.52, 0.92) + 0.10 * ripple;\n"
                    + "  vec3 color = base * (0.20 + 0.80 * diff)\n"
                    + "             + vec3(0.95, 0.97, 1.0) * spec * 0.7;\n"
                    + "  gl_FragColor = vec4(color, 1.0);\n"
                    + "}\n";

    private final GLSurfaceView view;
    private final AtomicLong frames = new AtomicLong();
    private final AtomicInteger errors = new AtomicInteger();
    private volatile double fps;
    private volatile int triangles;
    private volatile String note;

    GpuLoadTask(Context context) {
        view = new GLSurfaceView(context);
        view.setEGLContextClientVersion(2);
        view.setRenderer(new TeapotRenderer());
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    /** The surface to attach to the page; rendering only happens while attached. */
    View view() {
        return view;
    }

    @Override
    public String label() {
        return "GPU";
    }

    @Override
    public void start(ExecutorService pool) {
        view.onResume();
    }

    @Override
    public void stop() {
        view.onPause();
    }

    @Override
    public String status() {
        StringBuilder sb = new StringBuilder(String.format(Locale.US,
                "OpenGL ES 2.0 茶壶 teapot %s 三角形 tris   帧 Frames %s   %.1f fps"
                        + "   错误 Errors %d",
                Fmt.count(triangles), Fmt.count(frames.get()), fps, errors.get()));
        String n = note;
        if (n != null) sb.append("\n     ").append(n);
        return sb.toString();
    }

    @Override
    public int errorCount() {
        return errors.get();
    }

    /** Runs on the GLSurfaceView's own render thread. */
    private final class TeapotRenderer implements GLSurfaceView.Renderer {

        private final float[] projection = new float[16];
        private final float[] view4 = new float[16];
        private final float[] model = new float[16];
        private final float[] temp = new float[16];
        private final float[] mvp = new float[16];
        private final float[] normalMat = new float[9];

        private int program;
        private int posHandle;
        private int normalHandle;
        private int mvpHandle;
        private int normalMatHandle;
        private int timeHandle;
        private int vertexBuffer;
        private int indexBuffer;
        private int indexCount;

        private long startNs;
        private long windowStartNs;
        private long windowFrames;

        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                     javax.microedition.khronos.egl.EGLConfig config) {
            int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SRC);
            int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC);
            if (vertex == 0 || fragment == 0) {
                program = 0;
                return;
            }
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vertex);
            GLES20.glAttachShader(p, fragment);
            GLES20.glLinkProgram(p);
            int[] status = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                note = "着色器链接失败 / program link failed: " + GLES20.glGetProgramInfoLog(p);
                errors.incrementAndGet();
                GLES20.glDeleteProgram(p);
                program = 0;
                return;
            }
            program = p;
            posHandle = GLES20.glGetAttribLocation(program, "aPos");
            normalHandle = GLES20.glGetAttribLocation(program, "aNormal");
            mvpHandle = GLES20.glGetUniformLocation(program, "uMvp");
            normalMatHandle = GLES20.glGetUniformLocation(program, "uNormalMat");
            timeHandle = GLES20.glGetUniformLocation(program, "uTime");

            // Upload once; drawing from client memory every frame would make this a
            // CPU test as much as a GPU one.
            Teapot teapot = new Teapot();
            indexCount = teapot.indexCount;
            triangles = indexCount / 3;
            int[] buffers = new int[2];
            GLES20.glGenBuffers(2, buffers, 0);
            vertexBuffer = buffers[0];
            indexBuffer = buffers[1];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
                    teapot.vertexCount * Teapot.STRIDE_BYTES, teapot.vertices,
                    GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER,
                    indexCount * 2, teapot.indices, GLES20.GL_STATIC_DRAW);

            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            // Back faces are left on: the depth test still resolves them correctly on
            // a closed surface, and rasterising them adds the fill-rate load we want.
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glClearColor(0.05f, 0.06f, 0.09f, 1f);

            Matrix.setLookAtM(view4, 0, 0f, 0.55f, 3.5f, 0f, 0.55f, 0f, 0f, 1f, 0f);
            startNs = System.nanoTime();
            windowStartNs = startNs;
        }

        @Override
        public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl,
                                     int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            float aspect = height == 0 ? 1f : width / (float) height;
            Matrix.perspectiveM(projection, 0, 45f, aspect, 1f, 20f);
        }

        @Override
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            if (program == 0) return;

            float seconds = (System.nanoTime() - startNs) / 1e9f;
            Matrix.setIdentityM(model, 0);
            Matrix.rotateM(model, 0, seconds * ROTATION_DEG_PER_SEC, 0f, 1f, 0f);
            Matrix.rotateM(model, 0, 12f * (float) Math.sin(seconds * 0.6), 1f, 0f, 0f);
            // Rotation only, so the normal matrix is just the model matrix's 3x3 part.
            for (int col = 0; col < 3; col++) {
                for (int row = 0; row < 3; row++) {
                    normalMat[col * 3 + row] = model[col * 4 + row];
                }
            }
            Matrix.multiplyMM(temp, 0, view4, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0);

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0);
            GLES20.glUniformMatrix3fv(normalMatHandle, 1, false, normalMat, 0);
            GLES20.glUniform1f(timeHandle, seconds);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GLES20.glEnableVertexAttribArray(posHandle);
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false,
                    Teapot.STRIDE_BYTES, 0);
            GLES20.glEnableVertexAttribArray(normalHandle);
            GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false,
                    Teapot.STRIDE_BYTES, Teapot.NORMAL_OFFSET_BYTES);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount,
                    GLES20.GL_UNSIGNED_SHORT, 0);
            GLES20.glDisableVertexAttribArray(posHandle);
            GLES20.glDisableVertexAttribArray(normalHandle);

            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                errors.incrementAndGet();
                note = "GL 错误 error 0x" + Integer.toHexString(error);
            }

            frames.incrementAndGet();
            windowFrames++;
            long now = System.nanoTime();
            long windowNs = now - windowStartNs;
            if (windowNs >= 1_000_000_000L) {
                fps = windowFrames * 1e9 / windowNs;
                windowStartNs = now;
                windowFrames = 0;
            }
        }

        private int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                note = "着色器编译失败 / shader compile failed: "
                        + GLES20.glGetShaderInfoLog(shader);
                errors.incrementAndGet();
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }
    }
}
