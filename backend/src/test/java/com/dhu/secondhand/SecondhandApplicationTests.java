package com.dhu.secondhand;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
		"spring.mail.username=test@example.com",
		"spring.mail.test-connection=false",
		"spring.datasource.url=jdbc:h2:mem:secondhand_test;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password="
})
class SecondhandApplicationTests {

	@MockitoBean
	private JavaMailSender mailSender;

	@Test
	void contextLoads() {
	}
}
