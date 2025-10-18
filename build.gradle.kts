plugins {
	id("java")
	id("org.springframework.boot") version "3.5.6"
	id("io.spring.dependency-management") version "1.1.7"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
group = "com.sandkev.cryptio"
version = "0.0.1-SNAPSHOT"
description = "Manage Crypto Portfolio"

repositories { mavenCentral() }

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	runtimeOnly("org.postgresql:postgresql:42.7.3")
	implementation("org.flywaydb:flyway-core:10.16.0")

	// XChange (choose only the exchanges you need)
	implementation("org.knowm.xchange:xchange-core:5.2.2")
	implementation("org.knowm.xchange:xchange-binance:5.2.2")
	implementation("org.knowm.xchange:xchange-coinbasepro:5.2.2")

	// Optional: ta4j
	implementation("org.ta4j:ta4j-core:0.16")

	// JSON + util
	implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
	testImplementation("org.springframework.boot:spring-boot-starter-test")

	implementation("org.springframework.boot:spring-boot-starter-webflux") // WebClient
	implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")         // in-memory cache
	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")// retries/backoff
	testImplementation("org.springframework.boot:spring-boot-starter-test")

	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	runtimeOnly("com.h2database:h2:2.2.224") // <-- add this for dev
	// if you plan Postgres later, keep runtimeOnly("org.postgresql:postgresql:42.7.4")

	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("org.springframework.boot:spring-boot-starter-jdbc") // for JdbcTemplate upserts

	testImplementation("com.github.tomakehurst:wiremock-jre8:2.35.2")
	testImplementation("org.assertj:assertj-core:3.26.3")

	// Lombok as compile-only + annotation processor
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	// tests
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")

	implementation("org.springframework.boot:spring-boot-starter-security")

}

// Move ALL Gradle outputs away from OneDrive
layout.buildDirectory.set(file("C:/dev/_gradle_builds/cryptio"))

tasks.test {
	useJUnitPlatform()
}