import { NativeModules, Platform, NativeEventEmitter, EmitterSubscription } from 'react-native';

const LINKING_ERROR =
  `The package 'react-native-document-scanner-plugin' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo managed workflow\n';

const DocumentScanner = NativeModules.DocumentScanner
  ? NativeModules.DocumentScanner
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

// ✅ NEW: Event emitter for native events
const documentScannerEmitter = new NativeEventEmitter(DocumentScanner);

// ✅ NEW: Custom overlay options interface
export interface CustomOverlayOptions {
  /**
   * Show a home button in the scanner overlay
   * @default: false
   */
  showHomeButton?: boolean;
  
  /**
   * Show thumbnails strip in the scanner overlay
   * @default: false
   */
  showThumbnails?: boolean;
  
  /**
   * Show a preview button in the scanner overlay
   * @default: false
   */
  showPreviewButton?: boolean;
  
  /**
   * Array of thumbnail data to display in the thumbnails strip
   */
  thumbnails?: Array<{
    uri: string;
    width?: number;
    height?: number;
  }>;
}

export interface ScanDocumentOptions {
  /**
   * The quality of the cropped image from 0 - 100. 100 is the best quality.
   * @default: 100
   */
  croppedImageQuality?: number;

  /**
   * Android only: The maximum number of photos an user can take (not counting photo retakes)
   * @default: undefined
   */
  maxNumDocuments?: number;

  /**
   * The response comes back in this format on success. It can be the document
   * scan image file paths or base64 images.
   * @default: ResponseType.ImageFilePath
   */
  responseType?: ResponseType;

  // ✅ NEW: Custom overlay configuration
  /**
   * Configuration for custom UI overlay on the scanner
   */
  customOverlay?: CustomOverlayOptions;
}

export enum ResponseType {
  /**
   * Use this response type if you want document scan returned as base64 images.
   */
  Base64 = 'base64',

  /**
   * Use this response type if you want document scan returned as inmage file paths.
   */
  ImageFilePath = 'imageFilePath',
}

export interface ScanDocumentResponse {
  /**
   * This is an array with either file paths or base64 images for the
   * document scan.
   */
  scannedImages?: string[];

  /**
   * The status lets you know if the document scan completes successfully,
   * or if the user cancels before completing the document scan.
   */
  status?: ScanDocumentResponseStatus;
}

export enum ScanDocumentResponseStatus {
  /**
   * The status comes back as success if the document scan completes
   * successfully.
   */
  Success = 'success',

  /**
   * The status comes back as cancel if the user closes out of the camera
   * before completing the document scan.
   */
  Cancel = 'cancel',
}

// ✅ NEW: Event listener types
export interface DocumentScannerEvents {
  onHomeButtonPressed: () => void;
  onPreviewButtonPressed: () => void;
  onThumbnailPressed: (data: { index: number; uri: string }) => void;
}

// ✅ NEW: Event listener functions
export const addEventListener = <K extends keyof DocumentScannerEvents>(
  eventName: K,
  handler: DocumentScannerEvents[K]
): EmitterSubscription => {
  return documentScannerEmitter.addListener(eventName, handler);
};

export const removeEventListener = (subscription: EmitterSubscription) => {
  subscription.remove();
};

// ✅ ENHANCED: Export with event listener functions
export default {
  /**
   * Opens the camera, and starts the document scan
   */
  scanDocument(
    options: ScanDocumentOptions = {}
  ): Promise<ScanDocumentResponse> {
    return DocumentScanner.scanDocument(options);
  },
  
  /**
   * Add event listener for custom overlay events
   */
  addEventListener,
  
  /**
   * Remove event listener
   */
  removeEventListener,
};
