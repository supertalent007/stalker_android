plugins {
  id("signal-sample-app")
}

android {
  namespace = "org.signal.pagingtest"

  defaultConfig {
    applicationId = "org.signal.pagingtest"
  }
}

dependencies {
  implementation(project(":paging"))
}
