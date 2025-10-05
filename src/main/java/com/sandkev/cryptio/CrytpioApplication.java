package com.sandkev.cryptio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = "com.sandkev.cryptio.domain")
@EnableJpaRepositories(basePackages = "com.sandkev.cryptio")
public class CrytpioApplication {

	public static void main(String[] args) {
		SpringApplication.run(CrytpioApplication.class, args);
	}

}
