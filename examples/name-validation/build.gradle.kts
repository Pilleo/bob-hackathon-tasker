plugins {
    kotlin("jvm") version "2.4.20-Beta1"
    application
}
repositories { mavenCentral() }
kotlin { jvmToolchain(25) }
application { mainClass.set("sample.MainKt") }
