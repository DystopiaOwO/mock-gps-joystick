import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releasePropertiesFile = rootProject.file("keystore.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.isFile) {
        releasePropertiesFile.inputStream().use(::load)
    }
}
val releaseKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasReleaseProperties = releaseKeys.all { !releaseProperties.getProperty(it).isNullOrBlank() }
val releaseStoreFile = releaseProperties.getProperty("storeFile")
    ?.takeIf(String::isNotBlank)
    ?.let(rootProject::file)

android {
    namespace = "tw.dystopia.mockgpsjoystick"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.dystopia.mockgpsjoystick"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (releasePropertiesFile.isFile && hasReleaseProperties && releaseStoreFile?.isFile == true) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Keep the same applicationId as release so Mock App selection is stable.
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Checks local release signing files without affecting debug builds."
    doLast {
        if (!releasePropertiesFile.isFile || !hasReleaseProperties || releaseStoreFile?.isFile != true) {
            throw GradleException("缺少 release keystore，請先依 docs/build-apk.md 建立簽名檔。")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

val packageDebugApk by tasks.registering(Copy::class) {
    group = "build"
    description = "Builds and copies the versioned debug APK to dist/."
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "MockGpsJoystick-v0.2.0-debug.apk" }
}

val packageReleaseApk by tasks.registering(Copy::class) {
    group = "build"
    description = "Builds and copies the signed versioned release APK to dist/."
    dependsOn(packageDebugApk, "assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "MockGpsJoystick-v0.2.0-release.apk" }
}

validateReleaseSigning {
    mustRunAfter(packageDebugApk)
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    mustRunAfter(packageDebugApk)
}

tasks.register("packageApks") {
    group = "build"
    description = "Packages debug first, then the signed release APK."
    dependsOn(packageReleaseApk)
}
