group = "com.threesln.emon_ocr_mlkit"
version = "0.0.0-dev"

buildscript {
    val kotlinVersion = "2.4.20"
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.3.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
}

// WHICH RECOGNISERS AND WHICH VARIANT (see lib/emon_ocr_mlkit.dart). The app's android/gradle.properties says:
//   emonMlkitScripts=latin,chinese,japanese,korean,devanagari    (default: latin)
//   emonMlkitVariant=bundled | unbundled                          (default: bundled: the models are in the app; unbundled: Google Play services holds them)
val scripts = ((project.findProperty("emonMlkitScripts") as String?) ?: "latin").split(",").map { it.trim() }.filter { it.isNotEmpty() }
val unbundled = (project.findProperty("emonMlkitVariant") as String?) == "unbundled"
val mlkitVersion = "16.0.1"
val playServicesVersion = "19.0.1"

android {
    namespace = "com.threesln.emon_ocr_mlkit"

    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin", if (unbundled) "src/unbundled/kotlin" else "src/bundled/kotlin")
        }
    }

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "EMON_MLKIT_SCRIPTS", "\"${scripts.joinToString(",")}\"")
        buildConfigField("String", "EMON_MLKIT_VERSION", "\"$mlkitVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    if (unbundled) {
        // the models live in Google Play services: about 260 KB each in the app, downloaded when first needed (ModelInstaller reports it)
        implementation("com.google.android.gms:play-services-mlkit-text-recognition:$playServicesVersion")
        scripts.filter { it != "latin" }.forEach { implementation("com.google.android.gms:play-services-mlkit-text-recognition-$it:16.0.1") }
        implementation("com.google.android.gms:play-services-base:18.5.0")
    } else {
        // the models are in the app: deterministic, offline, no Play services
        implementation("com.google.mlkit:text-recognition:$mlkitVersion")
        scripts.filter { it != "latin" }.forEach { implementation("com.google.mlkit:text-recognition-$it:$mlkitVersion") }
    }
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
}
