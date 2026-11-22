package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.LoginEvent;
import club.beenest.iam.persistence.mapper.LoginEventMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 登录事件存储(iam_login_event,仅追加)。
 * <p>写入在事件监听旁路执行,不参与登录事务;查询按域隔离,按时间倒序。</p>
 */
@Component
public class IamLoginEventManager {

    private final LoginEventMapper loginEventMapper;

    public IamLoginEventManager(LoginEventMapper loginEventMapper) {
        this.loginEventMapper = loginEventMapper;
    }

    /**
     * 记录一条登录事件。
     *
     * @param event 登录事件
     * @return 受影响行数
     */
    public int record(LoginEvent event) {
        return loginEventMapper.insert(event);
    }

    /**
     * 管理端分页查询登录事件。
     *
     * @param realmId 身份域主键
     * @param username 登录名,为空不过滤
     * @param eventType 事件类型,为空不过滤
     * @param ip 来源 IP,为空不过滤
     * @param fromTime 起始时间,为空不限制
     * @param toTime 截止时间,为空不限制
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 登录事件分页结果
     */
    public PageResult<LoginEvent> page(Long realmId, String username, String eventType, String ip,
                                       LocalDateTime fromTime, LocalDateTime toTime,
                                       int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<LoginEvent> list = loginEventMapper.selectByCondition(
                realmId, username, eventType, ip, fromTime, toTime);
        PageInfo<LoginEvent> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }

    /**
     * 按事件类型汇总指定时间点起的事件数(仪表盘)。
     *
     * @param realmId 身份域主键
     * @param since 统计起始时间
     * @return 事件类型 → 事件数
     */
    public Map<String, Long> countByTypeSince(Long realmId, LocalDateTime since) {
        List<Map<String, Object>> rows = loginEventMapper.countByTypeSince(realmId, since);
        Map<String, Long> result = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            Object type = row.get("eventtype");
            Object total = row.get("total");
            if (type != null && total instanceof Number number) {
                result.put(String.valueOf(type), number.longValue());
            }
        }
        return result;
    }
}
