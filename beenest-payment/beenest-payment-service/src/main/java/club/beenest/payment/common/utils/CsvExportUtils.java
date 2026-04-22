package club.beenest.payment.common.utils;

/**
 * CSV 导出工具类
 * 提供 CSV 值转义，防止 CSV 注入攻击
 */
public final class CsvExportUtils {

    private CsvExportUtils() {}

    /**
     * CSV 值转义，防止 CSV 注入攻击
     * 处理包含逗号、换行符、引号以及公式字符的字段
     */
    public static String escapeValue(String value) {
        if (value == null) {
            return "";
        }
        if (value.startsWith("=") || value.startsWith("+") ||
                value.startsWith("-") || value.startsWith("@")) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\n") ||
                value.contains("\r") || value.contains("\"")) {
            value = "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * 写入 CSV BOM 头（UTF-8 with BOM，Excel 兼容）
     */
    public static byte[] bom() {
        return new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
    }
}
