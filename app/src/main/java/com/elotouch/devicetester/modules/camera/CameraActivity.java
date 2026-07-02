package com.elotouch.devicetester.modules.camera;

import android.Manifest;
import android.widget.LinearLayout;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.google.common.util.concurrent.ListenableFuture;

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

    @Override
    protected void onStopTests() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(false);
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}
