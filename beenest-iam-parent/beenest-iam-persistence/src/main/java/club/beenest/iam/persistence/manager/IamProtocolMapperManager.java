package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.ProtocolMapper;
import club.beenest.iam.persistence.mapper.ProtocolMapperMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 协议映射器存储(iam_protocol_mapper)。
 * <p>把用户属性或固定值映射为令牌 claim;域级映射对全部客户端生效,客户端级映射仅对该客户端生效。</p>
 */
@Component
public class IamProtocolMapperManager {

    private final ProtocolMapperMapper protocolMapperMapper;

    public IamProtocolMapperManager(ProtocolMapperMapper protocolMapperMapper) {
        this.protocolMapperMapper = protocolMapperMapper;
    }

    /**
     * 按主键查询。
     *
     * @param id 映射器主键
     * @return 映射器;不存在返回 null
     */
    public ProtocolMapper getById(Long id) {
        return protocolMapperMapper.selectById(id);
    }

    /**
     * 查询对某客户端生效的全部映射(域级 + 客户端级,令牌定制用)。
     *
     * @param realmId 身份域主键
     * @param clientId 客户端主键;为空时只返回域级映射
     * @return 生效的映射列表
     */
    public List<ProtocolMapper> listEffective(Long realmId, Long clientId) {
        return clientId == null
                ? protocolMapperMapper.selectRealmLevel(realmId)
                : protocolMapperMapper.selectEffectiveByClientId(realmId, clientId);
    }

    /**
     * 查询域级映射(client_id 为空)。
     *
     * @param realmId 身份域主键
     * @return 域级映射列表
     */
    public List<ProtocolMapper> listRealmLevel(Long realmId) {
        return protocolMapperMapper.selectRealmLevel(realmId);
    }

    /**
     * 分页查询(域级 + 指定客户端)。
     *
     * @param realmId 身份域主键
     * @param clientId 客户端主键;为空时只查域级映射
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 映射器分页结果
     */
    public PageResult<ProtocolMapper> page(Long realmId, Long clientId, int pageNum, int pageSize) {
        // 单表查询,PageHelper 改写 SQL 并统计总数
        PageHelper.startPage(pageNum, pageSize);
        List<ProtocolMapper> list = clientId == null
                ? protocolMapperMapper.selectRealmLevel(realmId)
                : protocolMapperMapper.selectEffectiveByClientId(realmId, clientId);
        PageInfo<ProtocolMapper> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }

    /**
     * 新建映射器。
     *
     * @param protocolMapper 映射器实体,主键回填
     * @return 受影响行数
     */
    public int create(ProtocolMapper protocolMapper) {
        return protocolMapperMapper.insert(protocolMapper);
    }

    /**
     * 更新非空字段。
     *
     * @param protocolMapper 映射器实体,需含主键
     * @return 受影响行数
     */
    public int update(ProtocolMapper protocolMapper) {
        return protocolMapperMapper.updateById(protocolMapper);
    }

    /**
     * 逻辑删除。
     *
     * @param id 映射器主键
     * @return 受影响行数
     */
    public int remove(Long id) {
        return protocolMapperMapper.softDeleteById(id);
    }
}
