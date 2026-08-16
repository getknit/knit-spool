dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "knit-spool"

include(":protocol", ":daemon", ":conformance")
