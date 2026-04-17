package club.beenest.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 支付中台启动类
 */
@SpringBootApplication(scanBasePackages = {
        "club.beenest.payment"
})
@MapperScan("club.beenest.payment.mapper")
@EnableFeignClients(basePackages = "club.beenest.payment.feign")
@EnableScheduling
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
