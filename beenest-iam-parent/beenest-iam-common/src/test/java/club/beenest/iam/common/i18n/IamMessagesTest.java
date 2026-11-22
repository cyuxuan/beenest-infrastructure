package club.beenest.iam.common.i18n;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态文案门面测试。
 * <p>{@link IamMessages} 是给静态工厂({@code Result} / {@code BusinessException})用的,
 * 拿不到容器,只能靠启动时注册一次。两个关键性质必须成立:</p>
 * <ol>
 *   <li><b>未注册时回落错误码默认文案</b>——单元测试、批处理、命令行工具不起容器也不能抛 NPE;</li>
 *   <li><b>注册后按语言解析</b>——注册只发生一次,之后全局只读。</li>
 * </ol>
 */
@DisplayName("静态文案门面")
class IamMessagesTest {

    @AfterEach
    void resetToUnregistered() {
        // 静态状态在用例之间会串,恢复成"未注册"以保证用例独立
        IamMessages.register(null);
    }

    @Test
    @DisplayName("未注册时回落错误码默认文案")
    void fallsBackToDefaultMessageWhenNotRegistered() {
        assertThat(IamMessages.message(ErrorCode.BAD_CREDENTIALS))
                .isEqualTo(ErrorCode.BAD_CREDENTIALS.getDefaultMessage());
        assertThat(IamMessages.message(ErrorCode.SYSTEM_ERROR))
                .isEqualTo(ErrorCode.SYSTEM_ERROR.getDefaultMessage());
    }

    @Test
    @DisplayName("未注册时 Result 仍能产出可读文案")
    void resultStillReadableWithoutMessageSource() {
        Result<Void> result = Result.fail(ErrorCode.BAD_CREDENTIALS);

        assertThat(result.getCode()).isEqualTo(ErrorCode.BAD_CREDENTIALS.getCode());
        assertThat(result.getMessage()).isEqualTo(ErrorCode.BAD_CREDENTIALS.getDefaultMessage());
    }

    @Test
    @DisplayName("注册后按当前语言解析,英文与中文不同")
    void resolvesByLocaleAfterRegistration() {
        IamMessages.register(bundle());

        LocaleContextHolderBackup.run(Locale.SIMPLIFIED_CHINESE, () ->
                assertThat(IamMessages.message(ErrorCode.BAD_CREDENTIALS)).isEqualTo("账号或密码错误"));
        LocaleContextHolderBackup.run(Locale.US, () ->
                assertThat(IamMessages.message(ErrorCode.BAD_CREDENTIALS))
                        .isEqualTo("Incorrect account or password"));
    }

    @Test
    @DisplayName("未知键返回键名本身,便于在页面上直接看出漏配")
    void unknownKeyReturnsKeyItself() {
        IamMessages.register(bundle());

        assertThat(IamMessages.message("page.does.not.exist")).isEqualTo("page.does.not.exist");
    }

    @Test
    @DisplayName("注册 null 等价于取消注册,不抛异常")
    void registeringNullIsSafe() {
        IamMessages.register(bundle());
        IamMessages.register(null);

        assertThat(IamMessages.message(ErrorCode.BAD_CREDENTIALS))
                .isEqualTo(ErrorCode.BAD_CREDENTIALS.getDefaultMessage());
    }

    @Test
    @DisplayName("可本地化的业务异常与自拟文案的业务异常可区分")
    void businessExceptionKnowsWhetherMessageIsLocalizable() {
        BusinessException fromCode = new BusinessException(ErrorCode.BAD_CREDENTIALS);
        BusinessException withOwnText = new BusinessException(ErrorCode.BAD_CREDENTIALS, "该密钥已绑定过账号");

        assertThat(fromCode.isMessageLocalizable()).isTrue();
        assertThat(withOwnText.isMessageLocalizable()).isFalse();
        assertThat(withOwnText.getMessage()).isEqualTo("该密钥已绑定过账号");
    }

    /**
     * 构造与生产一致的文案源。
     *
     * @return 文案源
     */
    private static ResourceBundleMessageSource bundle() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    /**
     * 在指定语言下执行一段逻辑,结束后恢复原语言。
     * <p>{@code LocaleContextHolder} 是线程级静态状态,不恢复会污染后续用例。</p>
     */
    private static final class LocaleContextHolderBackup {

        /**
         * 在指定语言下执行。
         *
         * @param locale 执行期间的语言
         * @param action 待执行逻辑
         */
        static void run(Locale locale, Runnable action) {
            Locale previous = org.springframework.context.i18n.LocaleContextHolder.getLocale();
            org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
            try {
                action.run();
            } finally {
                org.springframework.context.i18n.LocaleContextHolder.setLocale(previous);
            }
        }
    }
}
