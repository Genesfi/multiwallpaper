# Multi Wallpaper Live Changer

A professional, high-performance Android Live Wallpaper application designed to cycle through multiple images from your local storage with advanced visual effects and deep optimizations for modern launchers like HyperOS.

## New & Advanced Features

- **3D Depth Parallax Effect**: Experience a realistic sense of physical depth. Using AI Face Detection data, the subject (person) drifts independently from the background when you tilt your phone.
- **AI Smart Crop**: Automatically identifies the main subject or face in your photos and ensures they are perfectly centered and focused, regardless of screen orientation.
- **HyperOS Optimized (20-Page Sync)**: Specifically engineered for Xiaomi/HyperOS devices. Supports manual sliding across up to 20 home screen pages with perfect image-to-page mapping.
- **Background Rotation (Screen Off Support)**: Wallpapers continue to rotate even when the screen is off or the device is locked, ensuring a fresh view every time you wake your phone.
- **Parallel Image Loading**: Instant wallpaper updates! Background pages load concurrently with throttled CPU usage to prevent lag while ensuring all pages are ready in seconds.
- **Animated Loading State**: Seamless visual feedback with a modern spinning circle indicator for pages that are still being prepared.
- **Performance (Light) Mode**: Dedicated optimization for lower-end devices. Reduces RAM usage by 50% and uses faster image decoding for a snappy experience even with hundreds of wallpapers.

## Core Features

- **Multi-Folder Support**: Combine multiple directories as your wallpaper sources.
- **In-App File Explorer**: Easy navigation to pick source folders from internal storage.
- **Smooth Transitions**: Customizable 'Slide' and 'Fade' transition effects with adjustable speeds.
- **Flexible Intervals**: Set rotation frequency from 5 seconds up to an hour.
- **Double Tap & Shake to Change**: Quickly skip to the next wallpaper with intuitive gestures.
- **Gallery & Favorites**: Advanced image management with bulk actions and search/filter capabilities.

## How to Use

1. **Permissions**: Grant 'All Files Access' to allow the app to scan your selected image folders.
2. **Setup Source**: Navigate to the **Folders** tab, add your desired directories, and click 'Set as Live Wallpaper'.
3. **Customize**: Go to **Settings** to enable **3D Parallax**, **Smart Crop**, or **Performance Mode** according to your preference.
4. **Interact**: Double-tap your home screen to rotate the wallpaper instantly, or tilt your phone to see the 3D depth effect.

## Credits
- **Developer**: Migi Gustian
- **GitHub**: [Genesfi/multiwallpaper](https://github.com/Genesfi/multiwallpaper)

## Technical Info
- **Package ID**: `gustian.multiwallpaper`
- **Minimum SDK**: API 24 (Android 7.0)
- **Engine**: Custom WallpaperService with Choreographer-based rendering.
