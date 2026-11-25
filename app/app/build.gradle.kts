plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Version: -PappVersion=X.Y.Z (release tag), else the repo version file (../.release-please-manifest.json, one version
// for the whole monorepo). versionCode = X*10000 + Y*100 + Z.
val repoVersion: String? = rootProject.file("../.release-please-manifest.json").takeIf { it.isFile }?.readText()
    ?.let { Regex("\"\\.\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
val appVersion = (findProperty("appVersion") as String?) ?: repoVersion ?: "0.1.0"
val appVersionCode = appVersion.substringBefore('-').split('.').let { p ->
    require(p.size == 3 && p.all { it.toIntOrNull() != null }) { "appVersion must be X.Y.Z, got '$appVersion'" }
    require(p.all { it.matches(Regex("0|[1-9][0-9]?")) }) { "Version components must each be 0..99 without leading zeros (versionCode = X*10000+Y*100+Z); got '$appVersion'" }
    p[0].toInt() * 10000 + p[1].toInt() * 100 + p[2].toInt()
}

// Release signing: AUDIOBOOKS_KEYSTORE_FILE / _PASSWORD, AUDIOBOOKS_KEY_ALIAS / _PASSWORD from the environment
// or Gradle properties. Without them the release build is signed with the debug key (local use only).
fun signingValue(name: String): String? = providers.environmentVariable(name).orNull
    ?: providers.gradleProperty(name).orNull
val releaseKeystoreFile = signingValue("AUDIOBOOKS_KEYSTORE_FILE")
val hasReleaseSigning = listOf("AUDIOBOOKS_KEYSTORE_FILE", "AUDIOBOOKS_KEYSTORE_PASSWORD", "AUDIOBOOKS_KEY_ALIAS", "AUDIOBOOKS_KEY_PASSWORD")
    .all { !signingValue(it).isNullOrBlank() }

android {
    namespace = "xyz.aresius.audiobooks"
    compileSdk = 37

    defaultConfig {
        applicationId = "xyz.aresius.audiobooks"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        androidResources.localeFilters += listOf("en", "nl")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = signingValue("AUDIOBOOKS_KEYSTORE_PASSWORD")
                keyAlias = signingValue("AUDIOBOOKS_KEY_ALIAS")
                keyPassword = signingValue("AUDIOBOOKS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            // Debug builds only: the in-process FakeBackend (see app/README.md) and plain http to any host.
            buildConfigField("boolean", "DEBUG_TOOLS", "true")
        }
        release {
            isDebuggable = false
            buildConfigField("boolean", "DEBUG_TOOLS", "false")
            // TODO: enable R8 (isMinifyEnabled/isShrinkResources) once keep rules cover Hilt, Room and serialization.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("WARNING: AUDIOBOOKS_KEYSTORE_* not set: release APK is signed with the DEBUG key (not for distribution)")
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        // Media3's @UnstableApi is androidx's lint-only opt-in marker; the player is built on exactly those APIs (version pinned in libs.versions.toml).
        disable += "UnsafeOptInUsageError"
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.androidx.palette)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.tink.android)

    implementation(libs.androidx.work.runtime)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}
