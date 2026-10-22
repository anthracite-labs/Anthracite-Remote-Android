plugins {
    id("com.android.application")
}

android {
    namespace = "dev.anthracite.remote"
    // androidx.core 1.19.1 requires compileSdk 37. targetSdk stays 36.
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.anthracite.remote"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Signature files are not license texts. Dropping them avoids broken
            // APK resource merges. License and notice files are merged so the
            // upstream wording stays in the APK, and assets/THIRD-PARTY-NOTICES.md
            // carries the consolidated text.
            excludes += setOf(
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
            merges += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.github.mwiede:jsch:2.28.8")
    // JSch selects its Java 15 Ed25519/X25519 classes from a multi-release jar.
    // Android drops those classes and reports Java 8, so the library's own
    // Bouncy Castle implementations are the ones that actually run.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.sshd:sshd-core:2.20.0")
    testImplementation("org.slf4j:slf4j-nop:2.0.20")
}
