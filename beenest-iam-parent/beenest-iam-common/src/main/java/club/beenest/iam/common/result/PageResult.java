package club.beenest.iam.common.result;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果包装类。
 * <p>Service 层调用 {@code PageHelper.startPage} 后执行单表查询,
 * 再将 {@code PageInfo<T>} 转换为本对象返回。</p>
 *
 * @param <T> 记录类型
 */
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页码(从 1 开始) */
    private long pageNum;
    /** 每页条数 */
    private long pageSize;
    /** 总记录数 */
    private long total;
    /** 总页数 */
    private long pages;
    /** 当前页数据 */
    private List<T> records;

    public PageResult() {
        this.records = Collections.emptyList();
    }

    public PageResult(long pageNum, long pageSize, long total, long pages, List<T> records) {
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.total = total;
        this.pages = pages;
        this.records = records == null ? Collections.emptyList() : records;
    }

    /**
     * 构建空分页结果。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param <T>      记录类型
     * @return 记录为空的页结果
     */
    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(pageNum, pageSize, 0, 0, Collections.emptyList());
    }

    public long getPageNum() {
        return pageNum;
    }

    public void setPageNum(long pageNum) {
        this.pageNum = pageNum;
    }

    public long getPageSize() {
        return pageSize;
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPages() {
        return pages;
    }

    public void setPages(long pages) {
        this.pages = pages;
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }
}
