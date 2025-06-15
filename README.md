# SatEnDroid - ZIP Image Viewer

Android app for viewing images from ZIP files with Dropbox integration support.

## Features

- 📱 **Full-screen image viewing** with swipe navigation
- 📁 **Local ZIP file support** from device storage
- ☁️ **Dropbox integration** for cloud-based ZIP files
- 🎨 **Material Design 3** UI
- 🖱️ **Intuitive touch controls** (tap for info, swipe for navigation)

## Setup

### Prerequisites

- Android Studio Arctic Fox or later
- Android SDK 28 or higher
- Kotlin 1.8+

### Building the Project

1. Clone the repository
2. Copy `local.properties.template` to `local.properties`
3. Configure Dropbox integration (optional):
   - Create a Dropbox app at [Dropbox App Console](https://www.dropbox.com/developers/apps)
   - Add your App Key to `local.properties`: `DROPBOX_APP_KEY=your_key_here`
4. Build and run

### Dropbox Integration Setup

For full Dropbox functionality:

1. **Create Dropbox App**:
   - Visit [Dropbox App Console](https://www.dropbox.com/developers/apps)
   - Choose "Scoped access" and "Full Dropbox"
   - Name your app (e.g., "SatEnDroid")

2. **Configure OAuth**:
   - Add redirect URI: `com.celstech.satendroid://oauth`
   - Copy your App Key

3. **Update Configuration**:
   - Add to `local.properties`: `DROPBOX_APP_KEY=your_app_key_here`

4. **Test**:
   - Build and run the app
   - Tap "Dropbox Files" and connect your account

## Usage

### Viewing Images

1. **Start**: Tap "Select ZIP File" on the main screen
2. **Choose Source**: Select "Local Files" or "Dropbox Files"
3. **Navigate**: Swipe left/right to browse images
4. **Info**: Tap image to show/hide controls
5. **New File**: Tap top area twice to select different ZIP

### Supported Formats

- **ZIP Files**: Standard ZIP archives
- **Images**: JPG, JPEG, PNG, GIF, BMP, WebP

## Architecture

- **MVVM** pattern with Compose
- **Coroutines** for async operations
- **Material Design 3** components
- **Modular** structure for easy maintenance

## Dependencies

- Jetpack Compose
- Coil (image loading)
- Dropbox Core SDK
- Accompanist Permissions
- Material Design 3

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

1. Fork the repository
2. Create your feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## Security

- API keys are managed through BuildConfig
- Credentials are stored securely using Android's secure storage
- No sensitive data is logged or exposed
