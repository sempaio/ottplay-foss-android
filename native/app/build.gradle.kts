import java.io.File
import java.util.Properties

plugins { id("com.android.application"); kotlin("plugin.compose"); kotlin("plugin.serialization") }

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val sourceRevision = providers.environmentVariable("GITHUB_SHA").orNull
    ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "local"
val signingVariant = providers.environmentVariable("OTTPLAY_SIGNING_VARIANT").orNull
val signingNames = listOf("OTTPLAY_KEYSTORE", "OTTPLAY_STORE_PASSWORD", "OTTPLAY_KEY_ALIAS", "OTTPLAY_KEY_PASSWORD")
val signingValues = signingNames.associateWith { providers.environmentVariable(it).orNull }
if (signingVariant != null || signingValues.values.any { it != null }) {
    require(signingVariant in listOf("preview", "release")) { "OTTPLAY_SIGNING_VARIANT must be preview or release" }
    require(signingValues.values.all { !it.isNullOrEmpty() }) { "All four OTTPLAY signing variables are required" }
    require(File(signingValues.getValue("OTTPLAY_KEYSTORE")!!).let { it.isAbsolute && it.isFile }) {
        "OTTPLAY_KEYSTORE must point to an existing absolute keystore path"
    }
}
android {
    namespace = "play.ott.nativeapp"
    compileSdk = 37
    defaultConfig {
        applicationId = "br.starlink.iptv"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.getProperty("versionCode").toInt().also { require(it in 1..2100000000) }
        versionName = appVersion.getProperty("versionName").also { require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "ALLOW_INSECURE_HTTP", "false")
        buildConfigField("boolean", "CUSTOMER_MODE", "false")
        buildConfigField("boolean", "ARTWORK_ORIGINS", "false")
        buildConfigField("String", "SOURCE_REVISION", "\"$sourceRevision\"")
        manifestPlaceholders["allowCleartextTraffic"] = "false"
    }
    testBuildType = "layout"
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs.create("layoutStable") {
        val path = providers.environmentVariable("STARLINK_LAYOUT_KEYSTORE").orNull
        storeFile = path?.let { file(it) } ?: file(System.getProperty("user.home") + "/.starlink-signing/layout.jks")
        storePassword = providers.environmentVariable("STARLINK_LAYOUT_STORE_PASSWORD").orNull
        keyAlias = providers.environmentVariable("STARLINK_LAYOUT_KEY_ALIAS").orNull ?: "starlink-layout"
        keyPassword = providers.environmentVariable("STARLINK_LAYOUT_KEY_PASSWORD").orNull
    }
    if (signingVariant != null) {
        signingConfigs.create("configured") {
            storeFile = file(signingValues.getValue("OTTPLAY_KEYSTORE")!!)
            storePassword = signingValues.getValue("OTTPLAY_STORE_PASSWORD")
            keyAlias = signingValues.getValue("OTTPLAY_KEY_ALIAS")
            keyPassword = signingValues.getValue("OTTPLAY_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug {
            // Local HTTP fixtures are confined to a debuggable, non-publishable test variant.
            buildConfigField("boolean", "ALLOW_INSECURE_HTTP", "true")
            manifestPlaceholders["allowCleartextTraffic"] = "true"
        }
        create("layout") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("layoutStable")
            buildConfigField("boolean", "CUSTOMER_MODE", "true")
            applicationIdSuffix = ".layout"
            versionNameSuffix = "-layout"
            matchingFallbacks += "debug"
            resValue("string", "app_name", "STARLINK IPTV Prévia")
        }
        create("originsTest") {
            initWith(getByName("layout"))
            applicationIdSuffix = ".origins"
            versionNameSuffix = "-origins-test"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "ARTWORK_ORIGINS", "true")
            matchingFallbacks += "layout"
            resValue("string", "app_name", "STARLINK IPTV Origens")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingVariant == "release") signingConfig = signingConfigs.getByName("configured")
        }
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            matchingFallbacks += "release"
            signingConfig = if (signingVariant == "preview") signingConfigs.getByName("configured") else null
        }
        create("full") {
            initWith(getByName("release"))
            applicationIdSuffix = ".full"
            versionNameSuffix = "-full"
            matchingFallbacks += "release"
            buildConfigField("boolean", "ALLOW_INSECURE_HTTP", "true")
            manifestPlaceholders["allowCleartextTraffic"] = "true"
        }
    }
    // AAPT uses the legacy iw resource code; Android locale APIs use canonical he.
    androidResources { localeFilters += listOf("en", "hy", "be", "bg", "fr", "de", "el", "iw", "hu", "it", "lv", "lt", "pl", "pt", "ro", "ru", "es", "tr", "uk", "uz") }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // The in-app picker must work offline after a Play installation in either language.
    bundle { language { enableSplit = false } }
    testOptions { unitTests.isIncludeAndroidResources = true; animationsDisabled = true }
}
tasks.matching { it.name == "prePreviewBuild" }.configureEach {
    doFirst { check(signingVariant == "preview") { "Preview requires its explicitly configured stable preview signing key" } }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    listOf("exoplayer", "exoplayer-hls", "exoplayer-dash", "exoplayer-smoothstreaming", "session", "ui", "datasource-okhttp").forEach {
        implementation("androidx.media3:media3-$it:1.11.1")
    }
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

configurations.named("layoutImplementation") { extendsFrom(configurations.getByName("debugImplementation")) }

androidComponents.beforeVariants(androidComponents.selector().withBuildType("layout")) {
    it.hostTests.values.filter { test -> test.type == "UnitTest" }.forEach { test -> test.enable = true }

}

configurations.named("originsTestImplementation") { extendsFrom(configurations.getByName("debugImplementation")) }
androidComponents.beforeVariants(androidComponents.selector().withBuildType("originsTest")) {
    it.hostTests.values.filter { test -> test.type == "UnitTest" }.forEach { test -> test.enable = true }
}
