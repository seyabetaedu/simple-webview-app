package com.app.scan;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private static final int REQUEST_SELECT_FILE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        hideSystemUI();

        webView = new WebView(this);
        setContentView(webView);

        // Pengaturan WebView
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        // Menambahkan Javascript Interface untuk membaca unduhan tipe Blob (Excel/PDF)
        webView.addJavascriptInterface(new WebAppInterface(this), "Android");

        // Meminta izin Kamera jika belum diizinkan
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
        }

        webView.setWebViewClient(new WebViewClient());

        // Menangani Izin Kamera & Pemilihan Galeri
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView mWebView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, REQUEST_SELECT_FILE);
                } catch (ActivityNotFoundException e) {
                    uploadMessage = null;
                    Toast.makeText(MainActivity.this, "Gagal membuka pemilih file", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        // Menangani Unduhan Berkas (Mendukung HTTPS, Blob Excel/PDF, dan Base64 Data)
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    if (url.startsWith("blob:")) {
                        // Jika URL adalah Blob dari JavaScript web, konversi via JavaScript Interface
                        String js = "javascript:(function() {" +
                                "  var xhr = new XMLHttpRequest();" +
                                "  xhr.open('GET', '" + url + "', true);" +
                                "  xhr.responseType = 'blob';" +
                                "  xhr.onload = function(e) {" +
                                "    if (this.status == 200) {" +
                                "      var blob = this.response;" +
                                "      var reader = new FileReader();" +
                                "      reader.readAsDataURL(blob);" +
                                "      reader.onloadend = function() {" +
                                "        Android.getBase64FromBlob(reader.result, '" + mimeType + "');" +
                                "      };" +
                                "    }" +
                                "  };" +
                                "  xhr.send();" +
                                "})();";
                        webView.loadUrl(js);
                    } else if (url.startsWith("data:")) {
                        // Jika format data URI Base64 langsung
                        saveBase64ToFile(url, mimeType);
                    } else {
                        // Jika URL link HTTP/HTTPS biasa
                        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                        request.setMimeType(mimeType);

                        String cookies = CookieManager.getInstance().getCookie(url);
Penyebab aplikasi keluar paksa (*force close*) saat mengunduh file Excel/PDF dari website OMR/Blogger adalah karena file tersebut dibuat langsung di browser HP menggunakan JavaScript bertipe **`blob:`** atau **`data:`** (bukan link tautan `https://` biasa).

Sistem `DownloadManager` bawaan Android akan mengalami **crash parah** (*IllegalArgumentException*) jika dipaksa mengunduh URL bertipe `blob:`, yang menyebabkan aplikasi langsung tertutup dan muncul peringatan masalah.

---

### Solusi Kode Lengkap (`MainActivity.java`)

Kode berikut sudah dilengkapi dengan **JavaScript Interface (Base64 Converter)** untuk membaca file `blob:` hasil olahan JavaScript website, **`try-catch`** agar aplikasi tidak pernah keluar paksa lagi, serta sistem penyimpanan otomatis ke folder **Download** HP (cocok untuk Android versi lama maupun Android modern 10–14+).

Ganti seluruh isi file **`app/src/main/java/com/app/scan/MainActivity.java`** di VS Code dengan kode berikut:

```java
package com.app.scan;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private static final int REQUEST_SELECT_FILE = 100;
    private static final int PERMISSION_REQUEST_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        hideSystemUI();

        webView = new WebView(this);
        setContentView(webView);

        // Pengaturan WebView
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        // Menambahkan Interface Javascript untuk membaca unduhan file Blob/Base64
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBlobHandler");

        // Meminta Izin Akses (Kamera & Penyimpanan)
        requestAppPermissions();

        webView.setWebViewClient(new WebViewClient());

        // Menangani Kamera & Galeri Upload
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView mWebView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, REQUEST_SELECT_FILE);
                } catch (ActivityNotFoundException e) {
                    uploadMessage = null;
                    Toast.makeText(MainActivity.this, "Tidak ada aplikasi pemilih file", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        // Menangani Unduhan Berkas (Support HTTPS, Blob, dan Data URI)
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    if (url.startsWith("blob:")) {
                        // Jika URL berupa blob dari Javascript Excel/PDF
                        String js = "javascript: (function() {" +
                                "var xhr = new XMLHttpRequest();" +
                                "xhr.open('GET', '" + url + "', true);" +
                                "xhr.responseType = 'blob';" +
                                "xhr.onload = function(e) {" +
                                "  if (this.status == 200) {" +
                                "    var blob = this.response;" +
                                "    var reader = new FileReader();" +
                                "    reader.readAsDataURL(blob);" +
                                "    reader.onloadend = function() {" +
                                "      var base64data = reader.result;" +
                                "      AndroidBlobHandler.processBase64(base64data, '" + mimeType + "');" +
                                "    }" +
                                "  }" +
                                "};" +
                                "xhr.send();" +
                                "})()";
                        webView.loadUrl(js);
                    } else if (url.startsWith("data:")) {
                        // Jika URL berbentuk Data URI
                        new WebAppInterface(MainActivity.this).processBase64(url, mimeType);
                    } else {
                        // Jika URL unduhan normal (HTTP/HTTPS)
                        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                        request.setMimeType(mimeType);

                        String cookies = CookieManager.getInstance().getCookie(url);
                        if (cookies != null) {
                            request.addRequestHeader("cookie", cookies);
                        }
                        request.addRequestHeader("User-Agent", userAgent);

                        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                        request.setDescription("Mengunduh file...");
                        request.setTitle(fileName);
                        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        if (dm != null) {
                            dm.enqueue(request);
                            Toast.makeText(MainActivity.this, "Mengunduh " + fileName, Toast.LENGTH_SHORT).show();
                        }
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Gagal mengunduh file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.loadUrl("[https://scan32.blogspot.com](https://scan32.blogspot.com)");
    }

    private void requestAppPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                }, PERMISSION_REQUEST_CODE);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SELECT_FILE) {
            if (uploadMessage == null) return;
            uploadMessage.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            uploadMessage = null;
        }
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // Class pembantu untuk mengonversi data Blob/Base64 menjadi File XLSX/PDF di HP
    public class WebAppInterface {
        private Context context;

        public WebAppInterface(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void processBase64(String base64Data, String mimeType) {
            try {
                String fileExtension = ".xlsx";
                if (mimeType != null && mimeType.contains("pdf")) {
                    fileExtension = ".pdf";
                } else if (base64Data.contains("data:application/pdf")) {
                    fileExtension = ".pdf";
                }

                String filename = "Hasil_Scan_" + System.currentTimeMillis() + fileExtension;
                String pureBase64 = base64Data.substring(base64Data.indexOf(",") + 1);
                byte[] downloadedFile = Base64.decode(pureBase64, Base64.DEFAULT);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType != null && !mimeType.isEmpty() ? mimeType : "application/octet-stream");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                    Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        OutputStream outputStream = context.getContentResolver().openOutputStream(uri);
                        if (outputStream != null) {
                            outputStream.write(downloadedFile);
                            outputStream.close();
                            runOnUiThread(() -> Toast.makeText(context, "File berhasil disimpan di folder Download: " + filename, Toast.LENGTH_LONG).show());
                        }
                    }
                } else {
                    File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File file = new File(path, filename);
                    FileOutputStream os = new FileOutputStream(file);
                    os.write(downloadedFile);
                    os.close();
                    runOnUiThread(() -> Toast.makeText(context, "File berhasil disimpan: " + filename, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(context, "Gagal menyimpan file: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }
}
