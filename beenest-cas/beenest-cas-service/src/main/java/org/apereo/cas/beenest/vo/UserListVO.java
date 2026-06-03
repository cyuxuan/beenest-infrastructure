package org.apereo.cas.beenest.vo;

import lombok.Data;

import java.util.List;

/**
 * 用户列表响应 VO
 */
@Data
public final class UserListVO {

    /** 用户列表 */
    private List<UserDetailVO> users;

    /** 总数 */
    private long total;

    /** 当前页（从 0 开始） */
    private int page;

    /** 每页大小 */
    private int size;

    /** 是否有更多 */
    private boolean hasMore;
}