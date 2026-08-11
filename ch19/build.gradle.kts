plugins {
    java
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    repositories { mavenCentral() }

    tasks.withType<Test> { useJUnitPlatform() }

    // record 패턴/sealed는 정식 기능이라 별도 preview 플래그가 필요 없다.
    tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
}
