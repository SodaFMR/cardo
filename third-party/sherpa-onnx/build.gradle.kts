// Exposes the pinned sherpa-onnx static-link AAR (fetched into third_party/ by artifacts.lock.json) to
// other modules as a project dependency. AGP refuses `files(*.aar)` in library modules that build an AAR
// (:voice:bundleDebugAar), so the AAR is published through a plain Gradle module instead. Android modules
// consume it like any external AAR, so classes and JNI libs land in the app and in androidTest APKs.
configurations.create("default")

artifacts.add("default", file("$rootDir/third_party/sherpa-onnx-static-2.0.1.aar"))
