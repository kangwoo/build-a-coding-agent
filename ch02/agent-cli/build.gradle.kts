plugins { application }

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// 소스에 한글 문자열 리터럴이 있다 — UTF-8을 명시해야 비 UTF-8 기본 플랫폼(예: Windows)에서도 컴파일된다.
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // 동작 확인용 테스트(2.4절). 본문 코드(main)는 Jackson 하나에만 의존한다.
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")   // Gradle 9 대비(자동 로딩 폐지)
}

tasks.test { useJUnitPlatform() }

application { mainClass.set("agent.warmup.Main") }   // 3장에서 agent.cli.Main으로 교체
