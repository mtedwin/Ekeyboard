# Android Virtual Input Tool (`Ekeyboard`)

A custom Android application developed using Android Studio designed to explore mobile UI inputs, soft keyboard mechanics, and system-level event triggers. This project showcases hands-on experience with mobile software development, peripheral device simulation, and the Android SDK architecture.

## 🚀 Key Features
* **Custom IME / Soft Keyboard Implementation:** Explores the Android `InputMethodService` lifecycle to render and manage a functional virtual input interface.
* **Responsive Mobile Layouts:** UI components engineered using XML and Android material design principles to ensure optimal scaling across different screen densities.
* **Event Handlers & Intercepts:** Captured and processed key events smoothly, ensuring low-latency touch response within the Android environment.
* **Component Lifecycle Management:** Built with efficient memory management to comply with mobile OS background process restrictions.

## 🛠️ Technology Stack
* **IDE:** Android Studio
* **Languages:** Java / Kotlin
* **Framework:** Android SDK (Targeting modern API levels)
* **Core Components:** `InputMethodService`, `Keyboard`, `KeyboardView` (or custom canvas layouts)

## 📦 Project Setup & Build Instructions

### 1. Prerequisites
* Android Studio (Ladybug or newer recommended)
* Android SDK (API Level 30+)
* A physical Android device with Developer Mode enabled or an active Android Virtual Device (AVD) Emulator.

### 2. Installation & Compilation
Clone this repository to your local machine:
   ```bash
   git clone [https://github.com/mtedwin/Ekeyboard.git](https://github.com/mtedwin/Ekeyboard.git)

   Open Android Studio.

Select File > Open and choose the cloned Ekeyboard project folder.

Allow Gradle to sync and download necessary dependencies.

Click the Run button (Shift + F10) to compile the APK and deploy it to your emulator or connected device.
