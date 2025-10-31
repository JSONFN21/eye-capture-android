# MediaPipe Face Eye-Capture (Android)

> A focused fork of Google’s MediaPipe **Tasks Face Landmarker** sample, purpose-built to capture **high-quality per-eye images** with consistent focus and color for downstream analysis (iris/pupil work, biometrics research, medical screening).

This project **started as a fork** of the official MediaPipe sample and keeps its build flow and model assets, but the **runtime behavior is substantially different**: it adds a guided capture workflow that centers each eye, stabilizes the pose, zooms, focuses precisely on the eye, meters exposure/white balance on the face, **locks AE/AWB** for a short burst, and filters out blurry frames.

> Upstream sample: `google-ai-edge/mediapipe-samples` → `examples/face_landmarker/android`  
> This repository modifies that app to optimize **eye image capture quality and consistency**.

---

## What’s different in this fork

### Purpose-built eye capture
- **Two-stage capture:** right eye then left eye.
- **Centering & stability gates:** waits until the target eye is centered and the face is steady.
- **Auto-zoom:** frames the eye to a consistent scale for detail.

### Precision focus + stable color
- **AF (autofocus) on the eye center** using CameraX metering points.
- **AE/AWB metering over the whole face** (not background), then **locks AE/AWB** just before the burst to keep color/exposure stable across shots.
- **Flash disabled** to avoid color shifts.

### Quality filtering
- **Sharpness check** (Laplacian energy) per shot. Sub-threshold frames are discarded and automatically retaken.

### Operator UX
- Overlay crosshair and guides for centering the eye.
- “Hold steady” prompts before focus and capture.
- **Macro mode toggle** (rear camera only) when the device advertises Camera2 macro AF.

### Clean outputs
- Files are written via `MediaStore` as JPEGs using the pattern:  
  `"{participantId}_{Right|Left}_{index}.jpg"` (e.g., `P001_Right_3.jpg`)

---

## How it works (pipeline)

1. **Detect face & landmarks** (MediaPipe Tasks Face Landmarker, live stream).  
2. **Target selection:** choose right/left eye landmarks; compute eye/face bounding boxes.  
3. **Centering gate:** ensure the eye center is within a crosshair window.  
4. **Stability gate:** ensure head movement (face box center) is below a threshold for ~500 ms.  
5. **Zoom:** scale so the eye fills ~40% of view width (respect device max zoom).  
6. **Focus & metering:**
   - AF metering point at **eye center**.
   - AE/AWB metering region over the **face** (larger region).
   - Brief settle, then **lock AE/AWB**.
7. **Burst capture:** take `N` images (default **5**) with short spacing.  
8. **Quality gate:** compute sharpness; keep if ≥ threshold, else delete & retake.  
9. **Switch eye:** unlock AE/AWB, zoom out, guide user to center the other eye, repeat.  

---

## Configuration knobs (defaults)

Edit in `CameraFragment.kt`:

```kotlin
private const val IMAGES_TO_CAPTURE            = 5     // # per eye
private const val STABILITY_THRESHOLD          = 0.01f // face center drift
private const val CENTER_THRESHOLD             = 0.05f // how tight to center the eye
private const val STABILITY_CHECK_DELAY_MS     = 500L
private const val FOCUS_CONFIRMATION_DELAY_MS  = 2000L // after AF start
private const val CAPTURE_SERIES_DELAY_MS      = 100L  // spacing in burst
private const val SHARPNESS_THRESHOLD          = 10.0  // Laplacian energy gate
