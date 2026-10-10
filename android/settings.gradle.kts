// Génesis Gym N — app nativa de Android (Desarrollador 3, Proyecto Génesis).
// Vive en la carpeta android/ del repositorio genesis-gym. La 2.0 (PWA, en la raíz) no depende de esta carpeta.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "GenesisGymN"
include(":app")
