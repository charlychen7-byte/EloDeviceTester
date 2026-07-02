package com.elotouch.devicetester.modules.camera;

import android.Manifest;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Camera module (PRD §3.9): front/back preview via CameraX and flashlight
 * (torch) control. Camera permission is requested lazily on entry (§5.4).
 */
public class CameraActivity extends BaseTestActivity {

    private PreviewView previewView;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private boolean torchOn = false;
    private TextView cameraInfoText;
    private TextView multiCameraText;
    private final List<String> cameraIds = new ArrayList<>();
    private int cameraIdIndex = -1;

    @Override
    protected String title() {
        return "Camera 相机测试";
    }

    @Override
    protected void buildUi() {
        previewView = new PreviewView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360));
        lp.topMargin = dp(8);
        previewView.setLayoutParams(lp);
        content.addView(previewView);

        addButton("Switch Front/Back / 切换前后摄像头", this::switchCamera);
        addButton("Flashlight On/Off / 闪光灯开关", this::toggleTorch);

        addSectionTitle("Resolution & Focus / 分辨率与对焦速度");
        cameraInfoText = addInfo("");
        addButton("Check Output Resolution / 核验输出分辨率", this::checkResolution);
        addButton("Measure Focus Speed / 测量对焦速度", this::measureFocusSpeed);

        addSectionTitle("Multi-Camera / 多摄像头切换");
        multiCameraText = addInfo("");
        addButton("List Cameras / 列出所有摄像头", this::listCameras);
        addButton("Next Camera / 切换到下一个摄像头", this::nextCamera);

        requirePermission(Manifest.permission.CAMERA,
                this::startCamera,
                () -> toast("Permission denied. 权限受限：未授予相机权限。"));
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bind();
            } catch (Exception e) {
                toast("Camera init failed 相机初始化失败：" + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bind() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing).build();
        try {
            camera = cameraProvider.bindToLifecycle(this, selector, preview);
            torchOn = false;
        } catch (Exception e) {
            toast("Camera for this lens unavailable 该朝向摄像头不可用：" + e.getMessage());
        }
    }

    private void switchCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        bind();
    }

    private void toggleTorch() {
        if (camera == null) return;
        if (!camera.getCameraInfo().hasFlashUnit()) {
            toast("No flash on this camera 当前摄像头无闪光灯");
            return;
        }
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
    }

    private void checkResolution() {
        if (camera == null) {
            cameraInfoText.setText("Camera not ready. 相机未就绪。");
            return;
        }
        try {
            Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            StreamConfigurationMap map = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            StringBuilder sb = new StringBuilder("Supported JPEG sizes 支持的拍照分辨率：\n");
            if (map != null) {
                Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                if (sizes != null) {
                    for (Size s : sizes) sb.append("  ").append(s.getWidth())
                            .append('x').append(s.getHeight()).append('\n');
                }
            }
            cameraInfoText.setText(sb.toString());
        } catch (Exception e) {
            cameraInfoText.setText("Could not read resolutions. 无法读取分辨率列表：" + e.getMessage());
        }
    }

    private void measureFocusSpeed() {
        if (camera == null || previewView == null) {
            cameraInfoText.setText("Camera not ready. 相机未就绪。");
            return;
        }
        MeteringPointFactory factory = previewView.getMeteringPointFactory();
        MeteringPoint point = factory.createPoint(previewView.getWidth() / 2f, previewView.getHeight() / 2f);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point).build();
        long t0 = System.nanoTime();
        cameraInfoText.setText("Focusing… 对焦中…");
        ListenableFuture<FocusMeteringResult> future = camera.getCameraControl().startFocusAndMetering(action);
        future.addListener(() -> {
            try {
                FocusMeteringResult result = future.get();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                cameraInfoText.setText(String.format(Locale.US,
                        "Focus %s in %d ms\n对焦%s，耗时 %d ms",
                        result.isFocusSuccessful() ? "succeeded" : "did not converge", ms,
                        result.isFocusSuccessful() ? "成功" : "未收敛", ms));
            } catch (Exception e) {
                cameraInfoText.setText("Focus measurement failed 对焦测量失败：" + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStopTests() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(false);
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    private void listCameras() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            cameraIds.clear();
            StringBuilder sb = new StringBuilder("Cameras found 检测到摄像头：\n");
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                cameraIds.add(id);
                sb.append("  id=").append(id).append("  facing=").append(facingStr(facing)).append('\n');
            }
            multiCameraText.setText(sb.toString());
        } catch (Exception e) {
            multiCameraText.setText("Failed to list cameras 列出摄像头失败：" + e.getMessage());
        }
    }

    private void nextCamera() {
        if (cameraIds.isEmpty()) listCameras();
        if (cameraIds.isEmpty() || cameraProvider == null) return;
        cameraIdIndex = (cameraIdIndex + 1) % cameraIds.size();
        String targetId = cameraIds.get(cameraIdIndex);
        cameraProvider.unbindAll();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        CameraSelector selector = new CameraSelector.Builder()
                .addCameraFilter(infos -> {
                    List<CameraInfo> filtered = new ArrayList<>();
                    for (CameraInfo info : infos) {
                        if (targetId.equals(Camera2CameraInfo.from(info).getCameraId())) {
                            filtered.add(info);
                        }
                    }
                    return filtered;
                })
                .build();
        try {
            camera = cameraProvider.bindToLifecycle(this, selector, preview);
            multiCameraText.setText("Now showing camera id=" + targetId + " 当前显示摄像头 id=" + targetId);
        } catch (Exception e) {
            multiCameraText.setText("Bind failed 切换失败：" + e.getMessage());
        }
    }

    private static String facingStr(Integer facing) {
        if (facing == null) return "unknown 未知";
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT: return "front 前置";
            case CameraCharacteristics.LENS_FACING_BACK: return "back 后置";
            case CameraCharacteristics.LENS_FACING_EXTERNAL: return "external 外接";
            default: return "unknown 未知";
        }
    }
}
