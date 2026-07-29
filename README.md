# Multi Wallpaper Live Changer

A professional, high-performance Android Live Wallpaper application designed to cycle through multiple images from your local storage with advanced visual effects and deep optimizations for modern launchers like HyperOS.

## What's New

### v2.0: The Ultimate Stability & Smart Panoramic Update (Major)
#### 🧠 Panoramic & Page Intelligence
- **Smart Panoramic Engine**: Introduced **Linear Clockwise Fill** logic for consistent wide-image distribution across 2-3 pages without fragmentation.
- **Manual Page Control**: Added **Manual Page Count** setting to fix synchronization issues on launchers that don't report page steps (specifically optimized for **HyperOS/Xiaomi**).
- **Customizable Span**: Users can now set the maximum number of pages a single image can stretch across.
- **Smart Adjacency Locking**: Intelligent algorithm to prevent images from the same folder from appearing on adjacent pages.

#### ⏰ Automation & Independent Management
- **Full Time-Based Scheduling**: New **Automation Hub** to schedule different Presets, Color Filters, and Visual Effects based on time and day of the week.
- **Independent Home & Lock Screens**: Fully decoupled configurations. Set different image sources, effects, and rotation intervals for Home and Lock screens independently.

#### 🛡️ Extreme Stability & Recovery
- **Panic Repair System**: Immediate high-priority background fill if the engine detects an empty page slot during swiping.
- **Auto-Retry & Sync Check**: Robust recovery mechanism that automatically retries image decoding and performs a final sync check to ensure zero "stuck loading" states.
- **Non-Aggressive Rotation**: Optimized transition logic that keeps old wallpapers visible until the new ones are fully rendered, preventing black gaps.

#### 🎨 Pro Artistic & AI Tools
- **Advanced Color Filter Engine**: Introduced professional **Duotone** and **Tritone** filters with a full **Custom Palette Manager** (Save/Randomize/Apply).
- **Advanced AI Tuning**: Precision sliders for **AI Zoom Slack** and **Sensitivity** (X/Y) to fine-tune how the engine centers faces.
- **Manual Focal Editor**: A new UI tool to manually set the focus area if AI detection isn't desired for specific images.

#### 🔋 Efficiency & Global Control
- **Hibernation Mode**: Deep sleep state that stops all sensors, rendering, and purges RAM memory (RAM Flush) when the wallpaper is disabled.
- **1x1 Global Control Widget**: A precision-aligned Home Screen widget to toggle the service ON/OFF for both screens with a single tap.
- **OLED Black Support**: Backgrounds and loading states now use pure **#000000 Black** to maximize battery savings on AMOLED displays.

#### 📂 Advanced Data & History
- **Total Data Backup**: Export/Import your entire application state (Presets, History, Schedules, and Folders) into a single JSON file.
- **Advanced History Review**: New UI to browse, preview, manually release, or blacklist images directly from the rotation history list.
- **Wallpaper Quality Profiles**: Choose between **Low** (16-bit), **Normal**, and **High** (32-bit) quality to balance performance and visual fidelity.

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
