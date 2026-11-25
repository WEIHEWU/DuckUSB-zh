import com.v7878.zygisk.gradle.ZygoteLoader
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.zygoteloader)
}

val appPackageName: String by rootProject.extra
val moduleId: String by rootProject.extra
val moduleVersionName: String by rootProject.extra
val moduleVersionCode: Int by rootProject.extra

val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val signStoreFile: String? = keystoreProperties.getProperty("storeFile") ?: System.getenv("DUCKUSB_STORE_FILE")
val signStorePassword: String? = keystoreProperties.getProperty("storePassword") ?: System.getenv("DUCKUSB_STORE_PASSWORD")
val signKeyAlias: String? = keystoreProperties.getProperty("keyAlias") ?: System.getenv("DUCKUSB_KEY_ALIAS")

val buildManagerCertSha256: String = runCatching {
    val path = signStoreFile ?: return@runCatching ""
    val password = signStorePassword ?: return@runCatching ""
    val alias = signKeyAlias ?: return@runCatching ""
    val ksFile = rootProject.file(path)
    if (!ksFile.exists()) return@runCatching ""
    val ks = KeyStore.getInstance(ksFile, password.toCharArray())
    val cert = ks.getCertificate(alias) ?: return@runCatching ""
    MessageDigest.getInstance("SHA-256")
        .digest(cert.encoded)
        .joinToString("") { "%02x".format(it) }
}.getOrDefault("")

android {
    namespace = "$appPackageName.zygote"
    compileSdk = 36
    ndkVersion = (findProperty("duckusbNdk") as String?) ?: "29.0.14206865"

    defaultConfig {
        applicationId = namespace
        minSdk = 29
        targetSdk = 36
        versionCode = moduleVersionCode
        versionName = moduleVersionName
        buildConfigField("String", "MANAGER_CERT_SHA256", "\"$buildManagerCertSha256\"")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

zygisk {
    packages(ZygoteLoader.PACKAGE_SYSTEM_SERVER)

    id = moduleId
    name = "DuckUSB (Zygisk)"
    author = "XxxY"
    description = "Hides USB debugging, wireless debugging and Developer Options from chosen apps, and suppresses the USB debugging notification."
    entrypoint = "$appPackageName.zygote.ZygoteEntry"
    archiveName = "DuckUSB-Zygisk-$moduleVersionName"
    attachNativeLibs = true
    isAddVariantToArchiveName = true
}

dependencies {
    implementation(projects.common)
    implementation(libs.androidx.annotation.jvm)
    implementation(libs.r8.annotations)
}
