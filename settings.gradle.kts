pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()

        // ✅ 카카오맵 플러그인/라이브러리 저장소 추가
        maven(url = "https://devrepo.kakao.com/nexus/repository/kakaomap-releases/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // ✅ 카카오맵 전용 저장소 (여기도 추가해야 함)
        maven(url = "https://devrepo.kakao.com/nexus/repository/kakaomap-releases/")
    }
}

rootProject.name = "project-2"
include(":app")
