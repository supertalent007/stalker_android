plugins {
  id("signal-library")
}

android {
  namespace = "org.signal.qr"
}

dependencies {
  implementation(project(":core-util"))

  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.lifecycle.common.java8)
  implementation(libs.androidx.lifecycle.livedata.core)

  implementation(libs.google.guava.android)
  implementation(libs.google.zxing.android.integration)
  implementation(libs.google.zxing.core)
}
