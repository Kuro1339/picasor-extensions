plugins {
    id("com.android.application")
    kotlin("android")
    alias(kei.plugins.extension)
}

ext {
    set("extName", "Picazor")
    set("extClass", ".Picazor")
    set("extVersionCode", 1)
}

dependencies {
    implementation(project(":lib"))
}
