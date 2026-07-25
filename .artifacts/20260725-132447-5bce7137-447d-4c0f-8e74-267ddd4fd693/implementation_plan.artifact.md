# Fix 1-Page Loading Issues in Panorama Mode

Identify and fix bugs causing persistent loading spinners on 1-3 pages, especially in Panorama mode.

## Proposed Changes

### MultiWallpaper Service

#### [MultiWallpaperLiveService.kt](file:///F:/Android%20Project/multiwallpaper/app/src/main/java/gustian/multiwallpaper/MultiWallpaperLiveService.kt)

- **Fix Rendering Cache (Critical)**: Add `needsNodeUpdate = true` in `repairGaps` (both Phase 1 and Phase 2) and `refreshOtherPages`.
    - **Why**: When a background load completes, the `RenderNode` (which caches the UI on Android 12+) still contains the "loading" animation. Without setting this flag, the engine continues to draw the cached spinner even if the bitmap is now available in `pageBitmaps`.
- **Fix `isLoading` State Leak**: Wrap `loadWallpapersForPages` logic in a `try-finally` block.
    - **Why**: Currently, if the loading job is cancelled (e.g., rapid settings change or screen off), `isLoading` can stay `true`. Since `repairGaps` checks this flag before running, it effectively disables auto-repair until the next full reload.
- **Break Corrupted Image Loop**: Update `repairGaps` and `refreshOtherPages` to call `addToHistory(uri)` even if the bitmap decoding fails (`b == null`).
    - **Why**: Logcat shows multiple "unimplemented" errors. If decoding fails, the app currently doesn't add that URI to history, causing `getNextWallpaperUriBatch` to potentially return the same bad URI again for the next repair attempt, leading to an infinite loop of failed loads.
- **Concurrency Protection**: Introduce `repairJob` and `repairGapsJob` variables to track active repair tasks and prevent overlapping operations on the same page indices.
- **Pano Self-Healing Improvements**: Ensure that when a pano chain is repaired, all affected pages are marked for node update.

```kotlin
// Example of the critical fix in repairGaps
withContext(Dispatchers.Main) {
    synchronized(bitmapLock) {
        // ... update pageBitmaps ...
        needsNodeUpdate = true // CRITICAL: Tell GPU to re-record
    }
    requestDraw()
}
```

## Verification Plan

### Automated Tests
- Verification will be primarily manual on-device.

### Manual Verification
1. **Panorama Loading Test**:
    - Enable Panorama mode.
    - Change folders to trigger a full reload.
    - Verify that all pages load successfully and the spinner disappears on all pages.
2. **Rapid Swipe Test**:
    - Swipe rapidly through pages to trigger `repairGaps`.
    - Verify that any "Loading" states are temporary and resolve within seconds.
3. **Corrupted Image Test**:
    - (Simulation) Mock a decode failure for a specific URI.
    - Verify that the app moves on to a different image and doesn't get stuck on Page 35 (or whichever index failed).
4. **Visibility Toggle Test**:
    - Turn screen off/on during a load.
    - Verify that `isLoading` is correctly reset.
