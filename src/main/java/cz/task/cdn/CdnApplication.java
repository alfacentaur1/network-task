package cz.task.cdn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

//do not auto-configure datasource, we do not use any database, we just send data to clickhouse via http
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableScheduling
public class CdnApplication {

	public static void main(String[] args) {
		SpringApplication.run(CdnApplication.class, args);
	}

}
