# Krypton IDE

![Krypton IDE](https://via.placeholder.com/1200x400/1E1E1E/FFFFFF?text=Krypton+IDE) 

Krypton IDE is a powerful, modern Integrated Development Environment for Android, built natively with Kotlin and Jetpack Compose. Combining the native performance of Android with a real, hardware-accelerated code editing surface (Sora Editor), Krypton IDE permits you to write, edit, and manage code effortlessly on the go.

## ✨ Features

- **Native Code Editing**: A real Android View editing surface (Sora Editor) with hardware-accelerated rendering and native touch selection — no WebView involved.
- **Rich Language Support**: Advanced syntax highlighting, code folding, bracket matching, block indentations, and smart suggestions.
- **Floating Command Palette**: Quickly search and execute editor commands and actions with a beautiful, intuitive palette.
- **Mobile-First Interactions**: Custom touch interactions optimized for handheld devices, including double-tap-to-edit and smooth scrolling mechanics.
- **Status Footer & Breadcrumbs**: Live feedback on the current file scope, cursor position (line and column), language mode, and encoding right on your screen.
- **Animated Side Explorer**: A smooth, gesture-driven side drawer designed for effortlessly traversing deep project directories.
- **Plugin Ecosystem**: Extend your mobile coding capabilities via a built-in Plugin Manager with one-tap modular additions.
- **Larry AI Integration**: Your built-in AI coding assistant ready to assist with refactoring, fixing bugs, and generating new boilerplate code.
- **GitHub Integration**: Connect to remote repositories and manage version control effortlessly on your device.
- **Global Code Search**: Powerful cross-file search panel to quickly locate logic and functions in large codebases.

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug (or newer recommended)
- Android SDK 34
- JDK 17
- Gradle 8+

### Building from Source

1. **Clone the repository**
   ```sh
   git clone https://github.com/yourusername/kryptonide.git
   ```
2. **Open the project in Android Studio**
   Open the cloned directory in Android Studio. The IDE will automatically sync the Gradle configuration.
3. **Run the App**
   Select your emulator or physical Android device and hit **Run** (or `Shift + F10`). 

## 🛠️ Architecture

Krypton IDE is built upon modern Android architecture patterns:
- **UI Toolkit**: 100% Jetpack Compose for a responsive, clean, and declarative user interface.
- **State Management**: Unidirectional Data Flow (UDF) powered by Jetpack ViewModels and Kotlin `StateFlow`.
- **Native Editor Engine**: `SoraEditorHost.kt` wraps Sora Editor's `CodeEditor` View directly — no bridge, no WebView round-trip.
- **Theming**: Adheres to Material Design 3 guidelines with a sleek, low-strain dark mode tailored specifically for developers.

## 🤝 Contributing

Contributions are what make the open-source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
