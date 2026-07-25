# Walkthrough - Fixing Panorama Loading and "Empty" Gaps

I have implemented a series of deep architectural fixes to solve the persistent "loading" and "Img: Empty" issues in Panorama mode.

## Critical Issues Resolved

1.  **The "Coroutine Bomb" (CPU Lag)**:
    - **Issue**: The app was launching per-frame coroutines in `drawCanvas` (60fps) to fix empty pages. This was repeatedly cancelling and restarting decoding jobs every 16ms, preventing them from ever finishing.
    - **Fix**: Removed all coroutine launches from the render path. Repairs are now triggered only by manual swipes or settings changes with a debounced mechanism.

2.  **`isLoading` Deadlock**:
    - **Issue**: If a loading job was cancelled (e.g., rapid swipe), the `isLoading` flag would get stuck at `true`, blocking future auto-repairs.
    - **Fix**: Wrapped loading logic in `try-finally` blocks with `NonCancellable` context to guarantee the flag resets even on cancellation.

3.  **Fragmented Panorama ("Ngawur")**:
    - **Issue**: Small gaps were being filled by single segments of a large panorama, leading to disconnected images.
    - **Fix**: Implemented "Smart Pano Fit" – the system now checks if there is enough space (neighboring empty slots) before placing a panorama. If not, it forces a single-page image.

4.  **Race Condition in Recycling**:
    - **Issue**: New bitmaps were sometimes immediately recycled by a parallel gap-repair check because they weren't yet fully "accounted for" in the `pageBitmaps` map.
    - **Fix**: Refined the `stillInUse` logic to be more cautious and added explicit URI-Bitmap synchronization.

## Results
- **Smooth Swiping**: Pages load ahead of time or very shortly after landing.
- **Stable Pano Chains**: No more disconnected segments or "ngawur" placements.
- **Low CPU Usage**: No more redundant coroutine cancellations (`DequeueBuffer time out`).
- **No Persistent Spinners**: Every "Empty" page is now proactively filled by the prioritized repair system.
