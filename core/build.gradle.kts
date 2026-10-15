import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.10"
    `java-library`
}

group = "com.appdwg"
version = "0.1.2"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Android ships XmlPullParser in the platform; on the JVM the tests use kXML2.
    compileOnly("net.sf.kxml:kxml2:2.3.0")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
