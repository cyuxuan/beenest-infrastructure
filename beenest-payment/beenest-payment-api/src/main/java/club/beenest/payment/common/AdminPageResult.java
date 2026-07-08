package club.beenest.payment.common;

import lombok.Data;

import java.util.List;

/**
 * 管理端分页查询结果
 * 用于 Feign 接口返回强类型分页数据，替代 Map<String, Object>
 *
 * @param <T> 列表元素类型
 */
@Data
public class AdminPageResult<T> {

    /** 总记录数 */
    private Long total;

    /** 数据列表 */
    private List<T> list;

    /** 当前页码 */
    private Integer pageNum;

    /** 每页大小 */
    private Integer pageSize;

    /**
     * 从 PageHelper 的 Page 对象构建 AdminPageResult
     *
     * @param page PageHelper 分页结果
     * @return AdminPageResult
     */
    public static <T> AdminPageResult<T> of(com.github.pagehelper.Page<T> page) {
        AdminPageResult<T> result = new AdminPageResult<>();
        result.setTotal(page.getTotal());
        result.setList(page.getResult());
        result.setPageNum(page.getPageNum());
        result.setPageSize(page.getPageSize());
        return result;
    }

    /**
     * 手动构建 AdminPageResult
     *
     * @param total 总记录数
     * @param list 数据列表
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return AdminPageResult
     */
    public static <T> AdminPageResult<T> of(Long total, List<T> list, Integer pageNum, Integer pageSize) {
        AdminPageResult<T> result = new AdminPageResult<>();
        result.setTotal(total);
        result.setList(list);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        return result;
    }
}
