plugins {
    // AGP 9 enables optimized resource shrinking with isShrinkResources (Play "R8 optimization").
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
