plugins {
  id("signal-library")
}

android {
  namespace = "org.signal.spinner"
}

dependencies {
  implementation(project(":core-util"))

  implementation(libs.jknack.handlebars)
  implementation(libs.nanohttpd.webserver)
  implementation(libs.nanohttpd.websocket)
  implementation(libs.androidx.sqlite)

  testImplementation(testLibs.junit.junit)
}
