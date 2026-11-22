package club.beenest.iam.common.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体公共基类。
 * <p>对应通用列:id / deleted / created_time / updated_time。
 * 时间字段由数据库默认值或 MyBatis 拦截器填充。</p>
 */
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;
    /** 逻辑删除标记:true 表示已删除 */
    private Boolean deleted;
    /** 创建时间 */
    private LocalDateTime createdTime;
    /** 更新时间 */
    private LocalDateTime updatedTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime updatedTime) {
        this.updatedTime = updatedTime;
    }
}
