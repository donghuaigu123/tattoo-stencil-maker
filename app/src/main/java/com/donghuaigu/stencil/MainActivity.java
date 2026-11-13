package com.donghuaigu.stencil;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 一键式「图片转纹身转印线描稿」。
 * 选图 → 自动出稿 → 拖动对比 → 保存 / 分享。默认参数即可直接使用。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final int REQ_WRITE = 1002;

    private CompareView compareView;
    private TextView tvStatus;
    private ProgressBar progressBar;
    private Button btnModeOriginal, btnModeResult, btnModeCompare;
    private Button btnFine, btnNormal, btnBold;
    private Button btnPick, btnSave, btnShare, btnAdvanced;
    private SeekBar seekStrength;
    private Switch switchClean;
    private View panelAdvanced;
    private TextView tvHint;

    private Bitmap original;
    private Bitmap result;
    private Uri lastSavedUri;
    private boolean busy = false;
    private int jobId = 0;
    private int preset = 1;
    private int strength = 50;
    private boolean cleanBackground = true;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        compareView = findViewById(R.id.compareView);
        tvStatus = findViewById(R.id.tvStatus);
        progressBar = findViewById(R.id.progressBar);
        tvHint = findViewById(R.id.tvHint);
        btnModeOriginal = findViewById(R.id.btnModeOriginal);
        btnModeResult = findViewById(R.id.btnModeResult);
        btnModeCompare = findViewById(R.id.btnModeCompare);
        btnFine = findViewById(R.id.btnFine);
        btnNormal = findViewById(R.id.btnNormal);
        btnBold = findViewById(R.id.btnBold);
        btnPick = findViewById(R.id.btnPick);
        btnSave = findViewById(R.id.btnSave);
        btnShare = findViewById(R.id.btnShare);
        btnAdvanced = findViewById(R.id.btnAdvanced);
        seekStrength = findViewById(R.id.seekStrength);
        switchClean = findViewById(R.id.switchClean);
        panelAdvanced = findViewById(R.id.panelAdvanced);

        btnPick.setOnClickListener(v -> pickImage());
        btnSave.setOnClickListener(v -> doSave(false));
        btnShare.setOnClickListener(v -> doSave(true));
        btnAdvanced.setOnClickListener(v -> {
            boolean show = panelAdvanced.getVisibility() != View.VISIBLE;
            panelAdvanced.setVisibility(show ? View.VISIBLE : View.GONE);
            btnAdvanced.setText(show ? "收起设置 ▴" : "高级设置 ▾");
        });

        btnModeOriginal.setOnClickListener(v -> switchMode(CompareView.MODE_ORIGINAL));
        btnModeResult.setOnClickListener(v -> switchMode(CompareView.MODE_RESULT));
        btnModeCompare.setOnClickListener(v -> switchMode(CompareView.MODE_COMPARE));

        View.OnClickListener presetClick = v -> {
            int p = (v == btnFine) ? 0 : (v == btnBold) ? 2 : 1;
            if (p != preset) { preset = p; refreshPresetButtons(); regenerate(); }
        };
        btnFine.setOnClickListener(presetClick);
        btnNormal.setOnClickListener(presetClick);
        btnBold.setOnClickListener(presetClick);

        seekStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                strength = s.getProgress();
                regenerate();
            }
        });
        switchClean.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                cleanBackground = checked;
                regenerate();
            }
        });

        refreshPresetButtons();
        refreshButtons();
        switchMode(CompareView.MODE_COMPARE);
    }

    // ---------------------------------------------------------------- 交互

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        setBusy(true, "正在导入图片");
        new Thread(() -> {
            try {
                Bitmap bmp = loadBitmap(uri);
                runOnUiThread(() -> {
                    if (original != null && original != bmp) original.recycle();
                    if (result != null) { result.recycle(); result = null; }
                    original = bmp;
                    lastSavedUri = null;
                    compareView.setImages(original, null);
                    refreshButtons();
                    regenerate();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, "导入失败：" + e.getMessage());
                    Toast.makeText(this, "图片读取失败，请换一张试试", Toast.LENGTH_LONG).show();
                });
            }
        }, "load-image").start();
    }

    private void switchMode(int mode) {
        compareView.setMode(mode);
        int active = 0xFF2C6BED, inactive = 0xFFE8ECF3;
        int activeText = 0xFFFFFFFF, inactiveText = 0xFF44506A;
        btnModeOriginal.setBackgroundColor(mode == 0 ? active : inactive);
        btnModeOriginal.setTextColor(mode == 0 ? activeText : inactiveText);
        btnModeResult.setBackgroundColor(mode == 1 ? active : inactive);
        btnModeResult.setTextColor(mode == 1 ? activeText : inactiveText);
        btnModeCompare.setBackgroundColor(mode == 2 ? active : inactive);
        btnModeCompare.setTextColor(mode == 2 ? activeText : inactiveText);
        tvHint.setText(mode == CompareView.MODE_COMPARE ? "拖动中间分割线对比 · 双指放大查看细节"
                : "双指放大 · 单指拖动查看细节");
    }

    private void refreshPresetButtons() {
        int active = 0xFF2C6BED, inactive = 0xFFE8ECF3;
        int activeText = 0xFFFFFFFF, inactiveText = 0xFF44506A;
        Button[] bs = {btnFine, btnNormal, btnBold};
        for (int i = 0; i < 3; i++) {
            boolean on = (i == preset);
            bs[i].setBackgroundColor(on ? active : inactive);
            bs[i].setTextColor(on ? activeText : inactiveText);
        }
    }

    private void refreshButtons() {
        boolean ready = (result != null) && !busy;
        btnSave.setEnabled(ready);
        btnShare.setEnabled(ready);
        btnSave.setAlpha(ready ? 1f : 0.45f);
        btnShare.setAlpha(ready ? 1f : 0.45f);
        btnPick.setEnabled(!busy);
        seekStrength.setEnabled(ready);
        switchClean.setEnabled(ready);
        btnFine.setEnabled(ready);
        btnNormal.setEnabled(ready);
        btnBold.setEnabled(ready);
        if (original == null) tvStatus.setText("等待导入图片");
    }

    private void setBusy(boolean b, String msg) {
        busy = b;
        progressBar.setVisibility(b ? View.VISIBLE : View.GONE);
        if (msg != null) tvStatus.setText(msg);
        refreshButtons();
    }

    // ---------------------------------------------------------------- 处理

    private void regenerate() {
        if (original == null || original.isRecycled()) return;
        final int id = ++jobId;
        StencilProcessor.Options opt = new StencilProcessor.Options();
        opt.preset = preset;
        opt.strength = strength;
        opt.cleanBackground = cleanBackground;
        final Bitmap input = original;
        setBusy(true, "开始生成线描稿");
        new Thread(() -> {
            try {
                Bitmap out = StencilProcessor.process(input, opt, (pct, message) ->
                        runOnUiThread(() -> {
                            if (id != jobId) return;
                            progressBar.setProgress(pct);
                            tvStatus.setText(message + "  " + pct + "%");
                        }));
                runOnUiThread(() -> {
                    if (id != jobId) { out.recycle(); return; }
                    if (result != null && result != out) result.recycle();
                    result = out;
                    lastSavedUri = null;
                    compareView.setImages(original, result);
                    compareView.setMode(CompareView.MODE_COMPARE);
                    switchMode(CompareView.MODE_COMPARE);
                    setBusy(false, "完成 · 纯白底纯黑线，可直接打印转印");
                    refreshButtons();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    if (id != jobId) return;
                    setBusy(false, "生成失败：" + t.getMessage());
                    Toast.makeText(this, "处理失败，可尝试关闭「背景净化」或降低强度", Toast.LENGTH_LONG).show();
                });
            }
        }, "stencil-process").start();
    }

    // ---------------------------------------------------------------- 保存 / 分享

    private void doSave(boolean share) {
        if (result == null) { Toast.makeText(this, "请先导入图片", Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT < 29 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            Toast.makeText(this, "请授予存储权限后再次点击", Toast.LENGTH_SHORT).show();
            return;
        }
        final Bitmap toSave = result;
        setBusy(true, "正在保存 PNG");
        new Thread(() -> {
            Uri uri = saveToGallery(toSave);
            runOnUiThread(() -> {
                setBusy(false, null);
                if (uri == null) {
                    Toast.makeText(this, "保存失败，请检查存储空间", Toast.LENGTH_LONG).show();
                    return;
                }
                lastSavedUri = uri;
                tvStatus.setText("已保存到 相册 / Pictures / TattooStencil");
                Toast.makeText(this, "已保存 PNG（无损、纯黑白）", Toast.LENGTH_SHORT).show();
                if (share) shareImage(uri);
            });
        }, "save").start();
    }

    private void shareImage(Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/png");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "分享线描稿"));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 写入系统相册，PNG 无损，保证纯黑白无灰阶。兼容 API 24+。 */
    private Uri saveToGallery(Bitmap bmp) {
        String name = "stencil_" + System.currentTimeMillis() + ".png";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= 29) {
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TattooStencil");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TattooStencil");
            if (!dir.exists() && !dir.mkdirs()) return null;
            cv.put(MediaStore.Images.Media.DATA, new File(dir, name).getAbsolutePath());
        }
        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) return null;
            boolean ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();
            if (!ok) return null;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
            }
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 图片读取

    private Bitmap loadBitmap(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new Exception("无法打开图片");
        BitmapFactory.decodeStream(is, null, bounds);
        is.close();

        int side = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (side / sample > 2400) sample *= 2;

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        is = getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(is, null, opt);
        is.close();
        if (bmp == null) throw new Exception("无法解码图片");

        int rotation = readRotation(uri);
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            bmp = rotated;
        }
        return bmp;
    }

    private int readRotation(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return 0;
            ExifInterface exif = new ExifInterface(is);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            is.close();
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {}
        return 0;
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_WRITE && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "权限已授予，请再次点击保存", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        jobId++;
        if (result != null) result.recycle();
        if (original != null) original.recycle();
    }
}
