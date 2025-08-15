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

    // ✅ NEW: Instance variables for overlay management
    private FrameLayout customOverlayContainer;
    private boolean showHomeButton = false;
    private boolean showThumbnails = false;
    private boolean showPreviewButton = false;
    private ReadableArray thumbnailsData = null;

    public DocumentScannerModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    @NonNull
    public String getName() {
        return NAME;
    }

    // ✅ NEW: Event emission helper
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
        WritableMap response = new WritableNativeMap();

        GmsDocumentScannerOptions.Builder documentScannerOptionsBuilder = new GmsDocumentScannerOptions.Builder()
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL);

        if (options.hasKey("maxNumDocuments")) {
            documentScannerOptionsBuilder.setPageLimit(
                options.getInt("maxNumDocuments")
            );
        }

        // ✅ NEW: Parse custom overlay options
        if (options.hasKey("customOverlay")) {
            ReadableMap customOverlay = options.getMap("customOverlay");
            showHomeButton = customOverlay.hasKey("showHomeButton") && customOverlay.getBoolean("showHomeButton");
            showThumbnails = customOverlay.hasKey("showThumbnails") && customOverlay.getBoolean("showThumbnails");
            showPreviewButton = customOverlay.hasKey("showPreviewButton") && customOverlay.getBoolean("showPreviewButton");
            
            if (customOverlay.hasKey("thumbnails")) {
                thumbnailsData = customOverlay.getArray("thumbnails");
            }
        }

        int croppedImageQuality;
        if (options.hasKey("croppedImageQuality")) {
            croppedImageQuality = options.getInt("croppedImageQuality");
        } else {
            croppedImageQuality = 100;
        }

        GmsDocumentScanner scanner = GmsDocumentScanning.getClient(documentScannerOptionsBuilder.build());
        ActivityResultLauncher<IntentSenderRequest> scannerLauncher = ((ComponentActivity) currentActivity).getActivityResultRegistry().register(
                "document-scanner",
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    // ✅ CLEANUP: Remove overlay when scanner finishes
                    removeCustomOverlay();
                    
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        GmsDocumentScanningResult documentScanningResult = GmsDocumentScanningResult.fromActivityResultIntent(
                            result.getData()
                        );
                        WritableArray docScanResults = new WritableNativeArray();

                        if (documentScanningResult != null) {
                            List<Page> pages = documentScanningResult.getPages();
                            if (pages != null) {
                                for (Page page : pages) {
                                    Uri croppedImageUri = page.getImageUri();
                                    String croppedImageResults = croppedImageUri.toString();

                                    if (options.hasKey("responseType") && Objects.equals(options.getString("responseType"), "base64")) {
                                        try {
                                            croppedImageResults = this.getImageInBase64(currentActivity, croppedImageUri, croppedImageQuality);
                                        } catch (FileNotFoundException error) {
                                            promise.reject("document scan error", error.getMessage());
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
                // ✅ NEW: Add custom overlay before launching scanner
                if (showHomeButton || showThumbnails || showPreviewButton) {
                    addCustomOverlayToActivity(currentActivity);
                }
                scannerLauncher.launch(new IntentSenderRequest.Builder(intentSender).build());
            })
            .addOnFailureListener(error -> {
                promise.reject("document scan error", error.getMessage());
            });
    }

    // ✅ NEW: Add custom overlay to the current activity
    private void addCustomOverlayToActivity(Activity activity) {
        if (activity == null) return;

        // Get the root view of the activity
        ViewGroup rootView = (ViewGroup) activity.findViewById(android.R.id.content);
        
        // Create overlay container
        customOverlayContainer = new FrameLayout(activity);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        
        // Add home button
        if (showHomeButton) {
            Button homeButton = createHomeButton(activity);
            customOverlayContainer.addView(homeButton);
        }

        // Add thumbnails strip
        if (showThumbnails && thumbnailsData != null) {
            HorizontalScrollView thumbnailsStrip = createThumbnailsStrip(activity);
            customOverlayContainer.addView(thumbnailsStrip);
        }

        // Add preview button
        if (showPreviewButton) {
            Button previewButton = createPreviewButton(activity);
            customOverlayContainer.addView(previewButton);
        }

        // Add overlay to root view with delay to ensure scanner UI is ready
        activity.runOnUiThread(() -> {
            // Small delay to ensure the scanner activity is fully loaded
            customOverlayContainer.postDelayed(() -> {
                rootView.addView(customOverlayContainer, overlayParams);
            }, 500);
        });
    }

    // ✅ NEW: Create home button
    private Button createHomeButton(Activity activity) {
        Button homeButton = new Button(activity);
        homeButton.setText("🏠");
        homeButton.setTextSize(20);
        homeButton.setBackgroundColor(Color.parseColor("#80000000")); // Semi-transparent black
        homeButton.setTextColor(Color.WHITE);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(150, 120);
        params.leftMargin = 40;
        params.topMargin = 80;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        homeButton.setLayoutParams(params);
        
        homeButton.setOnClickListener(v -> {
            WritableMap eventData = new WritableNativeMap();
            sendEvent("onHomeButtonPressed", eventData);
        });
        
        return homeButton;
    }

    // ✅ NEW: Create thumbnails strip
    private HorizontalScrollView createThumbnailsStrip(Activity activity) {
        HorizontalScrollView scrollView = new HorizontalScrollView(activity);
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(20, 0, 20, 0);
        
        // Add thumbnail images from thumbnailsData
        if (thumbnailsData != null) {
            for (int i = 0; i < thumbnailsData.size(); i++) {
                ReadableMap thumbnail = thumbnailsData.getMap(i);
                if (thumbnail != null && thumbnail.hasKey("uri")) {
                    ImageView thumbnailView = createThumbnailView(activity, thumbnail.getString("uri"), i);
                    container.addView(thumbnailView);
                }
            }
        }
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            200
        );
        params.bottomMargin = 200;
        params.gravity = Gravity.BOTTOM;
        scrollView.setLayoutParams(params);
        scrollView.addView(container);
        
        return scrollView;
    }

    // ✅ NEW: Create individual thumbnail view
    private ImageView createThumbnailView(Activity activity, String uri, int index) {
        ImageView imageView = new ImageView(activity);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(160, 160);
        params.rightMargin = 16;
        imageView.setLayoutParams(params);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setBackgroundColor(Color.parseColor("#80FFFFFF"));
        
        // Load image from URI (you'd need a proper image loading library like Glide in production)
        // For now, just set a placeholder background
        imageView.setBackground(activity.getResources().getDrawable(android.R.drawable.ic_menu_gallery));
        
        imageView.setOnClickListener(v -> {
            WritableMap eventData = new WritableNativeMap();
            eventData.putInt("index", index);
            eventData.putString("uri", uri);
            sendEvent("onThumbnailPressed", eventData);
        });
        
        return imageView;
    }

    // ✅ NEW: Create preview button
    private Button createPreviewButton(Activity activity) {
        Button previewButton = new Button(activity);
        previewButton.setText("📄");
        previewButton.setTextSize(20);
        previewButton.setBackgroundColor(Color.parseColor("#CC0066CC")); // Blue with transparency
        previewButton.setTextColor(Color.WHITE);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(150, 120);
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

    // ✅ NEW: Remove custom overlay
    private void removeCustomOverlay() {
        if (customOverlayContainer != null && customOverlayContainer.getParent() != null) {
            ((ViewGroup) customOverlayContainer.getParent()).removeView(customOverlayContainer);
            customOverlayContainer = null;
        }
    }
}
