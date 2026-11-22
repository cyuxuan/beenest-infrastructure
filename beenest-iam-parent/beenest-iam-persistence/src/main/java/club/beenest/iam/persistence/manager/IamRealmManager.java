package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.converter.RealmConverter;
import club.beenest.iam.persistence.entity.Realm;
import club.beenest.iam.persistence.mapper.RealmMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 身份域存储(iam_realm)。
 * <p>提供 RealmProvider 的领域模型读取,以及管理端的单表 CRUD 与分页查询。</p>
 */
@Component
public class IamRealmManager implements RealmProvider {

    private final RealmMapper realmMapper;
    private final RealmConverter realmConverter;

    public IamRealmManager(RealmMapper realmMapper, RealmConverter realmConverter) {
        this.realmMapper = realmMapper;
        this.realmConverter = realmConverter;
    }

    // ===== 认证授权侧:领域模型读取 =====

    @Override
    public Optional<RealmModel> findByRealmKey(String realmKey) {
        return Optional.ofNullable(realmConverter.toModel(
                realmMapper.selectByRealmKey(realmKey)));
    }

    @Override
    public Optional<RealmModel> findById(Long id) {
        return Optional.ofNullable(realmConverter.toModel(realmMapper.selectById(id)));
    }

    @Override
    public List<RealmModel> listEnabled() {
        return realmMapper.selectEnabled().stream()
                .map(realmConverter::toModel)
                .toList();
    }

    // ===== 管理侧:单表 CRUD =====

    /**
     * 按主键查询实体。
     *
     * @param id 身份域主键
     * @return 身份域;不存在返回 null
     */
    public Realm getById(Long id) {
        return realmMapper.selectById(id);
    }

    /**
     * 按域标识查询实体(用于唯一性校验)。
     *
     * @param realmKey 域标识
     * @return 身份域;不存在返回 null
     */
    public Realm getByRealmKey(String realmKey) {
        return realmMapper.selectByRealmKey(realmKey);
    }

    /**
     * 查询默认域。
     *
     * @return 默认身份域;未配置返回 null
     */
    public Realm getDefault() {
        return realmMapper.selectDefaultRealm();
    }

    /**
     * 分页查询身份域。
     *
     * @param condition 查询条件,非空字段参与过滤
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 身份域分页结果
     */
    public PageResult<Realm> page(Realm condition, int pageNum, int pageSize) {
        // 1. 开启分页后执行单表查询,PageHelper 改写 SQL 并装填总数
        PageHelper.startPage(pageNum, pageSize);
        List<Realm> list = realmMapper.selectByCondition(condition);
        // 2. 组装统一分页结果
        PageInfo<Realm> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }

    /**
     * 新建身份域,回填主键。
     *
     * @param realm 身份域实体
     * @return 受影响行数
     */
    public int create(Realm realm) {
        return realmMapper.insert(realm);
    }

    /**
     * 更新身份域非空字段。
     *
     * @param realm 身份域实体,需含主键
     * @return 受影响行数
     */
    public int update(Realm realm) {
        return realmMapper.updateById(realm);
    }

    /**
     * 逻辑删除身份域。
     *
     * @param id 身份域主键
     * @return 受影响行数
     */
    public int remove(Long id) {
        return realmMapper.softDeleteById(id);
    }
}
