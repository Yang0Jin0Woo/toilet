package com.example.toilet;

import com.example.toilet.repository.ToiletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableScheduling
@RequiredArgsConstructor
public class ToiletApplication {

	private final ToiletRepository toiletRepository;

	public static void main(String[] args) {
		SpringApplication.run(ToiletApplication.class, args);
	}

	@Bean
	CommandLineRunner runner() {
		return args -> {
			long cnt = toiletRepository.count();
			System.out.println(">>> Toilet 레코드 수: " + cnt);
		};
	}
}
