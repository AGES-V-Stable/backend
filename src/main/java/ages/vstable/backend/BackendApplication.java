package ages.vstable.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@io.swagger.v3.oas.annotations.security.SecurityScheme(name = "bearerAuth", type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP, bearerFormat = "JWT", scheme = "bearer")
@io.swagger.v3.oas.annotations.OpenAPIDefinition(security = {@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")})

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
