// MorseCode - root Gradle build.
// The app intentionally has zero third-party dependencies: plain Android Views, no Compose,
// no AndroidX, no coroutines. Everything the offline toolchain cannot resolve is absent by design.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
