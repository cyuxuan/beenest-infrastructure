package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.UserSessionStore;
import club.beenest.iam.persistence.converter.UserSessionConverter;
import club.beenest.iam.persistence.entity.UserSession;
import club.beenest.iam.persistence.mapper.UserSessionMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户会话存储(iam_user_session),按 sid 维护会话状态与登录方式。
 */
@Component
public class IamUserSessionManager implements UserSessionStore {

    private final UserSessionMapper userSessionMapper;
    private final UserSessionConverter userSessionConverter;

    public IamUserSessionManager(UserSessionMapper userSessionMapper, UserSessionConverter userSessionConverter) {
        this.userSessionMapper = userSessionMapper;
        this.userSessionConverter = userSessionConverter;
    }

    @Override
    public UserSessionModel insert(UserSessionModel session) {
        // 1. 模型转实体并落库,主键回填
        UserSession entity = userSessionConverter.toEntity(session);
        userSessionMapper.insert(entity);
        // 2. 以落库后的实体重建模型
        return userSessionConverter.toModel(entity);
    }

    @Override
    public UserSessionModel findBySid(String sid) {
        return userSessionConverter.toModel(userSessionMapper.selectBySid(sid));
    }

    @Override
    public void updateState(String sid, String state) {
        userSessionMapper.updateStateBySid(sid, state);
    }

    /** 仅当会话仍处于活动状态时置为过期,返回是否过期成功。 */
    @Override
    public boolean expireIfActive(String sid) {
        return userSessionMapper.expireIfActive(sid) > 0;
    }

    @Override
    public void touch(String sid) {
        userSessionMapper.touchBySid(sid, LocalDateTime.now());
    }

    @Override
    public List<UserSessionModel> listByUser(Long realmId, Long userId) {
        return userSessionConverter.toModelList(userSessionMapper.selectByUserId(realmId, userId));
    }

    @Override
    public List<UserSessionModel> listActiveByUser(Long realmId, Long userId) {
        return userSessionConverter.toModelList(userSessionMapper.selectActiveByUserId(realmId, userId));
    }

    @Override
    public List<UserSessionModel> listActive(int limit) {
        return userSessionConverter.toModelList(userSessionMapper.selectActive(limit));
    }

    /** 更新登录方式;loginType 为空时忽略,不覆盖已有方式。 */
    @Override
    public void updateLoginType(String sid, String loginType) {
        // 只补第二因素时传 null,不覆盖第一因素
        if (loginType == null) {
            return;
        }
        userSessionMapper.updateLoginTypeBySid(sid, loginType);
    }

    /**
     * 管理端分页查询会话记录。
     *
     * @param realmId 身份域主键
     * @param username 登录名,为空不过滤
     * @param state 会话状态,为空不过滤
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 会话分页结果
     */
    public PageResult<UserSession> page(Long realmId, String username, String state,
                                        int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<UserSession> list = userSessionMapper.selectByCondition(realmId, username, state);
        PageInfo<UserSession> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }
}
