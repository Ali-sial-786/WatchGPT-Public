plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "io.github.alisial786.watchgpt.organizer"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    api(project(":core:reminders"))
    implementation(platform(libs.kotlinx.coroutines.bom))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
