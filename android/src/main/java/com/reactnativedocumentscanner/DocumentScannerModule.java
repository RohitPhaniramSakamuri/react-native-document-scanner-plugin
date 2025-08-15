package com.reactnativedocumentscanner;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.Page;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Objects;

@ReactModule(name = DocumentScannerModule.NAME)
public class DocumentScannerModule extends ReactContextBaseJavaModule {
    public static final String NAME = "DocumentScanner";

    // ✅ OVERLAY MANAGEMENT
    private FrameLayout customOverlayContainer;
    private Activity currentScannerActivity;
    private ReadableArray thumbnailsData = null;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public DocumentScannerModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    @NonNull
    public String getName() {
        return NAME;
    }

    // ✅ EVENT EMISSION TO REACT NATIVE
    private void sendEvent(String eventName, WritableMap params) {
        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    public String getImageInBase64(Activity currentActivity, Uri croppedImageUri, int quality) throws FileNotFoundException {
        Bitmap bitmap = BitmapFactory.decodeStream(
            currentActivity.getContentResolver().openInputStream(croppedImageUri)
        );
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }

    @ReactMethod
public void scanDocument(ReadableMap options, Promise promise) {
    Activity currentActivity = getCurrentActivity();
    if (currentActivity == null) {
        promise.reject("ACTIVITY_NOT_AVAILABLE", "Current activity is null");
        return;
    }

    this.currentScannerActivity = currentActivity;
    WritableMap response = new WritableNativeMap();

    GmsDocumentScannerOptions.Builder documentScannerOptionsBuilder = new GmsDocumentScannerOptions.Builder()
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL);

    if (options.hasKey("maxNumDocuments")) {
        documentScannerOptionsBuilder.setPageLimit(options.getInt("maxNumDocuments"));
    }

    // ✅ PARSE CUSTOM OVERLAY OPTIONS
    boolean showHomeButton = false;
    boolean showThumbnails = false;
    boolean showPreviewButton = false;

    if (options.hasKey("customOverlay")) {
        ReadableMap customOverlay = options.getMap("customOverlay");
        showHomeButton = customOverlay.hasKey("showHomeButton") && customOverlay.getBoolean("showHomeButton");
        showThumbnails = customOverlay.hasKey("showThumbnails") && customOverlay.getBoolean("showThumbnails");
        showPreviewButton = customOverlay.hasKey("showPreviewButton") && customOverlay.getBoolean("showPreviewButton");
        
        if (customOverlay.hasKey("thumbnails")) {
            thumbnailsData = customOverlay.getArray("thumbnails");
        }
    }

    // ✅ FIX: Make variables final for lambda usage
    final boolean finalShowHomeButton = showHomeButton;
    final boolean finalShowThumbnails = showThumbnails;
    final boolean finalShowPreviewButton = showPreviewButton;
    final Activity finalCurrentActivity = currentActivity;

    int croppedImageQuality = options.hasKey("croppedImageQuality") ? options.getInt("croppedImageQuality") : 100;

    GmsDocumentScanner scanner = GmsDocumentScanning.getClient(documentScannerOptionsBuilder.build());
    ActivityResultLauncher<IntentSenderRequest> scannerLauncher = ((ComponentActivity) currentActivity).getActivityResultRegistry().register(
            "document-scanner",
            new ActivityResultContracts.StartIntentSenderForResult(),
            result -> {
                // ✅ CLEANUP OVERLAY WHEN SCANNER FINISHES
                removeCustomOverlay();
                
                if (result.getResultCode() == Activity.RESULT_OK) {
                    GmsDocumentScanningResult documentScanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
                    WritableArray docScanResults = new WritableNativeArray();

                    if (documentScanningResult != null) {
                        List<Page> pages = documentScanningResult.getPages();
                        if (pages != null) {
                            for (Page page : pages) {
                                Uri croppedImageUri = page.getImageUri();
                                String croppedImageResults = croppedImageUri.toString();

                                if (options.hasKey("responseType") && Objects.equals(options.getString("responseType"), "base64")) {
                                    try {
                                        croppedImageResults = this.getImageInBase64(finalCurrentActivity, croppedImageUri, croppedImageQuality);
                                    } catch (FileNotFoundException error) {
                                        promise.reject("document scan error", error.getMessage());
                                        return;
                                    }
                                }

                                docScanResults.pushString(croppedImageResults);
                            }
                        }
                    }

                    response.putArray("scannedImages", docScanResults);
                    response.putString("status", "success");
                    promise.resolve(response);
                } else if (result.getResultCode() == Activity.RESULT_CANCELED) {
                    response.putString("status", "cancel");
                    promise.resolve(response);
                }
            }
    );

    scanner.getStartScanIntent(currentActivity)
        .addOnSuccessListener(intentSender -> {
            // ✅ FIXED: Use final variables in lambda
            if (finalShowHomeButton || finalShowThumbnails || finalShowPreviewButton) {
                // Delay overlay injection to ensure scanner UI is ready
                mainHandler.postDelayed(() -> {
                    addCustomOverlayToActivity(finalCurrentActivity, finalShowHomeButton, finalShowThumbnails, finalShowPreviewButton);
                }, 1000);
            }
            scannerLauncher.launch(new IntentSenderRequest.Builder(intentSender).build());
        })
        .addOnFailureListener(error -> {
            promise.reject("document scan error", error.getMessage());
        });
}

    // ✅ INJECT CUSTOM NATIVE OVERLAY
    private void addCustomOverlayToActivity(Activity activity, boolean showHome, boolean showThumbnails, boolean showPreview) {
        if (activity == null) return;

        mainHandler.post(() -> {
            try {
                // Get the root view (DecorView contains all views including status bar)
                ViewGroup rootView = (ViewGroup) activity.getWindow().getDecorView();
                
                // Create overlay container
                customOverlayContainer = new FrameLayout(activity);
                FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                );
                
                // Set overlay to be clickable but allow touches to pass through where there are no views
                customOverlayContainer.setClickable(false);
                customOverlayContainer.setFocusable(false);

                // Add UI elements
                if (showHome) {
                    Button homeButton = createHomeButton(activity);
                    customOverlayContainer.addView(homeButton);
                }

                if (showThumbnails && thumbnailsData != null) {
                    HorizontalScrollView thumbnailsStrip = createThumbnailsStrip(activity);
                    customOverlayContainer.addView(thumbnailsStrip);
                }

                if (showPreview) {
                    Button previewButton = createPreviewButton(activity);
                    customOverlayContainer.addView(previewButton);
                }

                // Add overlay to root view
                rootView.addView(customOverlayContainer, overlayParams);
                
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // ✅ CREATE HOME BUTTON
    private Button createHomeButton(Activity activity) {
        Button homeButton = new Button(activity);
        homeButton.setText("🏠");
        homeButton.setTextSize(20);
        homeButton.setBackgroundColor(Color.parseColor("#80000000"));
        homeButton.setTextColor(Color.WHITE);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(120, 120);
        params.leftMargin = 40;
        params.topMargin = 100;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        homeButton.setLayoutParams(params);
        
        homeButton.setOnClickListener(v -> {
            WritableMap eventData = new WritableNativeMap();
            sendEvent("onHomeButtonPressed", eventData);
        });
        
        return homeButton;
    }

    // ✅ CREATE THUMBNAILS STRIP
    private HorizontalScrollView createThumbnailsStrip(Activity activity) {
        HorizontalScrollView scrollView = new HorizontalScrollView(activity);
        scrollView.setBackgroundColor(Color.parseColor("#80000000"));
        
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(20, 20, 20, 20);
        
        // Add thumbnail images from thumbnailsData
        if (thumbnailsData != null) {
            for (int i = 0; i < thumbnailsData.size(); i++) {
                ReadableMap thumbnail = thumbnailsData.getMap(i);
                if (thumbnail != null && thumbnail.hasKey("uri")) {
                    View thumbnailView = createThumbnailView(activity, thumbnail.getString("uri"), i);
                    container.addView(thumbnailView);
                }
            }
        }
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            160
        );
        params.bottomMargin = 200;
        params.gravity = Gravity.BOTTOM;
        scrollView.setLayoutParams(params);
        scrollView.addView(container);
        
        return scrollView;
    }

    // ✅ CREATE INDIVIDUAL THUMBNAIL
    private View createThumbnailView(Activity activity, String uri, int index) {
        FrameLayout container = new FrameLayout(activity);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(120, 120);
        params.rightMargin = 16;
        container.setLayoutParams(params);
        container.setBackgroundColor(Color.parseColor("#80FFFFFF"));
        
        // Create a simple placeholder button (in production, you'd load the actual image)
        Button thumbnailButton = new Button(activity);
        thumbnailButton.setText("📷");
        thumbnailButton.setTextSize(16);
        thumbnailButton.setBackgroundColor(Color.TRANSPARENT);
        
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        thumbnailButton.setLayoutParams(buttonParams);
        
        thumbnailButton.setOnClickListener(v -> {
            WritableMap eventData = new WritableNativeMap();
            eventData.putInt("index", index);
            eventData.putString("uri", uri);
            sendEvent("onThumbnailPressed", eventData);
        });
        
        container.addView(thumbnailButton);
        return container;
    }

    // ✅ CREATE PREVIEW BUTTON
    private Button createPreviewButton(Activity activity) {
        Button previewButton = new Button(activity);
        previewButton.setText("📄");
        previewButton.setTextSize(20);
        previewButton.setBackgroundColor(Color.parseColor("#CC0066CC"));
        previewButton.setTextColor(Color.WHITE);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(120, 120);
        params.rightMargin = 40;
        params.bottomMargin = 240;
        params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        previewButton.setLayoutParams(params);
        
        previewButton.setOnClickListener(v -> {
            WritableMap eventData = new WritableNativeMap();
            sendEvent("onPreviewButtonPressed", eventData);
        });
        
        return previewButton;
    }

    // ✅ CLEANUP OVERLAY
    private void removeCustomOverlay() {
        mainHandler.post(() -> {
            if (customOverlayContainer != null && customOverlayContainer.getParent() != null) {
                ((ViewGroup) customOverlayContainer.getParent()).removeView(customOverlayContainer);
                customOverlayContainer = null;
            }
        });
    }
}
