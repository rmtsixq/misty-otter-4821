import java.io.File
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/* ---------------------------------------------------------------------------
 * MediaPipe model bundles are ~11 MB in total, so they are not committed.
 * They are fetched into src/main/assets before the first compile instead.
 * ------------------------------------------------------------------------- */
val modelSpecs = listOf(
    "face_landmarker.task" to
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task",
    "hand_landmarker.task" to
        "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task",
)

val downloadMediapipeModels = tasks.register("downloadMediapipeModels") {
    group = "build setup"
    description = "Downloads the MediaPipe face/hand landmarker bundles into src/main/assets."

    val assetsDir = file("src/main/assets")
    val targets = modelSpecs.map { (name, url) -> File(assetsDir, name) to url }

    outputs.files(targets.map { it.first })
    outputs.upToDateWhen { targets.all { (dest, _) -> dest.length() > 1_000_000L } }

    doLast {
        assetsDir.mkdirs()
        targets.forEach { (dest, url) ->
            if (dest.length() > 1_000_000L) {
                logger.lifecycle("MediaPipe model already present: ${dest.name}")
                return@forEach
            }
            logger.lifecycle("Downloading ${dest.name} from $url")
            val partial = File(dest.parentFile, dest.name + ".part")
            partial.delete()
            // `java` alone would resolve to the Java plugin extension here,
            // so the type is imported explicitly at the top of the script.
            URI(url).toURL().openStream().use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (partial.length() < 1_000_000L) {
                partial.delete()
                throw GradleException(
                    "Download of ${dest.name} produced only ${partial.length()} bytes. " +
                        "Check network access to storage.googleapis.com."
                )
            }
            partial.renameTo(dest)
            logger.lifecycle("Saved ${dest.name} (${dest.length()} bytes)")
        }
    }
}

/* Gemini key can be baked in at build time; otherwise it is entered in Settings. */
fun buildTimeGeminiKey(): String {
    val raw = (project.findProperty("GEMINI_API_KEY") as String?)
        ?: System.getenv("GEMINI_API_KEY")
        ?: ""
    return raw.trim().replace("\\", "\\\\").replace("\"", "\\\"")
}

/* Optional release signing: only active when the four properties are supplied. */
val releaseStoreFile = (project.findProperty("SIGNING_STORE_FILE") as String?)
    ?: System.getenv("SIGNING_STORE_FILE")
val releaseStorePassword = (project.findProperty("SIGNING_STORE_PASSWORD") as String?)
    ?: System.getenv("SIGNING_STORE_PASSWORD")
val releaseKeyAlias = (project.findProperty("SIGNING_KEY_ALIAS") as String?)
    ?: System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword = (project.findProperty("SIGNING_KEY_PASSWORD") as String?)
    ?: System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.sixq.gozjest.main"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sixq.gozjest.main"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"${buildTimeGeminiKey()}\"")

        ndk {
            // MediaPipe ships no x86_64 vision library at all, so that ABI
            // could only ever crash on first inference, and no phone runs x86.
            // Dropping both takes about 22 MB off the download.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") { dependsOn(downloadMediapipeModels) }

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.nanohttpd)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}
