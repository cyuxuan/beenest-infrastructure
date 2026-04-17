package club.beenest.payment.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.Date;

/**
 * 标准响应包装类型。
 */
@Data
@Accessors(chain = true)
@Schema(name = "Response", description = "标准响应包装类型")
public class Response<T> {
    @Schema(description = "业务码（200表示成功）")
    private int code;
    @Schema(description = "提示信息")
    private String message;
    @Schema(description = "响应数据")
    private T data;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Schema(description = "响应时间")
    private Date timestamp;

    public static <T> Response<T> success(T data) {
        return new Response<T>()
                .setCode(200)
                .setMessage("OK")
                .setData(data)
                .setTimestamp(new Date());
    }

    public static <T> Response<T> success() {
        return success(null);
    }

    public static <T> Response<T> fail(int code, String message) {
        return new Response<T>()
                .setCode(code)
                .setMessage(message)
                .setTimestamp(new Date());
    }
}
