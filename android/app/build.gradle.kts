plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spandan.app"
    compileSdk = 35

    defaultConfig {
        // [Segment 36 release] Deliberately DIFFERENT from the `namespace`
        // above (com.spandan.app) -- applicationId, not namespace, is what
        // Android uses to decide "same app, upgrade in place" vs. "different
        // app, install side by side." This lets this build (with the new,
        // not-yet-on-device-verified anatomy-ROI toggle) install alongside
        // whatever com.spandan.app build is already on the device, rather
        // than overwriting it. No Kotlin source package/namespace changed --
        // this is the standard, minimal way to fork an installable identity
        // without touching code structure.
        applicationId = "com.spandan.app.anatomy"
        // CameraX and ML Kit Face Detection both support minSdk 21; 24 is used
        // here as a reasonable modern baseline for a course project, not a
        // hard library requirement.
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.4.0-anatomy-roi"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Segment 19 -- MorphologyWaveformEstimator (and RealHeartRateEstimator/
    // LiveSpo2Estimator before it, just never previously exercised by a
    // plain-JUnit test) call android.util.Log.d/.w directly. Log's real
    // implementation throws "not mocked" under plain JUnit (unlike
    // android.graphics.Rect, whose methods are plain Java with no native
    // call, and so already worked fine in CoordinateMapperTest/
    // OpticalFlowMatcherTest without this). This makes every unmocked
    // android.* call return a harmless default (Log.d/.w return 0) instead
    // of throwing, rather than wrapping every estimator's logging behind a
    // new testable indirection layer for no other purpose.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // --- CameraX: preview + on-device frame analysis ---
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // --- ML Kit Face Detection (bundled model: works fully offline, no
    // Play Services download step needed, which is one less moving part for
    // a lab demo) ---
    implementation("com.google.mlkit:face-detection:16.1.7")

    // --- MediaPipe Tasks Vision (Segment 36): FaceLandmarker, gated behind
    // the "anatomy ROI" toggle (MainActivity's anatomyRoiSwitch), OFF by
    // default. Same artifact android_segment30_mediapipe/ already used for
    // its own (rejected, on different grounds -- see AnatomyRoiFaceAnalyzer.kt's
    // own KDoc) FaceDetector experiment; verified against the real
    // maven-metadata.xml there, not guessed. Ships the app's own APK size up
    // (native .so + the bundled face_landmarker.task model, ~3.75MB, in
    // assets/) regardless of whether the toggle is ever switched on, since
    // Gradle has no per-flag dependency exclusion -- a real, accepted cost
    // of adding the toggle at all, not hidden.
    implementation("com.google.mediapipe:tasks-vision:1.0.0")

    // --- JTransforms: real FFT for signal/HeartRateFft.kt (matlab/src/heartrate/
    // fftHeartRate.m ported) --- added now that a real FFT step actually exists;
    // previously deferred (see android/README.md history) while HR was a placeholder.
    implementation("com.github.wendykierp:JTransforms:3.1")

    // NOTE (deliberate, not an oversight): the OpenCV Android SDK is NOT added.
    //  - ROI pixel averaging is done directly on the CameraX YUV_420_888
    //    planes (see camera/RoiPixelAverager.kt) without needing OpenCV's
    //    Mat/Bitmap machinery. If a later stage needs real image-processing
    //    ops (e.g. matching a MATLAB `imresize`/filtering step exactly),
    //    add OpenCV Android SDK then.
    // Flagged for the team to confirm against Spandan_Orientation_Lecture.pdf
    // if/when that document turns up -- see android/README.md.

    testImplementation("junit:junit:4.13.2")
}
