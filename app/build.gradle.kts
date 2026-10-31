import java.io.File
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
val releaseStoreFileFromProperties = releaseProperties.getProperty("storeFile")
    ?.takeIf(String::isNotBlank)
    ?.let(rootProject::file)

fun signingEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

fun signingEnvKeystore(): File? {
    val raw = signingEnv("MOCKGPS_KEYSTORE_FILE") ?: return null
    val candidate = File(raw)
    return if (candidate.isAbsolute) candidate else rootProject.file(raw)
}

val propertiesSigningReady =
    releasePropertiesFile.isFile &&
        hasReleaseProperties &&
        releaseStoreFileFromProperties?.isFile == true
val envKeystoreFile = signingEnvKeystore()
val envStorePassword = signingEnv("MOCKGPS_STORE_PASSWORD")
val envKeyAlias = signingEnv("MOCKGPS_KEY_ALIAS")
val envKeyPassword = signingEnv("MOCKGPS_KEY_PASSWORD")
// keystore.properties wins when it exists. Environment variables are the CI fallback.
val envSigningReady =
    !releasePropertiesFile.isFile &&
        envKeystoreFile?.isFile == true &&
        envStorePassword != null &&
        envKeyAlias != null &&
        envKeyPassword != null

val releaseStoreFile: File? = when {
    propertiesSigningReady -> releaseStoreFileFromProperties
    envSigningReady -> envKeystoreFile
    else -> null
}
val releaseStorePassword: String? = when {
    propertiesSigningReady -> releaseProperties.getProperty("storePassword")
    envSigningReady -> envStorePassword
    else -> null
}
val releaseKeyAlias: String? = when {
    propertiesSigningReady -> releaseProperties.getProperty("keyAlias")
    envSigningReady -> envKeyAlias
    else -> null
}
val releaseKeyPassword: String? = when {
    propertiesSigningReady -> releaseProperties.getProperty("keyPassword")
    envSigningReady -> envKeyPassword
    else -> null
}
val hasReleaseSigning =
    releaseStoreFile != null &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

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
        if (hasReleaseSigning) {
            val store = releaseStoreFile
            val storePass = releaseStorePassword
            val alias = releaseKeyAlias
            val keyPass = releaseKeyPassword
            check(store != null && storePass != null && alias != null && keyPass != null)
            create("release") {
                storeFile = store
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
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
    description = "Checks release signing material without affecting debug builds."
    doLast {
        if (!hasReleaseSigning) {
            throw GradleException(
                "缺少 release keystore。本機請依 docs/build-apk.md 建立 keystore.properties；" +
                    "CI 請設定 MOCKGPS_KEYSTORE_FILE、MOCKGPS_STORE_PASSWORD、MOCKGPS_KEY_ALIAS 與 MOCKGPS_KEY_PASSWORD。",
            )
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
