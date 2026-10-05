plugins { id("com.android.application") }
val podAppId = providers.gradleProperty("podAppId").orElse("dev.podjs.androidwatch").get()
val podAppName = providers.gradleProperty("podAppName").orElse("PodJS Gallery").get()
val podVersionName = providers.gradleProperty("podVersionName").orElse("0.1.0").get()
val podVersionCode = providers.gradleProperty("podVersionCode").map(String::toInt).orElse(1).get()
val podDistDir = providers.gradleProperty("podDistDir").orElse("../../../dist/android-watch").get()
val podAppTheme = providers.gradleProperty("podAppTheme").orElse("@style/AppTheme").get()
android {
    androidResources { noCompress += "pak" }
    namespace = "dev.podjs.androidwatch"; compileSdk = 36
    defaultConfig { applicationId = podAppId; minSdk = 30; targetSdk = 36; versionCode = podVersionCode; versionName = podVersionName; manifestPlaceholders["podAppName"] = podAppName; manifestPlaceholders["podAppTheme"] = podAppTheme; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets["main"].assets.srcDir(podDistDir)
    providers.gradleProperty("podResDir").orNull?.let { sourceSets["main"].res.srcDir(it) }
    providers.gradleProperty("podTestAssetsDir").orNull?.let { sourceSets["androidTest"].assets.srcDir(it) }
    providers.gradleProperty("podTestSourceDir").orNull?.let { sourceSets["androidTest"].java.srcDir(it) }
}
dependencies {
    implementation(project(":runtime"))
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.work:work-runtime:2.11.2")
    androidTestImplementation("com.google.guava:guava:33.3.1-android")
}
