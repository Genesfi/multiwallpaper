# Multi Wallpaper Live Changer

A professional, high-performance Android Live Wallpaper application designed to cycle through multiple images from your local storage with advanced visual effects and deep optimizations for modern launchers like HyperOS.

## What's New

### v1.1.0: Professional AI Artistry & Asset Management
- **AI Portrait Mode**: Revolutionary background blur that follows the subject's face while keeping them 100% sharp. Optimized for Android 12-16 using `RenderNode`.
- **AI Spotlight Focus**: Dynamic vignette effect that centers on detected faces, adding a dramatic stage-light look.
- **Smart Visual Toggles**: Unified UI for Dimming and Blur that automatically adapts to "Subject-Aware" mode when AI Focus is enabled.
- **Instant Blacklist Gesture**: Manage thousands of photos directly from your home screen. **2 Finger Tap** to remove an image from rotation instantly.
- **Gallery Blacklist Management**: Dedicated section to view and restore blacklisted photos, preventing "zombie" files from cluttering your rotation.
- **Multi-Select Support**: Mark and blacklist multiple images at once from the Gallery view.
- **GitHub Update Sync**: Stay current with the latest features using the new in-app "Check for Update" system linked directly to the repository.

### v1.0.1: Stability & Performance Overhaul
- **Memory Leak Fixes**: Patched critical leaks in the rotation engine, stabilizing RAM usage in the healthy even with 20+ active pages.
- **AI 480px Downscaling**: Face detection now uses an industry-standard proxy bitmap, resulting in 10x faster detection and massive RAM savings for 4K/108MP images.
- **Database Indexing**: Scalability fix to handle 10,000+ images without lag by fetching URIs one-by-one via database offsets.
- **Sapu Jagat Memory Guard**: Advanced thread synchronization and "Zombie Bitmap" removal logic for high-intensity shake/swipe interactions.
- **Hardware Canvas Fix**: Fully migrated to Hardware Acceleration (`lockHardwareCanvas`) to eliminate memory lock errors and improve battery efficiency.

## Advanced Features

- **3D Depth Parallax Effect**: Experience a realistic sense of physical depth. Using AI Face Detection data, the subject (person) drifts independently from the background when you tilt your phone.
- **AI Smart Crop**: Automatically identifies the main subject or face in your photos and ensures they are perfectly centered and focused.
- **HyperOS Optimized (20-Page Sync)**: Specifically engineered for Xiaomi/HyperOS devices. Supports manual sliding across up to 20 home screen pages with perfect image-to-page mapping.
- **Background Rotation (Screen Off Support)**: Wallpapers continue to rotate every set interval even when the screen is off, with instant swap logic to prevent RAM buildup.
- **Focus Smoothing**: Fine-grained control over the transition edge between sharp subjects and blurred/dark backgrounds for a natural DSLR feel.

## Core Features

- **Multi-Folder Support**: Combine multiple directories as your wallpaper sources.
- **Smooth Transitions**: Customizable 'Slide' and 'Fade' transition effects with adjustable speeds.
- **Gallery & Favorites**: Advanced image management with bulk actions and search/filter capabilities.
- **Performance (Light) Mode**: Dedicated optimization for lower-end devices using `RGB_565` memory format.

## Credits
- **Developer**: Migi Gustian
- **GitHub**: [Genesfi/multiwallpaper](https://github.com/Genesfi/multiwallpaper)

## Technical Info
- **Package ID**: `gustian.multiwallpaper`
- **Minimum SDK**: API 24 (Android 7.0)
- **Engine**: RenderNode & Hardware Accelerated Canvas.
