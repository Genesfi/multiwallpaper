# Fix 1-Page Loading Issues in Panorama Mode

Identify and fix bugs causing persistent loading spinners on 1-3 pages, especially in Panorama mode.

## Proposed Changes

### MultiWallpaper Service

#### [MultiWallpaperLiveService.kt](file:///F:/Android%20Project/multiwallpaper/app/src/main/java/gustian/multiwallpaper/MultiWallpaperLiveService.kt)

- **Fix Rendering Cache**: Add `needsNodeUpdate = true` in `repairGaps` and `refreshOtherPages`. This ensures the GPU-cached `RenderNode` is re-recorded when a background load completes. Without this, the screen might continue showing a "loading" state (spinner) even after the bitmap is ready.
- **Fix `isLoading` State Leak**: Wrap `loadWallpapersForPages` and `repairGaps` logic in `try-finally` blocks. Reset `isLoading = false` in the `finally` block using `NonCancellable` to ensure the flag is cleared even if the job is cancelled or crashes.
- **Break Corrupted Image Loop**: Update `getNextWallpaperUriBatch` or the calling sites to add URIs to history even if decoding fails. This prevents the app from repeatedly trying to load the same "unimplemented" or corrupted image.
- **Improve Pano Repair Logic**: Ensure `repairGaps` Phase 1 (Self-Healing) correctly handles cases where a panorama neighbor is null, and that Phase 2 doesn't trigger redundant loads for pages being filled by a pano span.
- **Concurrency Protection**: Add `repairJob` and `repairGapsJob` management to avoid overlapping background repairs that could lead to race conditions in bitmap recycling.

```kotlin
// Example of the finally block fix
mainLoadJob = engineScope.launch {
    try {
        // ... loading logic ...
    } finally {
        withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                isLoading = false
                requestDraw()
            }
        }
    }
}
```

## Verification Plan

### Automated Tests
- No existing unit tests for the LiveService rendering logic. Verification will be primarily manual on-device.

### Manual Verification
1. **Panorama Loading Test**:
    - Enable Panorama mode.
    - Change folders to trigger a full reload.
    - Verify that all pages (including those in a span) load successfully and the spinner disappears on all pages.
2. **Rapid Swipe Test**:
    - Swipe rapidly through pages to trigger `repairGaps` and `refreshOtherPages`.
    - Observe logcat for "Audit detected GENUINE gap" and verify they are fixed immediately.
3. **Corrupted Image Test**:
    - (Simulation) Mock a decode failure for a specific URI.
    - Verify that the app moves on to a different image instead of getting stuck on that page.
4. **Visibility Toggle Test**:
    - Turn screen off/on during a load.
    - Verify that `isLoading` is correctly reset and loading continues or completes upon wake.
