plugins {
  id("signal-library")
}

android {
  namespace = "org.signal.video"
}

dependencies {
  implementation(project(":core-util"))
  implementation(libs.libsignal.android)
  implementation(libs.google.guava.android)

  implementation(libs.bundles.mp4parser) {
    exclude(group = "junit", module = "junit")
  }
}
